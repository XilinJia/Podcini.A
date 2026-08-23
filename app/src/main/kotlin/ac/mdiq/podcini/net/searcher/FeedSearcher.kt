package ac.mdiq.podcini.net.searcher

import ac.mdiq.podcini.BuildConfig
import ac.mdiq.podcini.shared.FeedSearchResult
import ac.mdiq.podcini.shared.FeedSearcher
import ac.mdiq.podcini.shared.PodciniHttpClient.getKtorClient
import ac.mdiq.podcini.shared.USER_AGENT
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.sources.sourceClients
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.formatEpochMillisSimple
import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.util.hex
import io.ktor.util.sha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.io.IOException
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "FeedSearcher"

enum class FeedSearchers {
    PodcastIndex,
    Apple,
    AppleDeep,
    Combined
}

class PodcastIndexSearcher : FeedSearcher {
    override suspend fun search(query: String): List<FeedSearchResult> {
        Logd(TAG, "PodcastIndexSearcher search")
        fun fromPodcastIndex(json: JSONObject): FeedSearchResult {
            val title = json.optString("title", "")
            val imageUrl: String? = json.optString("image").takeIf { it.isNotEmpty() }
            val feedUrl: String? = json.optString("url").takeIf { it.isNotEmpty() }
            val author: String? = json.optString("author").takeIf { it.isNotEmpty() }
            var count: Int? = json.optInt("episodeCount", -1)
            if (count != null && count < 0) count = null
            val updateInt: Int = json.optInt("lastUpdateTime", -1)
            var update: String? = null
            if (updateInt > 0) update = formatEpochMillisSimple(updateInt.toLong() * 1000, "yyyy-MM-dd")
            return FeedSearchResult(title, imageUrl, feedUrl, author, count, update, -1, "PodcastIndex")
        }

        val encodedQuery = query.encodeURLParameter()

        val formattedUrl = "https://api.podcastindex.org/api/1.0/search/byterm?q=${encodedQuery}"
        val podcasts: MutableList<FeedSearchResult> = mutableListOf()
        try {
            val response = getKtorClient().get(formattedUrl) { applyPodcastIndexAuth() }
            if (response.status.isSuccess()) {
                val resultString = response.bodyAsText()
                Logd(TAG, "search resultString: $resultString")
                val result = JSONObject(resultString)
                val j = result.getJSONArray("feeds")
                for (i in 0 until j.length()) {
                    val podcastJson = j.getJSONObject(i)
                    val podcast = fromPodcastIndex(podcastJson)
                    if (podcast.feedUrl != null) podcasts.add(podcast)
                }
            } else throw IOException(response.toString())
        } catch (e: IOException) { throw e
        } catch (e: JSONException) { throw e }
        return podcasts
    }

    override val name: String
        get() = FeedSearchers.PodcastIndex.name

    companion object {
        private const val SEARCH_API_URL = "https://api.podcastindex.org/api/1.0/search/byterm?q=%s"
        private fun sha1(clearString: String): String {
            val bytes = clearString.toByteArray(Charsets.UTF_8)
            val digest = sha1(bytes)
            return hex(digest)
        }
        fun HttpRequestBuilder.applyPodcastIndexAuth() {
            val now = nowInMillis()
            val secondsSinceEpoch = now / 1000L
            val apiHeaderTime = secondsSinceEpoch.toString()
            val data4Hash = BuildConfig.PODCASTINDEX_API_KEY + BuildConfig.PODCASTINDEX_API_SECRET + apiHeaderTime
            val hashString = sha1(data4Hash)
            header("X-Auth-Date", apiHeaderTime)
            header("X-Auth-Key", BuildConfig.PODCASTINDEX_API_KEY)
            header("Authorization", hashString)
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
    }
}

open class ItunesSearcher : FeedSearcher {
    private val TAG = "ItunesSearcher"

    open suspend fun fromItunes(json: JSONObject): FeedSearchResult {
        val title = json.optString("collectionName", "")
        val imageUrl: String? = json.optString("artworkUrl100").takeIf { it.isNotEmpty() }
        val feedUrl: String? = json.optString("feedUrl").takeIf { it.isNotEmpty() }
        val author: String? = json.optString("artistName").takeIf { it.isNotEmpty() }
        return FeedSearchResult(title, imageUrl, feedUrl, author, null, null, -1, "Itunes")
    }

