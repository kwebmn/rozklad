package io.github.kwebmn.rozklad.ui.update

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun UpdateDialog(
    state: UpdateState,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    when (state) {
        UpdateState.Idle, UpdateState.Checking -> Unit
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            text = { Text("У вас остання версія") },
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Доступна версія ${state.release.version}") },
            text = {
                Text(
                    state.release.notes.ifBlank { "Нова версія застосунку." },
                    Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = onUpdate) { Text("Оновити") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Пізніше") } },
        )
        UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Завантаження оновлення…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelDownload) { Text("Скасувати") } },
        )
        UpdateState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Оновлення завантажено") },
            confirmButton = { TextButton(onClick = onInstall) { Text("Встановити") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Пізніше") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            text = { Text(state.message) },
        )
    }
}
