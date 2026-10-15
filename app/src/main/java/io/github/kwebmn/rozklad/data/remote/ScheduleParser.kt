package io.github.kwebmn.rozklad.data.remote

import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.SubscriptionType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SiteFormatException(message: String) : Exception(message)

data class ParsedSchedule(
    val type: SubscriptionType?,
    val name: String?,
    val siteId: String?,
    val lessons: List<Lesson>,
)

object ScheduleParser {
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val DAY_HEADER = Regex("""^(\d{2}\.\d{2}\.\d{4})""")
    private val TIME = Regex("""\d{1,2}:\d{2}""")
    private val SITE_ID = Regex("""[?&](?:group|teacher)=(-?\d+)""")
    private const val NOTHING_FOUND = "записів не знайдено"
    private const val GROUP_TITLE = "Розклад групи"
    private const val TEACHER_TITLE = "Розклад викладача"

    fun parse(html: String): ParsedSchedule {
        val doc = Jsoup.parse(html)
        if (doc.select("div.alert").any { it.text().contains(NOTHING_FOUND) }) {
            return ParsedSchedule(null, null, null, emptyList())
        }
        val title = doc.select("h4").firstOrNull {
            val t = it.text()
            t.startsWith(GROUP_TITLE) || t.startsWith(TEACHER_TITLE)
        } ?: throw SiteFormatException("Schedule title not found")
        val type = if (title.text().startsWith(GROUP_TITLE)) SubscriptionType.GROUP else SubscriptionType.TEACHER
        val link = title.selectFirst("a")

        val lessons = doc.select("h4").flatMap { header ->
            val dateText = DAY_HEADER.find(header.text().trim())?.groupValues?.get(1)
                ?: return@flatMap emptyList()
            val date = LocalDate.parse(dateText, DATE)
            val table = header.parent()?.selectFirst("table") ?: return@flatMap emptyList()
            table.select("tr").flatMap { row -> parseRow(row, date) }
        }
        return ParsedSchedule(
            type = type,
            name = link?.text()?.trim(),
            siteId = link?.attr("href")?.let { SITE_ID.find(it)?.groupValues?.get(1) },
            lessons = lessons,
        )
    }

    private fun parseRow(row: Element, date: LocalDate): List<Lesson> {
        val cells = row.select("td")
        if (cells.size < 3) return emptyList()
        val pairNo = cells[0].text().trim().toIntOrNull() ?: return emptyList()
        val times = TIME.findAll(cells[1].text()).map { LocalTime.parse(it.value.padStart(5, '0')) }.toList()
        if (times.size < 2) throw SiteFormatException("Bad time cell on $date pair $pairNo")
        return parseCell(cells[2]).mapIndexed { slot, draft ->
            draft.toLesson(date, pairNo, slot, times[0], times[1])
        }
    }

    private class Draft(val subject: String, val note: String?, val remote: Boolean) {
        var kind = ""
        var teacher: String? = null
        var room: String? = null
        var stream: String? = null
        val links = mutableListOf<String>()

        fun toLesson(date: LocalDate, pairNo: Int, slot: Int, start: LocalTime, end: LocalTime) = Lesson(
            date = date, pairNo = pairNo, slot = slot, start = start, end = end,
            subject = subject, kind = kind, teacher = teacher, room = room, stream = stream,
            note = note, cancelled = note?.contains("відмінено") == true, remote = remote,
            links = links.distinct(),
        )
    }

    /** Пометки (zamena, remote_work*) стоят перед p_name и относятся к следующему занятию ячейки. */
    private fun parseCell(cell: Element): List<Draft> {
        val drafts = mutableListOf<Draft>()
        var pendingNote: String? = null
        var pendingRemote = false
        for (el in cell.select("span, a")) {
            val text = el.text().trim().ifEmpty { null }
            val current = drafts.lastOrNull()
            when {
                el.tagName() == "a" ->
                    el.attr("href").trim().takeIf { it.startsWith("http") }?.let { current?.links?.add(it) }
                el.hasClass("zamena") -> pendingNote = text
                el.classNames().any { it.startsWith("remote_work") } -> pendingRemote = true
                el.hasClass("p_name") -> {
                    drafts += Draft(text.orEmpty(), pendingNote, pendingRemote)
                    pendingNote = null
                    pendingRemote = false
                }
                current == null -> Unit
                el.hasClass("p_type_name") -> current.kind = text.orEmpty()
                el.hasClass("t_name") -> current.teacher = text
                el.hasClass("room_name") -> current.room = text
                el.hasClass("gr_name") || el.hasClass("gr2_name") -> current.stream = text
            }
        }
        return drafts
    }
}
