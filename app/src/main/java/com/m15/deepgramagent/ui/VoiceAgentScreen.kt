package com.m15.deepgramagent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAgentScreen(
    ui: AgentUiState,
    isSpeakerOn: Boolean,
    onSpeakerToggle: () -> Unit
) {
    // Last committed messages by role (if any)
    val lastUserMsg = ui.messages.lastOrNull { it.first == "user" }?.second
    val lastAssistantMsg = ui.messages.lastOrNull { it.first == "assistant" }?.second
    // Show live bubbles only if they’re different from the last committed
    val showLiveUser = !ui.livePartial.isNullOrEmpty() && ui.livePartial != lastUserMsg
    val showLiveAssistant = !ui.assistantLive.isNullOrEmpty() && ui.assistantLive != lastAssistantMsg
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Deepgram Agent")
                        Text(
                            text = "Flux: flux-general-en | LLM: gpt-4o-mini-realtime-preview",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onSpeakerToggle,
                containerColor =
                    if (isSpeakerOn) Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Hearing,
                    contentDescription = if (isSpeakerOn) "Speakerphone on" else "Speakerphone off"
                )
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = true
            ) {
                // Live assistant stream (green)
                if (showLiveAssistant) {
                    item {
                        ChatBubble(
                            role = "assistant",
                            text = ui.assistantLive!!,
                            color = Color(0xFF4CAF50) // green
                        )
                    }
                }
                // Live user transcription (blue)
                if (showLiveUser) {
                    item {
                        ChatBubble(
                            role = "user",
                            text = ui.livePartial!!,
                            color = Color(0xFF2196F3) // blue
                        )
                    }
                }
                // Message history (latest at the bottom)
                items(ui.messages.asReversed()) { (role, msg) ->
                    val color = if (role == "assistant") Color(0xFF4CAF50) else Color(0xFF2196F3)
                    ChatBubble(role = role, text = msg, color = color)
                }
            }
            if (ui.isThinking) {
                Text(
                    "thinking...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    textAlign = TextAlign.Center
                )
            }
            ui.error?.let {
                Text(
                    text = "Error: $it",
                    color = Color.Red,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
@Composable
fun ChatBubble(role: String, text: String, color: Color) {
    val alignment = if (role == "assistant") Alignment.CenterStart else Alignment.CenterEnd
    val bubbleColor = color.copy(alpha = 0.15f)
    val textColor = color
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(bubbleColor, shape = MaterialTheme.shapes.medium)
                .padding(12.dp)
                .widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
