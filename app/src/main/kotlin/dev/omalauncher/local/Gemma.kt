package dev.omalauncher.local

import dev.omalauncher.linux.shellQuote
import org.json.JSONObject
import java.io.File

/**
 * Gemma 4 E2B, run by the launcher itself on this phone with LiteRT-LM (GPU, else CPU), for Voice and for a coding
 * agent in Linux. The model file lives in the guest home where the `litert-lm` CLI keeps its models, so one copy
 * serves both.
 */
object Gemma {
    /** Its agent's bin: the drawer entry, the voice link, the settings. */
    const val BIN = "gemma"

    /** The model's id, in the OpenAI API and in `litert-lm list`. */
    const val MODEL_ID = "gemma-4-e2b"

    const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    /** The whole file, to tell a finished download from one cut short. */
    const val SIZE = 2_588_147_712L

    /** As said to the user. */
    const val SIZE_TEXT = "2.6 GB"

    /** Where the launcher serves it to Linux: litert-lm serve's port, so either can answer. */
    const val PORT = 9379

    /** The context the engine is opened with: room for a coding agent's long instructions. */
    const val MAX_TOKENS = 32768

    /** The coding agent's command in Linux: OpenCode on this model. */
    const val COMMAND = "gemma-code"

    /** In the guest home. */
    const val MODEL_PATH = ".litert-lm/models/$MODEL_ID/model.litertlm"

    fun modelFile(home: File) = File(home, MODEL_PATH)

    /** Whether the whole model is there. */
    fun present(home: File) = modelFile(home).length() == SIZE

    /** OpenCode's extra config: a provider for the launcher's server, and this model picked. */
    val openCodeConfig: String
        get() {
            val model = JSONObject().put("name", "Gemma 4 E2B (on this phone)").put("limit", JSONObject().put("context", MAX_TOKENS).put("output", 4096))
            val provider =
                JSONObject()
                    .put("npm", "@ai-sdk/openai-compatible")
                    .put("name", "On this phone")
                    .put("options", JSONObject().put("baseURL", "http://127.0.0.1:$PORT/v1").put("apiKey", "local"))
                    .put("models", JSONObject().put(MODEL_ID, model))
            // OpenCode's own agent sends ~19,500 characters of instructions and a dozen tools: ~30 s of reading on
            // the phone's GPU, and long enough that Gemma garbles its tool calls. Its own agent with short
            // instructions and five tools answers in seconds; no separate request for the session's title.
            val tools = JSONObject()
            for (t in OFF_TOOLS) tools.put(t, false)
            for (t in ON_TOOLS) tools.put(t, true)
            val agent = JSONObject().put("mode", "primary").put("description", "Gemma on this phone").put("prompt", AGENT_PROMPT).put("tools", tools)
            val agents = JSONObject().put("gemma", agent).put("title", JSONObject().put("disable", true))
            return JSONObject()
                .put("provider", JSONObject().put("litert", provider))
                .put("model", "litert/$MODEL_ID")
                .put("default_agent", "gemma")
                .put("agent", agents)
                .toString()
        }

    /** gemma-code's instructions: short, as every word is read on the phone before each new session. */
    private const val AGENT_PROMPT =
        "You are a coding assistant running on the user's phone, in a Linux terminal. Use the tools to read, write and edit " +
            "files and to run shell commands. Call one tool at a time and wait for its result. Keep answers short and plain. " +
            "Before changing or deleting anything important, say what you will do."

    private val ON_TOOLS = listOf("read", "write", "edit", "shell", "glob")

    /** OpenCode's other tools, each a description Gemma would read for nothing. */
    private val OFF_TOOLS =
        listOf(
            "question", "skill", "subagent", "webfetch", "websearch", "execute", "grep", "todowrite", "todoread", "task", "list",
            "patch", "bash", "lsp", "opencode_list_mcp_resources", "opencode_models", "opencode_read_mcp_resource",
            "opencode_session_move", "opencode_session_rename", "opencode_worktree_create", "opencode_worktree_list",
            "opencode_worktree_remove",
        )

    /** Downloads the model in the guest, resuming a cut download, saying how far it got every few seconds. */
    val downloadScript: String
        get() =
            """
            d="${'$'}HOME/.litert-lm/models/$MODEL_ID"; f="${'$'}d/model.litertlm"
            if [ "${'$'}(stat -c %s "${'$'}f" 2>/dev/null)" = "$SIZE" ]; then echo "Gemma 4 E2B is already on this phone."; exit 0; fi
            mkdir -p "${'$'}d" || exit 1
            echo "Downloading Gemma 4 E2B ($SIZE_TEXT)…"
            ( while sleep 5; do s=${'$'}(stat -c %s "${'$'}f.part" 2>/dev/null || echo 0); echo "${'$'}((s / 1048576)) of ${'$'}(($SIZE / 1048576)) MB"; done ) & p=${'$'}!
            ok=1
            for i in 1 2 3 4 5 6 7 8 9 10; do
              curl -fsSL -C - --retry 5 --speed-limit 10000 --speed-time 60 -o "${'$'}f.part" ${shellQuote(URL)} && { ok=0; break; }
              echo "The download stopped; going on where it was…"; sleep 3
            done
            kill ${'$'}p 2>/dev/null
            [ ${'$'}ok = 0 ] && [ "${'$'}(stat -c %s "${'$'}f.part" 2>/dev/null)" = "$SIZE" ] || { echo "The download didn't finish; try again to go on where it stopped."; exit 1; }
            mv "${'$'}f.part" "${'$'}f" && echo "Gemma 4 E2B is on this phone."
            """.trimIndent()

    /**
     * `gemma-code`: OpenCode on this model, in its own server (the background one would have the user's config).
     * Without the model it asks to download it first. `gemma-code --download` only downloads.
     */
    val wrapperScript: String
        // Each part trimmed on its own: trimIndent after putting the download in would trim nothing.
        get() =
            "#!/bin/sh\n# Written by the launcher: OpenCode on Gemma 4 E2B, which the launcher runs on this phone (127.0.0.1:$PORT).\n" +
                "download() {\n$downloadScript\n}\n" +
                """
            if [ "${'$'}1" = "--download" ]; then (download); exit ${'$'}?; fi
            if [ "${'$'}(stat -c %s "${'$'}HOME/$MODEL_PATH" 2>/dev/null)" != "$SIZE" ]; then
              printf 'Gemma 4 E2B is not on this phone yet. Download it now (%s)? [Y/n] ' "$SIZE_TEXT"
              read -r a || exit 1
              case "${'$'}a" in [nN]*) exit 1 ;; esac
              (download) || exit 1
            fi
            if ! command -v opencode >/dev/null 2>&1; then
              echo "OpenCode isn't installed: install Gemma 4 (local) or OpenCode in the launcher's Agents."; exit 1
            fi
            export OPENCODE_CONFIG_CONTENT=${shellQuote(openCodeConfig)}
            exec opencode --standalone "${'$'}@"
            """.trimIndent() + "\n"
}
