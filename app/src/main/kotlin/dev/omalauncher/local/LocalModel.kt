package dev.omalauncher.local

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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

    /** The backend it runs on ("GPU", "CPU"), once loaded. */
    @Volatile var backend: String? = null
        private set

    private var kept: Conversation? = null
    private var keptKey: String? = null

    /** A GPU load that crashed the launcher left this behind: the CPU from then on. */
    private val gpuMark get() = File(context.filesDir, "gemma-gpu-loading")
    private val gpuBroken get() = File(context.filesDir, "gemma-gpu-broken")

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

    private fun load(): Engine {
        engine?.let { return it }
        val file = Gemma.modelFile(home())
        check(Gemma.present(home())) { "Gemma 4 E2B isn't on this phone yet" }
        // A GPU load still marked from last time took the launcher down with it.
        if (gpuMark.exists()) {
            gpuMark.delete()
            gpuBroken.createNewFile()
        }
        val cache = File(context.cacheDir, "litert-lm").apply { mkdirs() }.path
        val backends = if (gpuBroken.exists()) listOf(Backend.CPU(CPU_THREADS)) else listOf(Backend.GPU(), Backend.CPU(CPU_THREADS))
        var failure: Throwable? = null
        for (b in backends) {
            val gpu = b is Backend.GPU
            if (gpu) gpuMark.createNewFile()
            try {
                val e = Engine(EngineConfig(modelPath = file.path, backend = b, maxNumTokens = Gemma.MAX_TOKENS, cacheDir = cache))
                e.initialize()
                engine = e
                backend = b.name
                Log.i(TAG, "loaded on ${b.name}")
                return e
            } catch (t: Throwable) {
                Log.w(TAG, "couldn't load on ${b.name}", t)
                failure = t
            } finally {
                if (gpu) gpuMark.delete()
            }
        }
        throw IllegalStateException("couldn't load Gemma: ${failure?.message}", failure)
    }

    private fun idle() {
        if (!lock.tryAcquire()) return
        try {
            drop()
            engine?.let { runCatching { it.close() } }
            engine = null
            backend = null
            Log.i(TAG, "unloaded")
        } finally {
            lock.release()
        }
    }

    companion object {
        private const val TAG = "gemma"
        const val IDLE_MS = 10 * 60 * 1000L
        private const val CPU_THREADS = 4
    }
}
