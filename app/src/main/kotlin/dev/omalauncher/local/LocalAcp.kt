package dev.omalauncher.local

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gemma as Voice's agent, with no process in Linux: a [Process] whose stdin and stdout speak ACP (as `opencode acp`
 * would), answered here with the launcher's [LocalModel], so Voice's AcpLink talks to it as to any agent. It only
 * talks: no tools. Conversations are kept in [dir] so a resumed one goes on where it was.
 */
class LocalAcp(
    private val model: LocalModel,
    private val dir: File,
) : Process() {
    private val out = LinkedBlockingQueue<ByteArray>()
    private val ended = CountDownLatch(1)
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "gemma-voice").apply { isDaemon = true } }

    @Volatile private var alive = true

    @Volatile private var current: com.google.ai.edge.litertlm.Conversation? = null

    private var session: String? = null

    /** The conversation so far: ("user" | "model", text). */
    private val history = ArrayList<Pair<String, String>>()

    private val stdin =
        object : OutputStream() {
            private val line = java.io.ByteArrayOutputStream()

            override fun write(b: Int) {
                if (b == '\n'.code) {
                    val s = line.toString(Charsets.UTF_8.name())
                    line.reset()
                    if (s.isNotBlank()) receive(s)
                } else {
                    line.write(b)
                }
            }
        }

    private val stdout =
        object : InputStream() {
            private var chunk: ByteArray = ByteArray(0)
            private var at = 0

            override fun read(): Int {
                if (!fill()) return -1
                return chunk[at++].toInt() and 0xff
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (len == 0) return 0
                if (!fill()) return -1
                val n = minOf(len, chunk.size - at)
                System.arraycopy(chunk, at, b, off, n)
                at += n
                return n
            }

            private fun fill(): Boolean {
                while (at >= chunk.size) {
                    val next = out.poll(200, TimeUnit.MILLISECONDS)
                    if (next == null) {
                        if (!alive) return false
                        continue
                    }
                    if (next.isEmpty()) return false
                    chunk = next
                    at = 0
                }
                return true
            }
        }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        ended.await()
        return 0
    }

    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException("still running")
        return 0
    }

    override fun isAlive() = alive

    override fun destroy() {
        if (!alive) return
        alive = false
        runCatching { current?.cancelProcess() }
        work.shutdownNow()
        out.put(ByteArray(0))
        ended.countDown()
    }

    override fun toString() = "LocalAcp[gemma]"

    // ---------- ACP

    private fun send(o: JSONObject) {
        if (alive) out.put((o.put("jsonrpc", "2.0").toString() + "\n").toByteArray())
    }

    private fun result(
        id: Any,
        r: JSONObject,
    ) = send(JSONObject().put("id", id).put("result", r))

    private fun fail(
        id: Any,
        message: String,
        code: Int = -32000,
    ) = send(JSONObject().put("id", id).put("error", JSONObject().put("code", code).put("message", message)))

    private fun receive(line: String) {
        val m = runCatching { JSONObject(line) }.getOrNull() ?: return
        val id = m.opt("id")
        val params = m.optJSONObject("params") ?: JSONObject()
        when (m.optString("method")) {
            "initialize" ->
                id?.let {
                    result(it, JSONObject().put("protocolVersion", 1).put("agentCapabilities", JSONObject().put("loadSession", true)).put("authMethods", JSONArray()))
                }
            "session/new" ->
                id?.let {
                    session = UUID.randomUUID().toString()
                    history.clear()
                    result(it, JSONObject().put("sessionId", session))
                }
            "session/load" ->
                id?.let {
                    val s = params.optString("sessionId")
                    val saved = load(s)
                    if (saved == null) {
                        fail(it, "no conversation $s")
                    } else {
                        session = s
                        history.clear()
                        history += saved
                        result(it, JSONObject())
                    }
                }
            "session/prompt" -> id?.let { rpc -> work.execute { prompt(rpc, params) } }
            "session/cancel" -> runCatching { current?.cancelProcess() }
            else -> id?.let { fail(it, "${m.optString("method")} isn't supported", -32601) }
        }
    }

    private fun prompt(
        rpc: Any,
        params: JSONObject,
    ) {
        val text =
            params.optJSONArray("prompt")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.takeIf { p -> p.optString("type") == "text" }?.optString("text") }.joinToString("\n")
            }.orEmpty()
        if (!model.present) return fail(rpc, "Gemma 4 E2B isn't on this phone yet: install Gemma 4 (local) in Agents to download it.")
        val s = session ?: return fail(rpc, "no session")
        var cancelled = false
        val reply = StringBuilder()
        val problem =
            runCatching {
                model.turn { engine ->
                    // Gemma takes the conversation so far as initial messages: Voice's turns are short.
                    val key = "voice:$s:${history.size}"
                    val config =
                        ConversationConfig(
                            initialMessages = history.map { (who, t) -> if (who == "user") Message.user(t) else Message.model(t) },
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0),
                        )
                    val conv = model.conversation(engine, key) { config }.first
                    current = conv
                    val done = CountDownLatch(1)
                    var error: Throwable? = null
                    conv.sendMessageAsync(
                        Message.user(text),
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val t = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                                if (t.isEmpty()) return
                                reply.append(t)
                                val chunk = JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", JSONObject().put("type", "text").put("text", t))
                                send(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", s).put("update", chunk)))
                            }

                            override fun onDone() = done.countDown()

                            override fun onError(throwable: Throwable) {
                                error = throwable
                                done.countDown()
                            }
                        },
                    )
                    done.await()
                    current = null
                    val e = error
                    if (e is java.util.concurrent.CancellationException) cancelled = true
                    if (e != null && !cancelled) throw e
                    if (cancelled) {
                        model.drop()
                    } else {
                        history += "user" to text
                        history += "model" to reply.toString()
                        model.keep(conv, "voice:$s:${history.size}")
                    }
                }
            }.exceptionOrNull()
        when {
            problem != null -> {
                Log.w(TAG, "turn failed", problem)
                fail(rpc, problem.message ?: problem.toString())
            }
            else -> {
                if (!cancelled) save(s)
                result(rpc, JSONObject().put("stopReason", if (cancelled) "cancelled" else "end_turn"))
            }
        }
    }

    // ---------- kept conversations

    private fun file(s: String) = File(dir, s.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    private fun save(s: String) {
        runCatching {
            dir.mkdirs()
            val a = JSONArray()
            for ((who, t) in history) a.put(JSONObject().put("who", who).put("text", t))
            file(s).writeText(a.toString())
            // Old conversations go after a month.
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 30L * 24 * 3600 * 1000 }?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "can't keep the conversation", it) }
    }

    private fun load(s: String): List<Pair<String, String>>? =
        runCatching {
            val a = JSONArray(file(s).readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("who") to o.getString("text") } }
        }.getOrNull()

    companion object {
        private const val TAG = "gemma-voice"
    }
}
