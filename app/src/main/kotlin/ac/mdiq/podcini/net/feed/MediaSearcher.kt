package ac.mdiq.podcini.net.feed

import ac.mdiq.podcini.shared.EpisodeIPC
import ac.mdiq.podcini.shared.MediaSearcher
import ac.mdiq.podcini.shared.PodciniHttpClient.getKtorClient
import ac.mdiq.podcini.storage.utils.parseDate
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import org.json.JSONObject

class AppleMediaSearcher : MediaSearcher {
    val TAG = "AppleMediaSearcher"

    override val name: String = "Apple"

    open suspend fun fromItunes(json: JSONObject): EpisodeIPC {
        val e = EpisodeIPC()
        e.link = json.optString("trackViewUrl", "")
        e.title = json.optString("trackName", "")
        e.description = "Short: ${json.optString("shortDescription", "")}"
        e.imageUrl = json.optString("artworkUrl60", "")
        e.pubDate = parseDate(json.optString("releaseDate", ""))?.toEpochMilliseconds() ?: 0L
        e.mimeType = "${json.optString("episodeContentType", "")}/*"
        e.fileUrl = null
        e.downloadUrl = json.optString("episodeUrl", "")
        e.duration = json.optInt("trackTimeMillis", 0)
        return e
    }

    // https://itunes.apple.com/search?term=Android&media=podcast&entity=podcastEpisode
    override suspend fun search(query: String, limit: Int): List<EpisodeIPC> {
        return listOf()
    }
    override suspend fun searchQuick(query: String): List<EpisodeIPC> {
        val encodedQuery = query.encodeURLParameter()
        val formattedUrl = "https://itunes.apple.com/search?term=$encodedQuery&media=podcast&entity=podcastEpisode&limit=200"
        val medias: MutableList<EpisodeIPC> = mutableListOf()
        try {
            val response = getKtorClient().get(formattedUrl) { header(HttpHeaders.CacheControl, "max-stale=86400") }
            if (response.status.isSuccess()) {
                val resultString = response.bodyAsText()
                Logd(TAG, "search resultString: $resultString")
                val result = JSONObject(resultString)
                val j = result.getJSONArray("results")
                for (i in 0 until j.length()) {
                    val podcastJson = j.getJSONObject(i)
                    val media = fromItunes(podcastJson)
                    if (media.title?.contains(query, ignoreCase = true) == true) medias.add(media)
                }
            } else Loge(TAG, "Failed finding media: HttpClient returns failure")
        } catch (e: Exception) { Loge(TAG, e, "Failed finding media on $query") }
        return medias
    }
    override suspend fun getMoreItems(): List<EpisodeIPC> {
        return listOf()
    }
}