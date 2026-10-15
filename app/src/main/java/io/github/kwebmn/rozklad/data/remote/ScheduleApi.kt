package io.github.kwebmn.rozklad.data.remote

import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.SubscriptionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NetworkException(cause: Throwable) : Exception(cause)

interface ScheduleSource {
    suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String>
    suspend fun fetch(type: SubscriptionType, siteId: String?, name: String, range: DateRange): ParsedSchedule
}

class ScheduleApi(
    private val client: OkHttpClient,
    private val baseUrl: String = BASE_URL,
) : ScheduleSource {

    override suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String> {
        val url = buildString {
            append(baseUrl)
            append(if (type == SubscriptionType.GROUP) "?n=701&lev=142" else "?n=701&lev=141")
            append("&faculty=").append(faculty)
            if (type == SubscriptionType.GROUP) append("&course=").append(course)
            append("&query=").append(SiteCodec.queryEncode(query))
        }
        return SuggestParser.parse(execute(Request.Builder().url(url).build()))
    }

    override suspend fun fetch(type: SubscriptionType, siteId: String?, name: String, range: DateRange): ParsedSchedule {
        val dates = "sdate=${SiteCodec.formatDate(range.from)}&edate=${SiteCodec.formatDate(range.to)}"
        val request = if (siteId != null) {
            val param = if (type == SubscriptionType.GROUP) "group" else "teacher"
            Request.Builder().url("$baseUrl?n=700&$param=$siteId&$dates").build()
        } else {
            val group = if (type == SubscriptionType.GROUP) SiteCodec.formEncode(name) else ""
            val teacher = if (type == SubscriptionType.TEACHER) SiteCodec.formEncode(name) else ""
            val body = "faculty=0&teacher=$teacher&course=0&group=$group&$dates&n=700"
                .toRequestBody(FORM)
            Request.Builder().url("$baseUrl?n=700").post(body).build()
        }
        return ScheduleParser.parse(execute(request))
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                SiteCodec.decode(response.body?.bytes() ?: ByteArray(0))
            }
        } catch (e: IOException) {
            throw NetworkException(e)
        }
    }

    companion object {
        const val BASE_URL = "http://195.162.83.28/cgi-bin/timetable.cgi"
        private val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}
