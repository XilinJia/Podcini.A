package ac.mdiq.podcini.sourcing.download

import ac.mdiq.podcini.shared.HTTPAttribs
import ac.mdiq.podcini.shared.HasCredentials
import ac.mdiq.podcini.shared.prepareUrl
import ac.mdiq.podcini.sourcing.AVStreams
import ac.mdiq.podcini.sourcing.clientByEpisode
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.Feed
import ac.mdiq.podcini.storage.specs.VideoMode
import ac.mdiq.podcini.storage.utils.cacheDir
import ac.mdiq.podcini.storage.utils.div
import ac.mdiq.podcini.storage.utils.toUF
import ac.mdiq.podcini.utils.BROWSER_USER_AGENT
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.LogeFor
import ac.mdiq.podcini.utils.PODCINI_USER_AGENT
import android.os.Bundle
import io.ktor.util.AttributeKey
import kotlinx.coroutines.runBlocking

enum class RequestType(val code: Int) {
    FEED(0),
    FEEDMEDIA(1);
}

class DownloadRequest: HasCredentials {
    var useragent: String = ""

    var destination: String = ""

    val sources = MutableList<String?>(2) { null }

    var urlOrig: String? = null
        set(value) {
            field = value
            sources[0] = value
        }

    var title: String? = null
    var feedfileId: Long = 0L
    var feedfileType: Int = 0
    var lastModified: String? = null
    var arguments: Bundle = Bundle()
    var initiatedByUser: Boolean = false

    var progressPercent: Int = 0
    var soFar: Long = 0
    var size: Long = 0
    var statusMsg = 0

    constructor(destination: String, media: Episode): super("", "") {
        this.destination = destination
        this.urlOrig = media.downloadUrl
        var at = HTTPAttribs()
        at.useragent = PODCINI_USER_AGENT
        clientByEpisode(media)?.let { c->
            val streams = AVStreams(media, false)
            streams.forceFetch = true
            if (c.attributes?.hasSeparateAVs == true) {
                runBlocking { streams.chooseAudioSpec() }
                streams.curAudioSpec?.let {
                    val url = it.url
                    this.sources[0] = if (!url.isNullOrBlank()) prepareUrl(url) else null
                }
                if (!this.sources[0].isNullOrBlank() && media.feed?.videoModePolicy != VideoMode.AUDIO_ONLY) {
                    runBlocking { streams.chooseVideoSpec(false) }
                    streams.curVideoSpec?.let {
                        val url = it.url
                        this.sources[1] = if (!url.isNullOrBlank()) prepareUrl(url) else null
                    }
                } else if (this.sources[0].isNullOrBlank()) {
                    runBlocking { streams.chooseVideoSpec(true) }
                    streams.curMuxedSpec?.let {
                        val url = it.url
                        this.sources[0] = if (!url.isNullOrBlank()) prepareUrl(url) else null
                    }
                }
                Logd(TAG) { "sources[0]: ${sources[0]}" }
                Logd(TAG) { "sources[1]: ${sources[1]}" }
            } else {
                var url: String? = ""
                if (c.attributes?.hasVideo == true) {
                    runBlocking { streams.chooseVideoSpec(true) }
                    streams.curMuxedSpec?.let { url = it.url }
                } else {
                    runBlocking { streams.chooseAudioSpec() }
                    streams.curAudioSpec?.let { url = it.url }
                }
                this.sources[0] = if (!url.isNullOrBlank()) prepareUrl(url!!) else null
            }
            at.useragent = BROWSER_USER_AGENT
            c.withProviderBlocking { it.httpAtribsFor(sources[0]) }?.let { at = it }
        }
        this.useragent = at.useragent
        this.title = media.title ?: media.downloadUrl
        this.feedfileId = media.id
        this.feedfileType = RequestType.FEEDMEDIA.code
    }

