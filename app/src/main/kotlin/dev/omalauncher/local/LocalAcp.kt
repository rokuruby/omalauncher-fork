package dev.omalauncher.local

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dev.omalauncher.linux.shellQuote
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
    /** Runs a shell command in Linux, with what to give it on stdin; its exit code and output. */
    private val guest: (script: String, stdin: ByteArray?) -> Pair<Int, String>,
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
        cancel()
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

    /** Requests of ours (asking before a write) waiting for Voice's answer, by id. */
    private val asked = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<JSONObject?>>()
    private val nextAsk = java.util.concurrent.atomic.AtomicInteger(0)

    private fun receive(line: String) {
        val m = runCatching { JSONObject(line) }.getOrNull() ?: return
        val id = m.opt("id")
        val params = m.optJSONObject("params") ?: JSONObject()
        // An answer to one of our questions.
        if (m.optString("method").isEmpty() && id != null) {
            asked.remove(m.optInt("id", -1))?.complete(m.optJSONObject("result"))
            return
        }
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
            "session/cancel" -> cancel()
            else -> id?.let { fail(it, "${m.optString("method")} isn't supported", -32601) }
        }
    }

    @Volatile private var stopped = false

    private fun cancel() {
        stopped = true
        runCatching { current?.cancelProcess() }
        // A question still open is answered no.
        asked.values.forEach { it.complete(null) }
        asked.clear()
    }

    private fun update(
        s: String,
        u: JSONObject,
    ) = send(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", s).put("update", u)))

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
        stopped = false
        val reply = StringBuilder()
        val problem =
            runCatching {
                model.turn { engine ->
                    // Gemma takes the conversation so far as initial messages: Voice's turns are short.
                    val key = "voice:$s:${history.size}"
                    val config =
                        ConversationConfig(
                            systemInstruction = Contents.of(INSTRUCTIONS),
                            initialMessages = history.map { (who, t) -> if (who == "user") Message.user(t) else Message.model(t) },
                            tools = TOOLS.map { com.google.ai.edge.litertlm.tool(it) },
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0),
                            automaticToolCalling = false,
                        )
                    val conv = model.conversation(engine, key) { config }.first
                    current = conv
                    var next: Message = Message.user(text)
                    // Each round: what it says, then the tools it calls, whose results go back to it.
                    for (round in 0..MAX_TOOL_ROUNDS) {
                        val calls = ArrayList<com.google.ai.edge.litertlm.ToolCall>()
                        val done = CountDownLatch(1)
                        var error: Throwable? = null
                        conv.sendMessageAsync(
                            next,
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    calls += message.toolCalls
                                    val t = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                                    if (t.isEmpty()) return
                                    reply.append(t)
                                    update(s, JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", JSONObject().put("type", "text").put("text", t)))
                                }

                                override fun onDone() = done.countDown()

                                override fun onError(throwable: Throwable) {
                                    error = throwable
                                    done.countDown()
                                }
                            },
                        )
                        done.await()
                        val e = error
                        if (e is java.util.concurrent.CancellationException || stopped) break
                        if (e != null) throw e
                        if (calls.isEmpty() || round == MAX_TOOL_ROUNDS) break
                        val results = calls.map { c -> Content.ToolResponse(c.name, use(s, c)) }
                        if (stopped) break
                        next = Message.tool(Contents.of(results))
                        if (reply.isNotEmpty() && !reply.last().isWhitespace()) reply.append(' ')
                    }
                    current = null
                    if (stopped) {
                        model.drop()
                    } else {
                        history += "user" to text
                        history += "model" to reply.toString()
                        model.keep(conv, "voice:$s:${history.size}")
                    }
                }
            }.exceptionOrNull()
        current = null
        when {
            problem != null && !stopped -> {
                Log.w(TAG, "turn failed", problem)
                fail(rpc, problem.message ?: problem.toString())
            }
            else -> {
                if (!stopped) save(s)
                result(rpc, JSONObject().put("stopReason", if (stopped) "cancelled" else "end_turn"))
            }
        }
    }

    // ---------- tools: the user's files, in Linux

    /** Runs one of the model's tool calls and says what it gave; a write is asked about first. */
    private fun use(
        s: String,
        call: com.google.ai.edge.litertlm.ToolCall,
    ): String {
        val path = guestPath(call.arguments["path"]?.toString().orEmpty())
        val id = "t${System.nanoTime()}"
        val (kind, title) =
            when (call.name) {
                "list_folder" -> "read" to "List $path"
                "read_file" -> "read" to "Read $path"
                "write_file" -> "edit" to "Write $path"
                else -> return "There's no tool called ${call.name}."
            }
        if (path.isEmpty()) return "No path was given."
        val raw = JSONObject().put("path", path)
        val toolCall = JSONObject().put("toolCallId", id).put("title", title).put("kind", kind).put("rawInput", raw).put("locations", JSONArray().put(JSONObject().put("path", path)))
        update(s, JSONObject(toolCall.toString()).put("sessionUpdate", "tool_call").put("status", "in_progress"))
        val q = shellQuote(path)
        val answer =
            when (call.name) {
                "list_folder" -> guest("ls -la -- $q 2>&1 | head -c $MAX_LIST", null).second.ifBlank { "(empty)" }
                "read_file" -> {
                    val (code, out) = guest("head -c $MAX_READ -- $q 2>&1; s=\$(wc -c < $q 2>/dev/null) && [ \"\$s\" -gt $MAX_READ ] && printf '\\n[only the first $MAX_READ of %s bytes]' \"\$s\"; true", null)
                    if (code != 0 && out.isBlank()) "Couldn't read $path." else out
                }
                "write_file" -> {
                    val content = call.arguments["content"]?.toString().orEmpty()
                    if (!allowed(s, id, toolCall)) {
                        "The user said no: $path wasn't written."
                    } else {
                        val (code, out) = guest("mkdir -p -- \"\$(dirname -- $q)\" && cat > $q", content.toByteArray())
                        if (code == 0) "Wrote ${content.toByteArray().size} bytes to $path." else "Couldn't write $path: ${out.take(300)}"
                    }
                }
                else -> ""
            }
        update(s, JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", id).put("status", "completed"))
        return answer
    }

    /** Asks Voice before a write (it may answer at once, for "allow all" or an earlier "always"); false for no. */
    private fun allowed(
        s: String,
        id: String,
        toolCall: JSONObject,
    ): Boolean {
        val rpc = 1_000_000 + nextAsk.incrementAndGet()
        val answer = java.util.concurrent.CompletableFuture<JSONObject?>()
        asked[rpc] = answer
        val options =
            JSONArray()
                .put(JSONObject().put("optionId", "allow").put("name", "Allow").put("kind", "allow_once"))
                .put(JSONObject().put("optionId", "always").put("name", "Always allow").put("kind", "allow_always"))
                .put(JSONObject().put("optionId", "reject").put("name", "Don't allow").put("kind", "reject_once"))
        send(JSONObject().put("id", rpc).put("method", "session/request_permission").put("params", JSONObject().put("sessionId", s).put("toolCall", toolCall).put("options", options)))
        val r = runCatching { answer.get() }.getOrNull() ?: return false
        val picked = r.optJSONObject("outcome")?.optString("optionId")
        return picked == "allow" || picked == "always"
    }

    /** A path as Linux takes it: relative ones from the home folder. */
    private fun guestPath(p: String): String {
        val t = p.trim().removeSurrounding("\"").removeSurrounding("'")
        return when {
            t.isEmpty() -> ""
            t == "~" -> "/root"
            t.startsWith("~/") -> "/root/" + t.removePrefix("~/")
            t.startsWith("/") -> t
            else -> "/root/$t"
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

        /** How many times a turn may go back to its tools before it has to answer. */
        private const val MAX_TOOL_ROUNDS = 6

        /** Bytes of a file or a folder listing the model reads: more would crowd out the conversation. */
        private const val MAX_READ = 12000
        private const val MAX_LIST = 6000

        private val INSTRUCTIONS =
            "You can use tools on the user's phone: list_folder, read_file and write_file. Paths are Linux paths: the " +
                "home folder is /root and the phone's Download folder is /sdcard/Download. Use a path exactly as the " +
                "user gives it; a path starting with / is absolute. When you don't know where a file is, list the folder " +
                "first. To change a file, read it, then write its whole new content. Writing asks the user first. Use the " +
                "tools only when the user asks about their files."

        private fun spec(
            name: String,
            description: String,
            vararg params: Pair<String, String>,
        ) = object : com.google.ai.edge.litertlm.OpenApiTool {
            private val json =
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().apply { params.forEach { (k, d) -> put(k, JSONObject().put("type", "string").put("description", d)) } })
                            .put("required", JSONArray(params.map { it.first })),
                    ).toString()

            override fun getToolDescriptionJsonString() = json

            // Never called: the calls are run here (automaticToolCalling = false).
            override fun execute(paramsJsonString: String) = "{}"
        }

        private val TOOLS =
            listOf(
                spec("list_folder", "Lists the files and folders in a folder.", "path" to "The folder, like /sdcard/Download"),
                spec("read_file", "Reads a text file.", "path" to "The file"),
                spec("write_file", "Writes a text file, replacing what was in it. The user is asked first.", "path" to "The file", "content" to "The whole new content"),
            )
    }
}
