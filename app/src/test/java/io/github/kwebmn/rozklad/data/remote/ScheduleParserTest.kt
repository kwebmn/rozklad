package io.github.kwebmn.rozklad.data.remote

import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleParserTest {
    private fun day(d: Int, m: Int) = LocalDate.of(2026, m, d)

    @Test
    fun `parses group week`() {
        val r = ScheduleParser.parse(fixture("group_week.html"))
        assertEquals(SubscriptionType.GROUP, r.type)
        assertEquals("ІПЗс-24-1", r.name)
        assertEquals("-607", r.siteId)
        assertEquals(14, r.lessons.size)
        assertEquals(5, r.lessons.map { it.date }.distinct().size)
        assertEquals(
            Lesson(
                date = day(13, 10), pairNo = 1, slot = 0,
                start = LocalTime.of(8, 30), end = LocalTime.of(9, 50),
                subject = "Prompt-інжиніринг", kind = "Л",
                teacher = "Демчина Микола Миколайович", room = "к.1ауд.410",
                stream = "потік ІПЗс-24-1, ІПЗс-24-2",
            ),
            r.lessons[0],
        )
    }

    @Test
    fun `parses teacher week with groups instead of teacher`() {
        val r = ScheduleParser.parse(fixture("teacher_week.html"))
        assertEquals(SubscriptionType.TEACHER, r.type)
        assertEquals("Демчина Микола Миколайович", r.name)
        assertEquals("66", r.siteId)
        assertEquals(9, r.lessons.size)
        val second = r.lessons[1]
        assertEquals(day(13, 10), second.date)
        assertEquals(3, second.pairNo)
        assertEquals("ІПЗс-24-1", second.stream)
        assertNull(second.teacher)
        assertEquals("к.1ауд.330/5к", second.room)
    }

    @Test
    fun `cancelled lesson and its replacement share one pair`() {
        val r = ScheduleParser.parse(fixture("group_markers.html"))
        assertEquals(106, r.lessons.size)
        assertEquals(5, r.lessons.count { it.cancelled })
        val pair = r.lessons.filter { it.date == day(1, 9) && it.pairNo == 3 }
        assertEquals(2, pair.size)
        assertEquals("Радіовиробництво і подкасти", pair[0].subject)
        assertEquals(0, pair[0].slot)
        assertTrue(pair[0].cancelled)
        assertEquals("Увага! Заняття відмінено!", pair[0].note)
        assertEquals("Брендинг", pair[1].subject)
        assertEquals(1, pair[1].slot)
        assertFalse(pair[1].cancelled)
        assertNull(pair[1].note)
    }

    @Test
    fun `remote lesson keeps meeting links`() {
        val r = ScheduleParser.parse(fixture("group_markers.html"))
        val l = r.lessons.single { it.date == day(8, 10) && it.pairNo == 2 }
        assertTrue(l.remote)
        assertNull(l.room)
        assertEquals(
            listOf("https://meet.google.com/irq-qsnc-jwu", "https://meet.google.com/pdy-xnvx-xqj"),
            l.links,
        )
    }

    @Test
    fun `lesson without room has null room`() {
        val r = ScheduleParser.parse(fixture("group_markers.html"))
        val l = r.lessons.single { it.date == day(16, 9) && it.pairNo == 6 }
        assertEquals("Практика PR-діяльності", l.subject)
        assertEquals("Лашків Софія Олегівна", l.teacher)
        assertNull(l.room)
    }

    @Test
    fun `late pairs have their own times`() {
        val r = ScheduleParser.parse(fixture("group_markers.html"))
        val fifth = r.lessons.single { it.date == day(2, 10) && it.pairNo == 5 }
        assertEquals(LocalTime.of(15, 10), fifth.start)
        assertEquals(LocalTime.of(16, 30), fifth.end)
        assertEquals("Сем", fifth.kind)
    }

    @Test
    fun `nothing found page is a valid empty period`() {
        val r = ScheduleParser.parse(fixture("empty_period.html"))
        assertTrue(r.lessons.isEmpty())
        assertNull(r.name)
        assertNull(r.siteId)
        assertNull(r.type)
    }

    @Test
    fun `form page without schedule is a format error`() {
        assertThrows(SiteFormatException::class.java) { ScheduleParser.parse(fixture("form_only.html")) }
    }

    @Test
    fun `garbage is a format error`() {
        assertThrows(SiteFormatException::class.java) {
            ScheduleParser.parse("<html><body>Internal Server Error</body></html>")
        }
    }

    @Test
    fun `malformed time is a format error`() {
        val html = """
            <html><body>
            <h4>Розклад групи <a href="/time-table/group?n=700&group=-1&type=0">X</a></h4>
            <div>
              <h4>13.10.2026 <small>Вівторок</small></h4>
              <table><tr><td>1</td><td>08:30<br>25:61</td><td><span class="p_name">Предмет</span></td></tr></table>
            </div>
            </body></html>
        """.trimIndent()
        assertThrows(SiteFormatException::class.java) { ScheduleParser.parse(html) }
    }
}