    constructor(destination: String, feed: Feed): super("", "") {
        this.destination = destination
        this.urlOrig = when {
            feed.isLocal -> feed.downloadUrl
            feed.downloadUrl != null -> prepareUrl(feed.downloadUrl!!)
            else -> null
        }
        this.title = feed.textIdentifier
        this.feedfileId = feed.id
        this.feedfileType = RequestType.FEED.code
        arguments.putInt(REQUEST_ARG_PAGE_NR, feed.pageNr)
    }

    fun withInitiatedByUser(initiatedByUser: Boolean): DownloadRequest {
        this.initiatedByUser = initiatedByUser
        return this
    }
    fun lastModified(lastModified: String?): DownloadRequest {
        this.lastModified = lastModified
        return this
    }
    fun withAuthentication(username: String?, password: String?): DownloadRequest {
        this.username = username
        this.password = password
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadRequest) return false

        if (if (lastModified != null) lastModified != other.lastModified else other.lastModified != null) return false
        if (feedfileId != other.feedfileId) return false
        if (feedfileType != other.feedfileType) return false
        if (progressPercent != other.progressPercent) return false
        if (size != other.size) return false
        if (soFar != other.soFar) return false
        if (statusMsg != other.statusMsg) return false
        if (destination != other.destination) return false
        if (if (password != null) password != other.password else other.password != null) return false
        if (sources != other.sources) return false
        if (if (title != null) title != other.title else other.title != null) return false
        if (if (username != null) username != other.username else other.username != null) return false
//        if (mediaEnqueued != other.mediaEnqueued) return false
        if (initiatedByUser != other.initiatedByUser) return false
        return true
    }

    override fun hashCode(): Int {
        var result = destination.hashCode()
        result = 31 * result + sources.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + lastModified.hashCode()
        result = 31 * result + (feedfileId xor (feedfileId ushr 32)).toInt()
        result = 31 * result + feedfileType
        result = 31 * result + arguments.hashCode()
        result = 31 * result + progressPercent
        result = 31 * result + (soFar xor (soFar ushr 32)).toInt()
        result = 31 * result + (size xor (size ushr 32)).toInt()
        result = 31 * result + statusMsg
//        result = 31 * result + (if (mediaEnqueued) 1 else 0)
        return result
    }

    suspend fun ensureMediaFileExists() {
        val destinationPath = destination
        Logd(TAG) { "ensureMediaFileExists destinationUri: $destinationPath " }
        var uf = destinationPath.toUF()
        if (!uf.exists()) uf = uf.createFile()
        if (!uf.exists()) Loge(TAG, "ensureMediaFileExists no: ${uf.absPath}")
        Logd(TAG) { "ensureMediaFileExists request.destination: $destination" }
        Logd(TAG) { "ensureMediaFileExists file.absPath: ${uf.absPath}" }
        destination = uf.absPath
    }

    companion object {
        private const val TAG = "DownloadRequest"
        const val REQUEST_ARG_PAGE_NR: String = "page"

        val CredentialsKey = AttributeKey<HasCredentials>("Credentials")

        suspend fun requestFor(feed: Feed): DownloadRequest {
            val dest = cacheDir / feed.getFeedfileName()
            if (dest.exists()) runOnIOScope { dest.delete() }
            Logd(TAG) { "requestFor download feed from url " + feed.downloadUrl }
            val username = feed.username
            val password = feed.password
            return DownloadRequest(dest.absPath, feed).withAuthentication(username, password).lastModified(feed.lastUpdate)
        }

        suspend fun requestFor(media: Episode): DownloadRequest? {
            Logd(TAG) { "requestFor: ${media.fileUrl} ${media.title}" }
            val destUriString = try { media.mediaFileUriString() } catch (e: Throwable ) { "" }
            Logd(TAG) { "requestFor destUriString: $destUriString" }
            if (destUriString.isBlank()) {
                LogeFor(TAG, media.id, "destUriString is invalid: [$destUriString] for media: ${media.title}")
                return null
            }
            val feed = media.feed
            val username = feed?.username
            val password = feed?.password
            return DownloadRequest(destUriString, media).withAuthentication(username, password)
        }
    }
}