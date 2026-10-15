package io.github.kwebmn.rozklad.fakes

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Часы для тестов, которые можно переводить: `clock.set("2026-10-16T07:00")` (местное время Киева). */
class MutableClock(local: String, private val zone: ZoneId = KYIV) : Clock() {
    var instant: Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    fun set(local: String) {
        instant = LocalDateTime.parse(local).atZone(zone).toInstant()
    }

    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(LocalDateTime.ofInstant(instant, zone).toString(), zone)
    override fun instant(): Instant = instant
}
