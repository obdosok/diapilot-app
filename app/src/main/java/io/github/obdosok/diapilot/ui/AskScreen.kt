package io.github.obdosok.diapilot.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.obdosok.diapilot.LocalAppGraph
import io.github.obdosok.diapilot.R
import io.github.obdosok.diapilot.data.AskClaude
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Ask your data" chat. Questions + a data summary go to the Anthropic API
 *  over the user's own key; answers are explanations, never dosing advice. */
@Composable
fun AskScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(AskClaude.apiKey(context)) }
    AskClaude.History.load(context)
    val chat = AskClaude.History.messages
    val busy by AskClaude.History.busy
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    if (apiKey == null) {
        var keyField by remember { mutableStateOf("") }
        var saveFailed by remember { mutableStateOf(false) }
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ask_screen_setup_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.ask_screen_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = keyField,
                onValueChange = { keyField = it },
                label = { Text(stringResource(R.string.ask_screen_key_placeholder)) },
                singleLine = true,
                // Masked, and a password keyboard: no suggestions, and the IME
                // does not learn the key into its dictionary.
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                if (keyField.trim().startsWith("sk-ant-")) {
                    saveFailed = !AskClaude.saveApiKey(context, keyField)
                    if (!saveFailed) apiKey = AskClaude.apiKey(context)
                }
            }) { Text(stringResource(R.string.ask_screen_save)) }
            if (saveFailed) {
                Text(
                    stringResource(R.string.ask_screen_key_not_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }

    fun send(question: String = input) {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        input = ""
        AskClaude.sendAsync(context, graph.store, q)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        // Fixed action bar — must not scroll away with the chat.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.ask_screen_chat_title), style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(
                    enabled = !busy,
                    onClick = { AskClaude.sendDigest(context, graph.store) },
                ) { Text(stringResource(R.string.ask_screen_digest_button)) }
                if (chat.isNotEmpty()) {
                    TextButton(onClick = { AskClaude.History.clear(context) }) { Text(stringResource(R.string.ask_screen_clear)) }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.ask_screen_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(chat.size) { i ->
                val m = chat[i]
                val fromUser = m.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (fromUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            m.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
            if (busy) {
                item { Text(stringResource(R.string.ask_screen_thinking), style = MaterialTheme.typography.bodySmall) }
            }
        }
        LaunchedEffect(chat.size, busy) {
            if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size)
        }
        // Quick prompts — one tap to ask the common "explain my data"
        // questions, so the feature is discoverable, not a blank box.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                stringResource(R.string.ask_screen_quick_explain_day),
                stringResource(R.string.ask_screen_quick_why_spike),
                stringResource(R.string.ask_screen_quick_walk_effect),
                stringResource(R.string.ask_screen_quick_late_tail),
                stringResource(R.string.ask_screen_quick_inject_earlier),
            ).forEach { p ->
                androidx.compose.material3.SuggestionChip(
                    onClick = { send(p) },
                    enabled = !busy,
                    label = { Text(p, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.ask_screen_input_placeholder)) },
            )
            val speak = rememberSpeechInput { spoken ->
                input = if (input.isBlank()) spoken else "$input $spoken"
            }
            IconButton(onClick = speak) { Text("🎤") }
            IconButton(onClick = ::send, enabled = !busy) { Text("➤") }
        }
    }
}
