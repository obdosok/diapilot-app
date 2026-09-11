package com.example.diapilot.ui

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
import androidx.compose.ui.unit.dp
import com.example.diapilot.data.AskClaude
import com.example.diapilot.data.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Ask your data" chat. Questions + a data summary go to the Anthropic API
 *  over the user's own key; answers are explanations, never dosing advice. */
@Composable
fun AskScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(AskClaude.apiKey(context)) }
    AskClaude.History.load(context)
    val chat = AskClaude.History.messages
    val busy by AskClaude.History.busy
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    if (apiKey == null) {
        var keyField by remember { mutableStateOf("") }
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Спросить свои данные", style = MaterialTheme.typography.titleLarge)
            Text(
                "Задавайте вопросы своим данным обычным языком: «почему ночью был высокий сахар?», " +
                    "«как на меня действует пиво?». Ответы строит Claude по сводке ваших данных.\n\n" +
                    "Нужен ваш API-ключ Anthropic (console.anthropic.com → API Keys). " +
                    "Ключ хранится только на этом устройстве. Сводка данных отправляется в API " +
                    "только в момент вопроса.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = keyField,
                onValueChange = { keyField = it },
                label = { Text("sk-ant-…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                if (keyField.trim().startsWith("sk-ant-")) {
                    AskClaude.saveApiKey(context, keyField)
                    apiKey = keyField.trim()
                }
            }) { Text("Сохранить") }
        }
        return
    }

    fun send(question: String = input) {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        input = ""
        AskClaude.sendAsync(context, Stores.get(context), q)
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
            Text("Спросить", style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(
                    enabled = !busy,
                    onClick = { AskClaude.sendDigest(context, Stores.get(context)) },
                ) { Text("📋 Дайджест") }
                if (chat.isNotEmpty()) {
                    TextButton(onClick = { AskClaude.History.clear(context) }) { Text("Очистить") }
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
                    "Ответы — объяснения ваших данных, не медицинские рекомендации. " +
                        "Дозы и настройки — только с врачом.",
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
                item { Text("Думаю…", style = MaterialTheme.typography.bodySmall) }
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
                "Объясни мой день",
                "Почему был скачок?",
                "Как на меня влияет прогулка?",
                "Что стабильно даёт поздний хвост?",
                "Когда мне лучше колоть — раньше?",
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
                placeholder = { Text("Почему ночью был высокий сахар?") },
            )
            val speak = rememberSpeechInput { spoken ->
                input = if (input.isBlank()) spoken else "$input $spoken"
            }
            IconButton(onClick = speak) { Text("🎤") }
            IconButton(onClick = ::send, enabled = !busy) { Text("➤") }
        }
    }
}
