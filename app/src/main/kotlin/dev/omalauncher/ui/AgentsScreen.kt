package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.asksFirst
import dev.omalauncher.local.Gemma
import kotlinx.coroutines.launch

/** Install and launch coding agents (Claude Code, OpenCode, Codex, Grok, ...). */
@Composable
fun AgentsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onOpen: (Agent) -> Unit,
) {
    val store = runtime.agents
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Agent?>(null) }
    asking?.let { agent ->
        DownloadDialog(agent, onDismiss = { asking = null }) {
            asking = null
            scope.launch { store.install(agent) }
        }
    }
    OverlayPanel(stringResource(R.string.agents_title), onDismiss, contentWidth = ReadingWidth) {
        BasicText(
            stringResource(R.string.agents_intro),
            Modifier.padding(vertical = 8.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AGENTS, key = { it.bin }) { agent ->
                AgentRow(
                    agent = agent,
                    installed = agent in store.installed,
                    state = store.states[agent.bin] ?: JobState.Idle,
                    onInstall = { if (agent.asksFirst()) asking = agent else scope.launch { store.install(agent) } },
                    onRemove = { scope.launch { store.remove(agent) } },
                    onOpen = { onOpen(agent) },
                )
            }
        }
    }
}

/** Asks before an install that downloads a model to the phone, saying how big it is. */
@Composable
internal fun DownloadDialog(
    agent: Agent,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) = ConfirmDialog(
    title = stringResource(R.string.gemma_download_title, agent.name),
    message = stringResource(R.string.gemma_download_message, Gemma.SIZE_TEXT),
    confirm = stringResource(R.string.gemma_download_confirm),
    onDismiss = onDismiss,
    confirmColor = Palette.accent,
    onConfirm = onConfirm,
)

@Composable
private fun AgentRow(
    agent: Agent,
    installed: Boolean,
    state: JobState,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier.card(10.dp),
    ) {
        ActionsRow(
            actions = {
                // Keys on install or remove wait out the job, for what it ends with.
                when {
                    state is JobState.Working -> BasicText("${stringResource(state.action)}…", style = Mono.copy(color = Palette.accent))
                    installed -> {
                        BracketAction(stringResource(R.string.agents_open), Palette.green, Modifier.takesPassedKeys(), onOpen)
                        BracketAction(stringResource(R.string.agents_remove), Palette.red, Modifier.passesKeys(), onRemove)
                    }
                    else -> BracketAction(stringResource(R.string.agents_install), Palette.accent, Modifier.takesPassedKeys().passesKeys(), onInstall)
                }
            },
        ) {
            Column {
                BasicText(agent.name, style = Mono.copy(fontSize = TextSize.Item))
                BasicText(stringResource(agent.description), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        JobLog(state)
    }
}
