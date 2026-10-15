package io.github.kwebmn.rozklad.data.remote

import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.fixtureBytes
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ScheduleApiTest {
    private val server = MockWebServer()
    private lateinit var api: ScheduleApi
    private val week = DateRange(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 19))

    @Before
    fun setUp() {
        server.start()
        api = ScheduleApi(OkHttpClient(), server.url("/cgi-bin/timetable.cgi").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(fixture: String) =
        server.enqueue(MockResponse().setBody(Buffer().write(fixtureBytes(fixture))))

    @Test
    fun `suggest sends utf-8 query and decodes windows-1251 json`() = runTest {
        respond("suggest_groups.json")
        val result = api.suggest(SubscriptionType.GROUP, "ІПЗ", faculty = 1001, course = 2)
        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("n=701&lev=142&faculty=1001&course=2&query=%D0%86%D0%9F%D0%97"))
        assertEquals(38, result.size)
        assertTrue(result.contains("ІПЗс-24-1"))
    }

    @Test
    fun `teacher suggest uses lev 141 without course`() = runTest {
        server.enqueue(MockResponse().setBody("""{"query":"x","suggestions":[]}"""))
        api.suggest(SubscriptionType.TEACHER, "Дем", faculty = 0, course = 3)
        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("lev=141&faculty=0&query="))
        assertTrue(path, !path.contains("course="))
    }

    @Test
    fun `fetch by site id uses permalink GET`() = runTest {
        respond("group_week.html")
        val r = api.fetch(SubscriptionType.GROUP, "-607", "ІПЗс-24-1", week)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!, req.path!!.endsWith("?n=700&group=-607&sdate=13.10.2026&edate=19.10.2026"))
        assertEquals(14, r.lessons.size)
    }

    @Test
    fun `fetch teacher by site id`() = runTest {
        respond("teacher_week.html")
        api.fetch(SubscriptionType.TEACHER, "66", "Демчина Микола Миколайович", week)
        assertTrue(server.takeRequest().path!!.contains("&teacher=66&"))
    }

    @Test
    fun `fetch by name posts windows-1251 form`() = runTest {
        respond("group_week.html")
        val r = api.fetch(SubscriptionType.GROUP, null, "ІПЗс-24-1", week)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals(
            "faculty=0&teacher=&course=0&group=%B2%CF%C7%F1-24-1&sdate=13.10.2026&edate=19.10.2026&n=700",
            req.body.readUtf8(),
        )
        assertEquals("-607", r.siteId)
    }

    @Test
    fun `http error becomes NetworkException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(NetworkException::class.java) {
            kotlinx.coroutines.runBlocking { api.fetch(SubscriptionType.GROUP, "-607", "x", week) }
        }
    }

    @Test
    fun `unreachable server becomes NetworkException`() {
        server.shutdown()
        assertThrows(NetworkException::class.java) {
            kotlinx.coroutines.runBlocking { api.fetch(SubscriptionType.GROUP, "-607", "x", week) }
        }
    }

    @Test
    fun `broken suggest json is a format error`() {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertThrows(SiteFormatException::class.java) {
            kotlinx.coroutines.runBlocking { api.suggest(SubscriptionType.GROUP, "ІП", 0, 0) }
        }
    }
}
