package dev.omalauncher.local

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Gemma in the launcher's own process: loaded on first use, on the GPU when the phone lets it and on the CPU
 * otherwise, and unloaded after [IDLE_MS] unused, as it takes gigabytes. One turn at a time ([turn]), Voice's or
 * Linux's; one conversation kept between turns ([conversation]), so a coding agent that sends its whole history
 * again only has the new part read.
 */
class LocalModel(
    private val context: Context,
    private val home: () -> File,
) {
    private val lock = Semaphore(1, true)
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gemma-idle").apply { isDaemon = true } }
    private var unload: ScheduledFuture<*>? = null

    private var engine: Engine? = null

    /** How it runs ("gpu", "gpu-fp32", "cpu"), once loaded. */
    @Volatile var backend: String? = null
        private set

    private var kept: Conversation? = null
    private var keptKey: String? = null

    val present get() = Gemma.present(home())

    /** Runs [block] with the model loaded and to itself; it waits for a turn in progress. */
    fun <T> turn(block: (Engine) -> T): T {
        lock.acquire()
        try {
            unload?.cancel(false)
            return block(load())
        } finally {
            lock.release()
            unload = timer.schedule(Runnable { idle() }, IDLE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * In a [turn]: the conversation kept under [key], or a new one made with [config], the kept one closed first.
     * After the turn, [keep] it under what it has become.
     */
    fun conversation(
        engine: Engine,
        key: String,
        config: () -> ConversationConfig,
    ): Pair<Conversation, Boolean> {
        val c = kept
        if (c != null && keptKey == key && c.isAlive) return c to true
        drop()
        val made = engine.createConversation(config())
        kept = made
        keptKey = null
        return made to false
    }

    /** In a [turn]: [c] is kept for a next turn that starts with what [key] stands for. */
    fun keep(
        c: Conversation,
        key: String,
    ) {
        if (kept === c) keptKey = key
    }

    /**
     * In a [turn]: a conversation used once and closed by the caller, made next to the kept one when the engine allows
     * it (a title asked between two turns of a session then costs it nothing), else in its place.
     */
    fun scratch(
        engine: Engine,
        config: ConversationConfig,
    ): Conversation =
        runCatching { engine.createConversation(config) }.getOrElse {
            drop()
            engine.createConversation(config)
        }

    /** In a [turn]: forgets the kept conversation, as after a failed or stopped turn. */
    fun drop() {
        kept?.let { runCatching { it.close() } }
        kept = null
        keptKey = null
    }

    /** One way to load the model: [label] for the log, and the config it loads with. */
    private class Attempt(
        val label: String,
        val gpu: Boolean,
        val config: (path: String, cache: String) -> EngineConfig,
    )

    /**
     * The ways to load, best first: the GPU as LiteRT-LM picks (FLOAT16, speculative decoding), the GPU in FLOAT32
     * without speculative decoding (what older Adreno GPUs answer sense with, google-ai-edge/LiteRT-LM#3879), then
     * the CPU. Each must answer a one-word test sensibly before it's used.
     */
    private val attempts =
        listOf(
            Attempt("gpu", true) { path, cache -> EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = Gemma.MAX_TOKENS, cacheDir = cache) },
            Attempt("gpu-fp32", true) { path, cache ->
                EngineConfig(
                    modelPath = path,
                    backend = Backend.GPU(),
                    maxNumTokens = Gemma.MAX_TOKENS,
                    cacheDir = cache,
                    activationDataType = ActivationDataType.FLOAT32,
                    speculativeDecodingConfig = SpeculativeDecodingConfig(SpeculativeDecodingMode.OFF),
                )
            },
            Attempt("cpu", false) { path, cache -> EngineConfig(modelPath = path, backend = Backend.CPU(CPU_THREADS), maxNumTokens = Gemma.MAX_TOKENS, cacheDir = cache) },
        )

    /** Ways that failed for good: they crashed the launcher, or answered nonsense. */
    private val brokenFile get() = File(context.filesDir, "gemma-broken")

    /** The way being loaded, left behind when a load takes the launcher down. */
    private val loadingFile get() = File(context.filesDir, "gemma-loading")

    private fun load(): Engine {
        engine?.let { return it }
        val file = Gemma.modelFile(home())
        check(Gemma.present(home())) { "Gemma 4 E2B isn't on this phone yet" }
        val broken = runCatching { brokenFile.readLines().toMutableSet() }.getOrDefault(mutableSetOf())
        // A load still marked from last time took the launcher down with it.
        runCatching { loadingFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            note("$it crashed the launcher last time: not tried again")
            broken += it
            brokenFile.writeText(broken.joinToString("\n"))
        }
        loadingFile.delete()
        note("loading ${file.path} (${file.length()} bytes) on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE}, LiteRT-LM ${LITERT_LM}")
        var failure: Throwable? = null
        for (a in attempts) {
            if (a.label in broken) {
                note("${a.label}: skipped, it failed before")
                continue
            }
            val cache = File(context.cacheDir, "litert-lm-${a.label}").apply { mkdirs() }.path
            loadingFile.writeText(a.label)
            var e: Engine? = null
            try {
                val t0 = System.currentTimeMillis()
                e = Engine(a.config(file.path, cache))
                e.initialize()
                val t1 = System.currentTimeMillis()
                val answer =
                    e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use {
                        it.sendMessage("Reply with only the word OK.").contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }
                    }
                val t2 = System.currentTimeMillis()
                note("${a.label}: loaded in ${t1 - t0} ms, test answered ${answer.take(80).replace('\n', ' ')} in ${t2 - t1} ms")
                if (!answer.contains("ok", ignoreCase = true)) {
                    note("${a.label}: that's not OK: nonsense from this backend, so the next one")
                    broken += a.label
                    brokenFile.writeText(broken.joinToString("\n"))
                    runCatching { e.close() }
                    continue
                }
                engine = e
                backend = a.label
                note("using ${a.label}")
                return e
            } catch (t: Throwable) {
                note("${a.label}: failed: $t")
                Log.w(TAG, "couldn't load on ${a.label}", t)
                failure = t
                e?.let { runCatching { it.close() } }
            } finally {
                loadingFile.delete()
            }
        }
        throw IllegalStateException("couldn't load Gemma: ${failure?.message}", failure)
    }

    /** For the user and for us: how loading went, in Download/omalauncher/gemma.log (this phone has no logcat). */
    private fun note(message: String) {
        Log.i(TAG, message)
        runCatching { File(LOG).appendText("${java.util.Date()} $message\n") }
    }

    private fun idle() {
        if (!lock.tryAcquire()) return
        try {
            drop()
            engine?.let { runCatching { it.close() } }
            engine = null
            backend = null
            note("unloaded after ${IDLE_MS / 60000} minutes unused")
        } finally {
            lock.release()
        }
    }

    companion object {
        private const val TAG = "gemma"
        const val IDLE_MS = 10 * 60 * 1000L
        private const val CPU_THREADS = 4
        private const val LOG = "/storage/emulated/0/Download/omalauncher/gemma.log"

        /** The LiteRT-LM in this build, for the log. */
        private const val LITERT_LM = "0.19.0"
    }
}
