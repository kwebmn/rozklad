package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.data.remote.ParsedSchedule
import io.github.kwebmn.rozklad.data.remote.ScheduleSource
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.SubscriptionType

class FakeScheduleSource : ScheduleSource {
    data class FetchCall(val siteId: String?, val name: String, val range: DateRange)

    var suggestions: List<String> = emptyList()
    val suggestCalls = mutableListOf<String>()
    var schedule = ParsedSchedule(SubscriptionType.GROUP, "ІПЗс-24-1", "-607", emptyList())
    var error: Exception? = null
    val fetchCalls = mutableListOf<FetchCall>()
    /** Вызывается внутри fetch — имитирует действия пользователя во время сетевого запроса. */
    var onFetch: (suspend () -> Unit)? = null

    override suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String> {
        suggestCalls += query
        error?.let { throw it }
        return suggestions
    }

    override suspend fun fetch(type: SubscriptionType, siteId: String?, name: String, range: DateRange): ParsedSchedule {
        fetchCalls += FetchCall(siteId, name, range)
        onFetch?.invoke()
        error?.let { throw it }
        return schedule
    }
}
