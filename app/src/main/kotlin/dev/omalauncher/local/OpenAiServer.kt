package dev.omalauncher.local

import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The launcher's Gemma for Linux: an OpenAI-compatible server on 127.0.0.1:[Gemma.PORT], like `litert-lm serve`, with
 * /v1/models and /v1/chat/completions (streamed or not, with tools). Coding agents like OpenCode send their whole
 * conversation with every request; when it goes on from the last one, the kept conversation is continued and only
 * the new messages are read, which on a phone is the difference between seconds and minutes.
 */
class OpenAiServer(
    private val model: LocalModel,
) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "gemma-http").apply { isDaemon = true } }
    private var socket: ServerSocket? = null

    /** Starts listening; a port already taken (litert-lm serve running in Linux) leaves that one serving. */
    fun start() {
        if (socket != null) return
        Thread({
            val s =
                runCatching { ServerSocket(Gemma.PORT, 16, InetAddress.getByName("127.0.0.1")) }.getOrElse {
                    Log.w(TAG, "can't listen on ${Gemma.PORT}", it)
                    return@Thread
                }
            socket = s
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                pool.execute { runCatching { c.use(::serve) }.onFailure { Log.w(TAG, "request failed", it) } }
            }
        }, "gemma-listen").apply { isDaemon = true }.start()
    }

    private fun serve(c: Socket) {
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val head = readHead(input) ?: return
        val (method, path) = head.first.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "").substringBefore('?') }
        val headers = head.second
        when {
            method == "GET" && (path == "/v1/models" || path == "/models") -> {
                val m = JSONObject().put("id", Gemma.MODEL_ID).put("object", "model").put("created", 0).put("owned_by", "omalauncher")
                respond(out, 200, JSONObject().put("object", "list").put("data", JSONArray().put(m)))
            }
            method == "GET" && path == "/" -> respond(out, 200, JSONObject().put("status", "ok").put("model", Gemma.MODEL_ID).put("present", model.present))
            method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") -> {
                val body = readBody(input, headers)
                val req = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrElse { return respond(out, 400, problem("the body isn't JSON")) }
                chat(req, input, out)
            }
            method == "OPTIONS" -> respond(out, 204, null)
            else -> respond(out, 404, problem("no $method $path here"))
        }
    }

    // ---------- chat completions

    private fun chat(
        req: JSONObject,
        input: InputStream,
        out: OutputStream,
    ) {
        if (!model.present) {
            return respond(out, 503, problem("Gemma 4 E2B isn't on this phone yet: install Gemma 4 (local) in the launcher's Agents, or run gemma-code, to download it (${Gemma.SIZE_TEXT})."))
        }
        val chat = runCatching { Chat.of(req) }.getOrElse { return respond(out, 400, problem(it.message ?: "bad request")) }
        val stream = req.optBoolean("stream")
        val usage = req.optJSONObject("stream_options")?.optBoolean("include_usage") == true
        val id = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        val sink = if (stream) Sse(out, id, created).also { it.open() } else null

        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        var chunks = 0
        var failure: Throwable? = null
        var total = 0
        // The client hanging up (a reply cancelled in OpenCode, a timeout) stops the work for it: otherwise the
        // model goes on answering no one, and every request after it waits its turn behind that.
        val gone = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val active = AtomicReference<Conversation?>(null)
        Thread({
            runCatching { while (input.read() >= 0) Unit }
            if (!finished.get()) {
                gone.set(true)
                active.get()?.let { c -> runCatching { c.cancelProcess() } }
            }
        }, "gemma-watch").apply { isDaemon = true }.start()
        model.runCatching {
            turn { engine ->
                // Gone while it waited for the request before it: nothing to do.
                if (gone.get()) return@turn Unit
                val config =
                    ConversationConfig(
                        systemInstruction = chat.system.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                        initialMessages = chat.context.map { it.message },
                        tools = chat.tools.map { tool(it) },
                        samplerConfig = chat.sampler,
                        automaticToolCalling = false,
                    )
                val oneShot = chat.context.isEmpty() && chat.tools.isEmpty()
                val conv = if (oneShot) scratch(engine, config) else conversation(engine, chat.contextKey) { config }.first
                val done = CountDownLatch(1)
                active.set(conv)
                if (gone.get()) {
                    active.set(null)
                    if (oneShot) runCatching { conv.close() } else drop()
                    return@turn Unit
                }
                conv.sendMessageAsync(
                    chat.prompt.message,
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val t = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                            if (t.isNotEmpty()) {
                                text.append(t)
                                chunks++
                                if (sink != null && !gone.get() && !sink.text(t)) {
                                    gone.set(true)
                                    runCatching { conv.cancelProcess() }
                                }
                            }
                            calls += message.toolCalls
                        }

                        override fun onDone() = done.countDown()

                        override fun onError(throwable: Throwable) {
                            failure = throwable
                            done.countDown()
                        }
                    },
                    maxOutputToken = chat.maxTokens,
                )
                done.await()
                active.set(null)
                total = runCatching { conv.getTokenCount() }.getOrDefault(0)
                when {
                    oneShot -> runCatching { conv.close() }
                    failure != null || gone.get() -> drop()
                    else -> keep(conv, chat.keyAfter(text.toString(), calls))
                }
            }
        }.onFailure { failure = it }
        finished.set(true)
        if (gone.get()) return

        val finish = if (calls.isNotEmpty()) "tool_calls" else "stop"
        val toolCalls =
            JSONArray().apply {
                calls.forEachIndexed { i, c ->
                    put(
                        JSONObject()
                            .put("index", i)
                            .put("id", "call_${id.substringAfter('-')}_$i")
                            .put("type", "function")
                            .put("function", JSONObject().put("name", c.name).put("arguments", jsonOf(c.arguments).toString())),
                    )
                }
            }
        val completion = if (chunks > 0) chunks else 0
        val usageJson =
            JSONObject()
                .put("prompt_tokens", (total - completion).coerceAtLeast(0))
                .put("completion_tokens", completion)
                .put("total_tokens", total)
        // A tool call Gemma garbled: LiteRT-LM fails the turn, and OpenCode would send it all again, minutes of
        // retries. Its words go back as the answer instead, so the agent sees them and can try another way.
        var recovered = false
        failure?.message?.let { m -> Regex("full response: (.*?)(\nerror:|$)", RegexOption.DOT_MATCHES_ALL).find(m) }?.let {
            if (text.isEmpty() && calls.isEmpty()) {
                text.append("(My tool call was malformed, so it didn't run.) ").append(it.groupValues[1].trim())
                failure = null
                recovered = true
            }
        }
        val err = failure
        if (sink != null) {
            if (err != null && text.isEmpty() && calls.isEmpty()) {
                sink.error(err.message ?: err.toString())
            } else {
                if (recovered) sink.text(text.toString())
                if (toolCalls.length() > 0) sink.delta(JSONObject().put("tool_calls", toolCalls))
                sink.finish(finish, if (usage) usageJson else null)
            }
            return
        }
        if (err != null && text.isEmpty() && calls.isEmpty()) return respond(out, 500, problem(err.message ?: err.toString()))
        val message = JSONObject().put("role", "assistant").put("content", if (text.isEmpty() && calls.isNotEmpty()) JSONObject.NULL else text.toString())
        if (toolCalls.length() > 0) message.put("tool_calls", toolCalls)
        val choice = JSONObject().put("index", 0).put("message", message).put("finish_reason", finish)
        respond(
            out,
            200,
            JSONObject()
                .put("id", id)
                .put("object", "chat.completion")
                .put("created", created)
                .put("model", Gemma.MODEL_ID)
                .put("choices", JSONArray().put(choice))
                .put("usage", usageJson),
        )
    }

    /** One message of the conversation, as LiteRT-LM takes it and as a key compares it. */
    class Turn(
        val message: Message,
        val key: String,
    )

    /** A chat completions request, in LiteRT-LM's terms. */
    class Chat(
        val system: String,
        val tools: List<OpenApiTool>,
        private val toolsKey: String,
        val context: List<Turn>,
        val prompt: Turn,
        val sampler: SamplerConfig,
        val maxTokens: Int?,
    ) {
        /** What the kept conversation must have read for this request to go on from it. */
        val contextKey get() = keyOf(context.map { it.key })

        /** What the conversation has read after this turn: the context, the prompt and the reply. */
        fun keyAfter(
            reply: String,
            calls: List<ToolCall>,
        ) = keyOf(context.map { it.key } + prompt.key + assistantKey(reply, calls.map { it.name to jsonOf(it.arguments) }))

        private fun keyOf(turns: List<String>) = sha256(system + "\u0001" + toolsKey + "\u0001" + turns.joinToString("\u0002"))

        companion object {
            fun of(req: JSONObject): Chat {
                val messages = req.optJSONArray("messages") ?: throw IllegalArgumentException("no messages")
                val names = HashMap<String, String>()
                val system = StringBuilder()
                val turns = ArrayList<Turn>()
                var leading = true
                // Tool results one after another are one tool message to the model.
                val results = ArrayList<Pair<String, String>>()

                fun flush() {
                    if (results.isEmpty()) return
                    val r = results.toList()
                    results.clear()
                    turns += Turn(Message.tool(Contents.of(r.map { (n, c) -> Content.ToolResponse(n, c) })), "tool:" + r.joinToString("\u0003") { (n, c) -> "$n=$c" })
                }
                for (i in 0 until messages.length()) {
                    val m = messages.optJSONObject(i) ?: continue
                    val role = m.optString("role")
                    if (role != "tool") flush()
                    when (role) {
                        "system", "developer" ->
                            if (leading) {
                                if (system.isNotEmpty()) system.append("\n\n")
                                system.append(textOf(m.opt("content")))
                            } else {
                                val t = textOf(m.opt("content"))
                                turns += Turn(Message.user(t), "user:$t")
                            }
                        "user" -> {
                            leading = false
                            val t = textOf(m.opt("content"))
                            turns += Turn(Message.user(t), "user:$t")
                        }
                        "assistant" -> {
                            leading = false
                            val t = textOf(m.opt("content"))
                            val calls = ArrayList<Pair<String, JSONObject>>()
                            m.optJSONArray("tool_calls")?.let { a ->
                                for (j in 0 until a.length()) {
                                    val c = a.optJSONObject(j) ?: continue
                                    val f = c.optJSONObject("function") ?: continue
                                    val name = f.optString("name")
                                    c.optString("id").takeIf { it.isNotEmpty() }?.let { names[it] = name }
                                    val args = f.opt("arguments").let { x -> if (x is JSONObject) x else runCatching { JSONObject(x.toString()) }.getOrElse { JSONObject() } }
                                    calls += name to args
                                }
                            }
                            val message = Message.model(if (t.isEmpty()) Contents.of(emptyList<Content>()) else Contents.of(t), calls.map { (n, a) -> ToolCall(n, argsOf(a)) })
                            turns += Turn(message, assistantKey(t, calls))
                        }
                        "tool" -> {
                            leading = false
                            results += (names[m.optString("tool_call_id")] ?: m.optString("name").ifEmpty { "tool" }) to textOf(m.opt("content"))
                        }
                    }
                }
                flush()
                val prompt = turns.removeLastOrNull() ?: throw IllegalArgumentException("no user message")
                if (prompt.key.startsWith("assistant:")) throw IllegalArgumentException("the last message is the assistant's: nothing to answer")

                val tools = ArrayList<OpenApiTool>()
                val toolsKey = StringBuilder()
                req.optJSONArray("tools")?.let { a ->
                    for (i in 0 until a.length()) {
                        val f = a.optJSONObject(i)?.optJSONObject("function") ?: continue
                        val spec = JSONObject().put("name", f.optString("name")).put("description", f.optString("description"))
                        f.optJSONObject("parameters")?.let { spec.put("parameters", it) }
                        val s = spec.toString()
                        toolsKey.append(s).append('\u0003')
                        tools +=
                            object : OpenApiTool {
                                override fun getToolDescriptionJsonString() = s

                                // Never called: tool calls go back to the client (automaticToolCalling = false).
                                override fun execute(paramsJsonString: String) = "{}"
                            }
                    }
                }
                val sampler =
                    SamplerConfig(
                        topK = req.optInt("top_k", 64).coerceAtLeast(1),
                        topP = req.optDouble("top_p", 0.95).coerceIn(0.0, 1.0),
                        temperature = req.optDouble("temperature", 1.0).coerceAtLeast(0.0),
                    )
                val max = req.optInt("max_completion_tokens", req.optInt("max_tokens", 0)).takeIf { it > 0 }
                return Chat(system.toString(), tools, toolsKey.toString(), turns, prompt, sampler, max)
            }

            /** An assistant message as a key: its text and calls, whatever ids the client gave them. */
            fun assistantKey(
                text: String,
                calls: List<Pair<String, JSONObject>>,
            ) = "assistant:" + text.trim() + calls.joinToString("") { (n, a) -> "\u0003$n(${canonical(a)})" }

            private fun textOf(content: Any?): String =
                when (content) {
                    null, JSONObject.NULL -> ""
                    is String -> content
                    is JSONArray ->
                        (0 until content.length()).mapNotNull { i ->
                            val p = content.optJSONObject(i) ?: return@mapNotNull content.optString(i)
                            when (p.optString("type")) {
                                "text", "input_text", "output_text" -> p.optString("text")
                                "image_url", "input_image", "image" -> "[an image, which this model can't see here]"
                                else -> p.optString("text").takeIf { it.isNotEmpty() }
                            }
                        }.joinToString("\n")
                    else -> content.toString()
                }
        }
    }

    // ---------- streaming

    /** A streamed reply in OpenAI's chunks; false from a write when the client has gone. */
    private class Sse(
        private val out: OutputStream,
        private val id: String,
        private val created: Long,
    ) {
        private var first = true

        fun open() = write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")

        fun text(t: String) = delta(JSONObject().put("content", t))

        fun delta(d: JSONObject): Boolean {
            if (first) {
                first = false
                d.put("role", "assistant")
            }
            return data(chunk(JSONArray().put(JSONObject().put("index", 0).put("delta", d).put("finish_reason", JSONObject.NULL))))
        }

        fun finish(
            reason: String,
            usage: JSONObject?,
        ) {
            data(chunk(JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject()).put("finish_reason", reason))))
            if (usage != null) data(chunk(JSONArray()).put("usage", usage))
            write("data: [DONE]\n\n")
        }

        fun error(message: String) {
            data(JSONObject().put("error", JSONObject().put("message", message).put("type", "server_error")))
            write("data: [DONE]\n\n")
        }

        private fun chunk(choices: JSONArray) =
            JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", Gemma.MODEL_ID).put("choices", choices)

        private fun data(o: JSONObject) = write("data: $o\n\n")

        @Synchronized
        fun write(s: String) =
            runCatching {
                out.write(s.toByteArray())
                out.flush()
            }.isSuccess
    }

    // ---------- HTTP

    private fun readHead(input: InputStream): Pair<String, Map<String, String>>? {
        val buf = ByteArrayOutputStream()
        var last = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a) break
            if (buf.size() > 64 * 1024) return null
        }
        val lines = buf.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        return (lines.firstOrNull() ?: return null) to headers
    }

    private fun readBody(
        input: InputStream,
        headers: Map<String, String>,
    ): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            val body = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) break
                body.write(readExactly(input, size))
                readLine(input)
            }
            return body.toByteArray()
        }
        return readExactly(input, headers["content-length"]?.toIntOrNull() ?: 0)
    }

    private fun readLine(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExactly(
        input: InputStream,
        n: Int,
    ): ByteArray {
        val a = ByteArray(n)
        var at = 0
        while (at < n) {
            val r = input.read(a, at, n - at)
            if (r < 0) break
            at += r
        }
        return if (at == n) a else a.copyOf(at)
    }

    private fun respond(
        out: OutputStream,
        status: Int,
        body: JSONObject?,
    ) {
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        val reason =
            when (status) {
                200 -> "OK"
                204 -> "No Content"
                400 -> "Bad Request"
                404 -> "Not Found"
                503 -> "Service Unavailable"
                else -> "Error"
            }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    private fun problem(message: String) = JSONObject().put("error", JSONObject().put("message", message).put("type", "invalid_request_error"))

    companion object {
        private const val TAG = "gemma-http"

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** JSON with its keys sorted, so the same arguments are the same key. */
        fun canonical(v: Any?): String =
            when (v) {
                null, JSONObject.NULL -> "null"
                is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonical(v.opt(k)) }
                is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
                is Map<*, *> -> canonical(jsonOf(v))
                is List<*> -> canonical(JSONArray(v.map { if (it is Map<*, *>) jsonOf(it) else it }))
                is String -> JSONObject.quote(v)
                is Number -> JSONObject.numberToString(v)
                else -> v.toString()
            }

        /** LiteRT-LM's arguments (maps, lists, numbers) as JSON. */
        fun jsonOf(m: Map<*, *>): JSONObject =
            JSONObject().apply {
                for ((k, v) in m) put(k.toString(), wrap(v))
            }

        private fun wrap(v: Any?): Any =
            when (v) {
                null -> JSONObject.NULL
                is Map<*, *> -> jsonOf(v)
                is List<*> -> JSONArray().apply { v.forEach { put(wrap(it)) } }
                is Number -> v.toString().toLongOrNull() ?: v.toDouble()
                else -> v
            }

        /** JSON arguments as the maps and lists LiteRT-LM takes. */
        fun argsOf(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { unwrap(o.opt(it)) }

        private fun unwrap(v: Any?): Any? =
            when (v) {
                null, JSONObject.NULL -> null
                is JSONObject -> argsOf(v)
                is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
                else -> v
            }
    }
}
