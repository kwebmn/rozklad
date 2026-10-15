package io.github.kwebmn.rozklad.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String)

fun interface ReleaseSource {
    suspend fun latest(): ReleaseInfo?
}

object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
    )

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    fun parse(text: String): ReleaseInfo? {
        val release = json.decodeFromString<GhRelease>(text)
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
        return ReleaseInfo(release.tagName, release.body.orEmpty(), apk.url)
    }
}

class UpdateApi(
    private val client: OkHttpClient,
    private val url: String = LATEST_URL,
) : ReleaseSource {
    override suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                    else -> ReleaseParser.parse(response.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            throw NetworkException(e)
        } catch (e: IllegalArgumentException) {
            throw NetworkException(e)
        }
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/kwebmn/rozklad/releases/latest"
    }
}
