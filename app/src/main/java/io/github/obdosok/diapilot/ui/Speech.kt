package io.github.obdosok.diapilot.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.obdosok.diapilot.R
import java.util.Locale

/**
 * System speech recognition as a text-input shortcut: tap the mic, speak,
 * the transcript lands in the field. No recording stored, no permission of
 * our own — the system dialog handles capture.
 */
@Composable
fun rememberSpeechInput(onResult: (String) -> Unit): () -> Unit {
    val prompt = stringResource(R.string.speech_prompt)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(onResult)
        }
    }
    return {
        try {
            launcher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                },
            )
        } catch (_: Exception) {
            // No recognizer on this device — the button simply does nothing.
        }
    }
}
