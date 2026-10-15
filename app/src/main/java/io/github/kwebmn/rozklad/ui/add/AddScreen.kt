package io.github.kwebmn.rozklad.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(vm: AddViewModel, onAdded: (Subscription) -> Unit, onBack: (() -> Unit)?) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) {
        state.added?.let { sub ->
            vm.consumeAdded()
            onAdded(sub)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Додати розклад") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SubscriptionType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.type == type,
                        onClick = { vm.setType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, SubscriptionType.entries.size),
                    ) { Text(if (type == SubscriptionType.GROUP) "Група" else "Викладач") }
                }
            }
            FacultyPicker(state.faculty, vm::setFaculty)
            if (state.type == SubscriptionType.GROUP) CourseChips(state.course, vm::setCourse)
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                label = { Text(if (state.type == SubscriptionType.GROUP) "Назва групи" else "ПІБ викладача") },
                supportingText = { Text("Мінімум ${state.type.minChars} символи") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.searched && state.suggestions.isEmpty()) {
                Text("Нічого не знайдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.suggestions) { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        modifier = Modifier.clickable(enabled = !state.loading) { vm.select(name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FacultyPicker(selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(FACULTIES.first { it.first == selected }.second, Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FACULTIES.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

@Composable
private fun CourseChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0..6).forEach { course ->
            FilterChip(
                selected = selected == course,
                onClick = { onSelect(course) },
                label = { Text(if (course == 0) "Усі курси" else "$course курс") },
            )
        }
    }
}