    override suspend fun search(query: String): List<FeedSearchResult> {
        val encodedQuery = query.encodeURLParameter()
        val formattedUrl = "https://itunes.apple.com/search?media=podcast&entity=podcast&term=$encodedQuery"
        Logd(TAG, "search formattedUrl: $formattedUrl")
        val podcasts: MutableList<FeedSearchResult> = mutableListOf()
        try {
            val response = getKtorClient().get(formattedUrl) { header(HttpHeaders.CacheControl, "max-stale=86400") }
            if (response.status.isSuccess()) {
                val resultString = response.bodyAsText()
//                Logd(TAG, "search resultString: $resultString")
                val result = JSONObject(resultString)
                val j = result.getJSONArray("results")
                for (i in 0 until j.length()) {
                    val podcastJson = j.getJSONObject(i)
                    val podcast = fromItunes(podcastJson)
                    if (podcast.feedUrl != null) podcasts.add(podcast)
                }
            } else throw IOException(response.toString())
        } catch (e: IOException) { throw e
        } catch (e: JSONException) { throw e }
        return podcasts
    }

    override suspend fun lookupUrl(url: String): String {
        val lookupUrl = PATTERN_BY_ID.find(url)?.let { match -> "https://itunes.apple.com/lookup?id=${match.groups[1]?.value}" } ?: url
        val resultString = try {
            val response = getKtorClient().get(lookupUrl) { header(HttpHeaders.CacheControl, "max-stale=86400") }
            if (response.status.isSuccess()) response.bodyAsText()
            else throw IOException(response.toString())
        } catch (e: Exception) {
            Logs(TAG, e, "get feedString error")
            ""
        }
        if (resultString.trim().startsWith('<')) return url     // XML already

        Logd(TAG, "lookupUrl resultString: $resultString")
        val result = JSONObject(resultString)
        val results = result.getJSONArray("results").getJSONObject(0)
        val feedUrlName = "feedUrl"
        if (!results.has(feedUrlName)) {
            val artistName = results.getString("artistName")
            val trackName = results.getString("trackName")
            throw FeedUrlNotFoundException(artistName, trackName)
        }
        return results.getString(feedUrlName)
    }

    override fun urlNeedsLookup(url: String): Boolean {
        Logd(TAG, "urlNeedsLookup url: $url")
        // TODO: may also need to check podcasts.apple.com?
        return url.contains("//itunes.apple.com") || url.matches(PATTERN_BY_ID)
    }

    override val name: String
        get() = FeedSearchers.Apple.name

    companion object {
        private const val ITUNES_API_URL = "https://itunes.apple.com/search?media=podcast&term=%s"
        private val PATTERN_BY_ID = Regex(".*/podcasts\\.apple\\.com/.*/podcast/.*/id(\\d+).*")
    }
}

class ItunesDeepSearcher: ItunesSearcher() {
    private val TAG = "ItunesDeepSearcher"

    override val name: String
        get() = FeedSearchers.AppleDeep.name

    override suspend fun fromItunes(json: JSONObject): FeedSearchResult {
        val title = json.optString("collectionName", "")
        val imageUrl: String? = json.optString("artworkUrl100").takeIf { it.isNotEmpty() }
        var feedUrl: String? = json.optString("feedUrl").takeIf { it.isNotEmpty() }
        if (feedUrl.isNullOrBlank()) json.optString("collectionViewUrl").takeIf { it.isNotEmpty() }?.apply { feedUrl = extractAppleFeedUrl(this) }
        val author: String? = json.optString("artistName").takeIf { it.isNotEmpty() }
        return FeedSearchResult(title, imageUrl, feedUrl, author, null, null, -1, "Itunes")
    }

    private val feedUrlRegex = Regex(""""feedUrl"\s*:\s*"([^"]+)"""")

    private suspend fun extractAppleFeedUrl(collectionViewUrl: String): String? {
        fun unescapeUrl(url: String): String {
            return url.replace("\\/", "/")
        }
        return runCatching {
            val html = getKtorClient().get(collectionViewUrl) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            }.bodyAsText()
            val directMatch = feedUrlRegex.find(html)?.groupValues?.get(1)
            if (directMatch != null) return@runCatching unescapeUrl(directMatch)

            val doc = Ksoup.parse(html)
            val scriptElements = doc.select("script[type=application/json], script#serialized-server-data, script")
            for (script in scriptElements) {
                val content = script.html()
                val match = feedUrlRegex.find(content)?.groupValues?.get(1)
                if (match != null) return@runCatching unescapeUrl(match)
            }
            null
        }.getOrNull()
    }
}
class CombinedSearcher : FeedSearcher {
    override suspend fun search(query: String): List<FeedSearchResult> {
        Logd(TAG, "CombinedSearcher search")
        val searchProviders = PodcastSearcherRegistry.searcherInfos
        val searchResults = MutableList<List<FeedSearchResult>>(searchProviders.size) { listOf() }

        // Using a supervisor scope to ensure that one failing child does not cancel others
        supervisorScope {
            val searchJobs = searchProviders.mapIndexed { index, searchProviderInfo ->
                val searcher = searchProviderInfo.searcher
                if (searchProviderInfo.weight > 0.00001f && searcher.javaClass != CombinedSearcher::class.java) {
                    async(Dispatchers.IO) { try { searchResults[index] = searcher.search(query) } catch (e: Throwable) { Logs(TAG, e) } }
                } else null
            }.filterNotNull()
            searchJobs.awaitAll()
        }
        return weightSearchResults(searchResults)
    }

