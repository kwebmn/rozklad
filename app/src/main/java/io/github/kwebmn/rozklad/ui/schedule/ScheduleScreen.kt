package io.github.kwebmn.rozklad.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kwebmn.rozklad.domain.*
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel, clock: Clock, onAdd: () -> Unit, onSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val now by produceState(LocalDateTime.now(clock)) {
        while (true) {
            delay(30_000)
            value = LocalDateTime.now(clock)
        }
    }
    var details by remember { mutableStateOf<Lesson?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { SubscriptionPicker(state, vm::selectSubscription, onAdd) },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Оновити") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Налаштування") }
                },
            )
        },
        bottomBar = {
            Text(
                formatSyncStatus(state.current?.lastSyncedAt, clock.millis(), state.offline, clock.zone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            WeekStrip(state, now.toLocalDate(), vm::showDate, vm::shiftWeek)
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -SWIPE_PX) vm.shiftDay(1) else if (total > SWIPE_PX) vm.shiftDay(-1)
                            },
                        ) { _, dx -> total += dx }
                    },
            ) {
                DayList(state, now, onLesson = { details = it })
            }
        }
    }
    details?.let { LessonSheet(it, onDismiss = { details = null }) }
}

private const val SWIPE_PX = 120f

@Composable
private fun SubscriptionPicker(state: ScheduleUiState, onSelect: (Long) -> Unit, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(state.current?.name ?: "Розклад", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Обрати розклад")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.subscriptions.forEach { sub ->
                DropdownMenuItem(
                    text = { Text(sub.name + if (sub.type == SubscriptionType.TEACHER) " (викладач)" else "") },
                    onClick = { open = false; onSelect(sub.id) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Додати…") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { open = false; onAdd() },
            )
        }
    }
}

@Composable
private fun WeekStrip(state: ScheduleUiState, today: LocalDate, onDate: (LocalDate) -> Unit, onWeek: (Long) -> Unit) {
    val sunday = state.weekStart.plusDays(6)
    val days = (0L..5L).map { state.weekStart.plusDays(it) } +
        listOfNotNull(sunday.takeIf { d -> state.weekLessons.any { it.date == d } })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = { onWeek(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Попередній тиждень")
        }
        days.forEach { day ->
            val selected = day == state.selectedDate
            val hasLessons = state.weekLessons.any { it.date == day && !it.cancelled }
            val fg = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                day == today -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onDate(day) }
                    .padding(vertical = 6.dp),
            ) {
                Text(dayShort(day.dayOfWeek), style = MaterialTheme.typography.labelMedium, color = fg)
                Text("${day.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = fg)
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (hasLessons) fg else Color.Transparent),
                )
            }
        }
        IconButton(onClick = { onWeek(1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Наступний тиждень")
        }
    }
}

@Composable
private fun DayList(state: ScheduleUiState, now: LocalDateTime, onLesson: (Lesson) -> Unit) {
    val rows = buildDayRows(state.dayLessons)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "${dayFull(state.selectedDate.dayOfWeek)}, ${shortDate(state.selectedDate)}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (rows.isEmpty()) {
            item {
                Text(
                    "Пар немає",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(rows, key = { it.pairNo }) { row ->
            when (row) {
                is DayRow.Window -> Text(
                    "${row.pairNo} · вікно",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp),
                )
                is DayRow.Lessons -> PairCard(row, now, state.current?.type, onLesson)
            }
        }
    }
}

@Composable
private fun PairCard(row: DayRow.Lessons, now: LocalDateTime, type: SubscriptionType?, onLesson: (Lesson) -> Unit) {
    val date = row.lessons.first().date
    val isCurrent = !now.isBefore(date.atTime(row.start)) && now.isBefore(date.atTime(row.end))
    Card(
        colors = if (isCurrent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.width(56.dp)) {
                Text("${row.pairNo}", style = MaterialTheme.typography.titleLarge)
                Text(formatTime(row.start), style = MaterialTheme.typography.bodySmall)
                Text(formatTime(row.end), style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f)) {
                if (isCurrent) {
                    Text("● зараз", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                row.lessons.forEach { lesson ->
                    LessonBlock(lesson, type, Modifier.clickable { onLesson(lesson) })
                }
            }
        }
    }
}

@Composable
private fun LessonBlock(lesson: Lesson, type: SubscriptionType?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        lesson.note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "${lesson.subject} (${lesson.kind})",
            style = MaterialTheme.typography.titleSmall.copy(
                textDecoration = if (lesson.cancelled) TextDecoration.LineThrough else null,
            ),
        )
        val who = if (type == SubscriptionType.TEACHER) lesson.stream else lesson.teacher?.let(::shortTeacher)
        val where = lesson.room?.let(::shortRoom) ?: if (lesson.remote) "дистанційно" else null
        val line = listOfNotNull(who, where).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LessonSheet(lesson: Lesson, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(lesson.subject, style = MaterialTheme.typography.titleLarge)
            Text(
                "${dayFull(lesson.date.dayOfWeek)} ${shortDate(lesson.date)}, ${lesson.pairNo} пара, " +
                    "${formatTime(lesson.start)}–${formatTime(lesson.end)}",
            )
            listOf(
                "Тип" to lesson.kind,
                "Викладач" to lesson.teacher,
                "Аудиторія" to (lesson.room ?: if (lesson.remote) "дистанційно" else null),
                "Групи" to lesson.stream,
                "Примітка" to lesson.note,
            ).forEach { (label, value) ->
                if (!value.isNullOrBlank()) Text("$label: $value", style = MaterialTheme.typography.bodyLarge)
            }
            lesson.links.forEach { link ->
                TextButton(onClick = { uriHandler.openUri(link) }) { Text(link) }
            }
        }
    }
}
