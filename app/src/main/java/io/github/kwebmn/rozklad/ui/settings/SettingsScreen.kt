package io.github.kwebmn.rozklad.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kwebmn.rozklad.data.ThemeMode
import io.github.kwebmn.rozklad.domain.SubscriptionType

private val REMINDER_OPTIONS = listOf(5, 10, 15, 30)
private val THEMES = listOf(ThemeMode.SYSTEM to "Системна", ThemeMode.LIGHT to "Світла", ThemeMode.DARK to "Темна")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    versionName: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionTitle("Мої розклади") }
            items(state.subscriptions, key = { it.id }) { sub ->
                ListItem(
                    headlineContent = { Text(sub.name) },
                    supportingContent = {
                        Text(
                            (if (sub.type == SubscriptionType.GROUP) "Група" else "Викладач") +
                                if (sub.isPrimary) " · основний (віджет і сповіщення)" else "",
                        )
                    },
                    leadingContent = {
                        IconButton(onClick = { vm.setPrimary(sub.id) }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "Зробити основним",
                                tint = if (sub.isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { vm.delete(sub.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Видалити")
                        }
                    },
                )
            }
            item {
                TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Додати розклад")
                }
            }

            item { SectionTitle("Сповіщення") }
            item { SwitchRow("Нагадування перед парою", s.remindersEnabled, vm::setRemindersEnabled) }
            if (s.remindersEnabled) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        REMINDER_OPTIONS.forEach { minutes ->
                            FilterChip(
                                selected = s.reminderMinutes == minutes,
                                onClick = { vm.setReminderMinutes(minutes) },
                                label = { Text("За $minutes хв") },
                            )
                        }
                    }
                }
            }
            item { SwitchRow("Сповіщати про зміни в розкладі", s.changeNotifications, vm::setChangeNotifications) }

            item { SectionTitle("Тема") }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    THEMES.forEach { (mode, title) ->
                        FilterChip(selected = s.theme == mode, onClick = { vm.setTheme(mode) }, label = { Text(title) })
                    }
                }
            }

            item { SectionTitle("Про застосунок") }
            item {
                ListItem(
                    headlineContent = { Text("Версія $versionName") },
                    trailingContent = { TextButton(onClick = onCheckUpdates) { Text("Перевірити оновлення") } },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
