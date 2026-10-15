package io.github.kwebmn.rozklad.data.remote

import java.net.URLEncoder
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object SiteCodec {
    val CHARSET: Charset = Charset.forName("windows-1251")
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Значение POST-формы расписания: сайт ждёт windows-1251. */
    fun formEncode(value: String): String = URLEncoder.encode(value, "windows-1251")

    /** Параметр query автокомплита: браузер шлёт UTF-8 (URLSearchParams). */
    fun queryEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun decode(bytes: ByteArray): String = String(bytes, CHARSET)

    fun formatDate(date: LocalDate): String = date.format(DATE)
}
