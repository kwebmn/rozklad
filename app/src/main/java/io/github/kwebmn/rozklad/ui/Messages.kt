package io.github.kwebmn.rozklad.ui

import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.SiteFormatException

const val NETWORK_ERROR = "Немає з'єднання з сайтом розкладу"
const val PARSE_ERROR = "Не вдалося розібрати відповідь сайту"

/** Текст для известных ошибок сайта; null — ошибка неизвестна и должна пробрасываться дальше. */
fun errorMessage(e: Exception): String? = when (e) {
    is NetworkException -> NETWORK_ERROR
    is SiteFormatException -> PARSE_ERROR
    else -> null
}