    private fun weightSearchResults(singleResults: List<List<FeedSearchResult>>): List<FeedSearchResult> {
        val resultRanking = mutableMapOf<String?, Float>()
        val urlToResult = mutableMapOf<String?, FeedSearchResult>()
        for (i in singleResults.indices) {
            val providerPriority = PodcastSearcherRegistry.searcherInfos[i].weight
            val providerResults = singleResults[i]
            for (position in providerResults.indices) {
                val result = providerResults[position]
                urlToResult[result.feedUrl] = result
                var ranking = 0f
                if (resultRanking.containsKey(result.feedUrl)) ranking = resultRanking[result.feedUrl]!!
                ranking += 1f / (position + 1f)
                resultRanking[result.feedUrl] = ranking * providerPriority
            }
        }
        //        val sortedResults = mutableListOf<MutableMap.MutableEntry<String?, Float>>(resultRanking.entries)
        val sortedResults = resultRanking.entries.toMutableList()
        sortedResults.sortWith { o1: Map.Entry<String?, Float>, o2: Map.Entry<String?, Float> -> o2.value.toDouble().compareTo(o1.value.toDouble()) }

        val results: MutableList<FeedSearchResult> = mutableListOf()
        for ((key) in sortedResults) {
            val v = urlToResult[key] ?: continue
            results.add(v)
        }
        return results
    }

    override suspend fun lookupUrl(url: String): String = PodcastSearcherRegistry.lookupUrl(url)

    override fun urlNeedsLookup(url: String): Boolean = PodcastSearcherRegistry.urlNeedsLookup(url)

    override val name: String
        get() {
            val names = mutableListOf<String?>()
            for (i in PodcastSearcherRegistry.searcherInfos.indices) {
                val searchProviderInfo = PodcastSearcherRegistry.searcherInfos[i]
                val searcher = searchProviderInfo.searcher
                if (searchProviderInfo.weight > 0.00001f && searcher.javaClass != CombinedSearcher::class.java) names.add(searcher.name)
            }
            return names.joinToString()
        }

    companion object {
        private val TAG: String = CombinedSearcher::class.simpleName ?: "Anonymous"
    }
}

object PodcastSearcherRegistry {
    @get:Synchronized
    var searcherInfos: MutableList<SearcherInfo> = mutableListOf()
        get() {
            if (field.isEmpty()) {
                field = mutableListOf()
                field.add(SearcherInfo(FeedSearchers.Combined.name, CombinedSearcher(), 1.0f))
                val extSearchers = sourceClients.mapNotNull { it.feedSearcher }
                if (extSearchers.isNotEmpty()) field.addAll(extSearchers.map { SearcherInfo(it.name?:"Anonymous", it, 1.0f) })
                field.add(SearcherInfo(FeedSearchers.Apple.name, ItunesSearcher(), 1.0f))
                field.add(SearcherInfo(FeedSearchers.AppleDeep.name, ItunesDeepSearcher(), 0.0f))
                field.add(SearcherInfo(FeedSearchers.PodcastIndex.name, PodcastIndexSearcher(), 1.0f))
            }
            return field
        }
        private set

    suspend fun lookupUrl(url: String): String {
        for (searcherInfo in searcherInfos) {
            if (searcherInfo.searcher.javaClass != CombinedSearcher::class.java && searcherInfo.searcher.urlNeedsLookup(url))
                return searcherInfo.searcher.lookupUrl(url)
        }
        return url
    }

    fun urlNeedsLookup(url: String): Boolean {
        for (searcherInfo in searcherInfos) {
            if (searcherInfo.searcher.javaClass != CombinedSearcher::class.java && searcherInfo.searcher.urlNeedsLookup(url)) return true
        }
        return false
    }

    class SearcherInfo(val tag: String, val searcher: FeedSearcher, val weight: Float)
}

class FeedUrlNotFoundException( val artistName: String,  val trackName: String) : IOException() {
    override val message: String
        get() = "Result does not specify a feed url"
}
