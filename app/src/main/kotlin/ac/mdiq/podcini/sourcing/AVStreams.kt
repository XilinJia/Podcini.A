package ac.mdiq.podcini.sourcing

import ac.mdiq.podcini.R
import ac.mdiq.podcini.shared.AudioSpec
import ac.mdiq.podcini.shared.MediaSpecs
import ac.mdiq.podcini.shared.VideoSpec
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.model.Episode
import ac.mdiq.podcini.storage.model.toIPC
import ac.mdiq.podcini.storage.specs.AVQuality
import ac.mdiq.podcini.ui.compose.borderColor
import ac.mdiq.podcini.ui.compose.filterChipBorder
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logt
import ac.mdiq.podcini.utils.NetworkUtils.networkMonitor
import ac.mdiq.podcini.utils.nowInSeconds
import androidx.collection.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow

class AVStreams(val media: Episode, val useCache: Boolean = true) {

    private var client: SourceGatewayClient? = null

    private var mediaSpecs: MediaSpecs = MediaSpecs()

    private val curLocales = mutableSetOf<String>()
    private var useLocales: Set<String> = setOf()
    private var useLocale: String? = null
    private var useCodex: String = "Any"
    private var useProt: String? = null
    private var useABPS: Int = 0
    private var useVCodex: String = "Any"
    private var useVProt: String? = null
    private var useResolution: String? = null

    var curAudioSpec: AudioSpec? = null
    var curVideoSpec: VideoSpec? = null
    var curMuxedSpec: VideoSpec? = null

    val bitrateFlow = MutableStateFlow(0)
    val resolutionFlow = MutableStateFlow("")

    var forceFetch = false

    init {
        bitrateFlow.value = 0
        resolutionFlow.value = ""
        setAudioStream()
        useVCodex = "Any"
        useResolution = null
        client = clientByEpisode(media)
//        if (client != null) specsFromCache()
    }

    private suspend fun getAVSpecs() {
        specsFromCache()
        if (!mediaSpecs.isInit) {
            Logd(TAG) { "getAVSpecs do init"}
            if (client == null) client = clientByEpisode(media)
            client?.withProvider { it.getMediaSpecs(media.toIPC(), forceFetch) }?.let { ms->
                mediaSpecs = ms
                Logd(TAG) { "getAVSpecs audios: ${mediaSpecs.audioSpecs?.size} videos: ${mediaSpecs.videoSpecs?.size} muxed: ${mediaSpecs.muxedSpecs?.size}"}
                forceFetch = false
                if (useCache) {
                    mediaSpecs.audioSpecs?.let { audioSpecsCache.put(media.id, it) }
                    mediaSpecs.videoSpecs?.let { videoSpecsCache.put(media.id, it) }
                    mediaSpecs.muxedSpecs?.let { muxedSpecsCache.put(media.id, it) }
                }
            }
        }
    }

    private fun setAudioStream(locale: String? = null, codec: String = "Any", aveBitrate: Int = 0) {
        Logd(TAG) { "setAudioStream: locale: $locale codec: $codec averageBitrate: $aveBitrate" }
        useLocale = locale
        useCodex = codec
        useABPS = aveBitrate
    }

    internal suspend fun chooseAudioSpec() {
        getAVSpecs()
        val asl = mutableListOf<AudioSpec>()
        //        Logd(TAG) { "useLocale: $useLocale useCodex: $useCodex useABPS: $useABPS audioIndex: $audioIndex" }
        Logd(TAG) { "chooseAudioSpec media.feed?.preferredLnaguages: [${media.feed?.preferredLnaguages?.joinToString()}]" }
        Logd(TAG) { "chooseAudioSpec appAttribs.langsPreferred: [${appAttribsFlow!!.value.langsPreferred.joinToString()}]" }
        useLocales = media.feed?.preferredLnaguages?.ifEmpty { appAttribsFlow!!.value.langsPreferred }?.ifEmpty { setOf("en-US", "en-GB", "en") } ?: setOf("en-US", "en-GB", "en")
        Logd(TAG) { "chooseAudioSpec useLocales: ${useLocales.joinToString()}" }
        curLocales.clear()
        for (s in mediaSpecs.audioSpecs ?: listOf()) {
            Logd(TAG) { "chooseAudioSpec s.audioLocale [${s.audioLocale}] ${s.codec} ${s.averageBitrate}" }
            curLocales.add(s.audioLocale ?: "")
            if ((useLocale == null || useLocale == s.audioLocale) && (useProt == null || s.deliveryMethod == useProt) && (useCodex == "Any" || s.codec == useCodex) && (useABPS == 0 || s.averageBitrate == useABPS)) {
                when {
                    s.audioLocale == null -> asl.add(s)
                    useLocale != null -> asl.add(s)
                    s.audioLocale in useLocales -> asl.add(s)
                }
            }
        }
        Logd(TAG) { "chooseAudioSpec langset: [${curLocales.joinToString()}]" }
        if (curLocales.isNotEmpty()) {
            runOnIOScope {
                if (media.feed != null && !media.feed!!.langSet.containsAll(curLocales)) upsert(media.feed!!) { it.langSet.addAll(curLocales) }
                if (!appAttribsFlow!!.value.langSet.containsAll(curLocales)) upsert(appAttribsFlow!!.value) { it.langSet.addAll(curLocales) }
            }
        }
        Logd(TAG) { "chooseAudioSpec asl: ${asl.size}" }
        if (asl.isEmpty()) {
            Loge(TAG, "chooseAudioSpec: eligible audio stream list is empty.\nAvailable languages: ${curLocales.joinToString()}.\nYou prefer: ${useLocales.joinToString()}")
            bitrateFlow.value = 0
            resolutionFlow.value = ""
            curAudioSpec = null
            return
        }

        if (useABPS > 0) {
            val audioSpec = asl.filter { it.averageBitrate == useABPS }.filter { useCodex == "Any" || it.codec == useCodex }.firstOrNull { useLocale == null || it.audioLocale == useLocale }
            if (audioSpec != null) {
                bitrateFlow.value = audioSpec.bitrate
                curAudioSpec = audioSpec
                return
            } else Logt(TAG, "chooseAudioSpec Requested audio doesn't exist ($useLocale, $useCodex, $useABPS), getting one based on settings.")
        }

        val prefLowQualityMedia: Boolean = appPrefsFlow!!.value.lowQualityOnMobile
        val audioIndex =
            if (networkMonitor.isNetworkRestricted && prefLowQualityMedia && media.feed?.audioQualitySetting == AVQuality.GLOBAL) 0
            else {
                when (media.feed?.audioQualitySetting) {
                    AVQuality.LOW -> 0
                    AVQuality.MEDIUM -> asl.size / 2
                    AVQuality.HIGH -> asl.size - 1
                    else -> {
                        when (appPrefsFlow!!.value.audioQuality) {
                            AVQuality.LOW.code -> 0
                            AVQuality.MEDIUM.code -> asl.size / 2
                            AVQuality.HIGH.code -> asl.size - 1
                            else -> asl.size - 1
                        }
                    }
                }
            }

        for (a in asl) {
            Logd(TAG) { "chooseAudioSpec asl: bitrate: ${a.bitrate} averageBitrate: ${a.averageBitrate} delivery: ${a.deliveryMethod}  codec: ${a.codec} audioLocale: ${a.audioLocale.toString()} id: ${a.audioTrackId} name: ${a.audioTrackName} format: ${a.format}" }
            a.url?.toUri()?.let { Logd(TAG) { "c=${it.getQueryParameter("c")} pot=${it.getQueryParameter("pot") != null} ${a.url}" } }
        }

        val audioSpec = if (audioIndex >= 0 && audioIndex < asl.size) asl[audioIndex] else null
        bitrateFlow.value = audioSpec?.bitrate ?: 0
        Logd(TAG) { "chooseAudioSpec use audio quality: ${audioSpec?.bitrate} forceVideo: ${media.forceVideo}" }
        Logd(TAG) { "chooseAudioSpec: ${audioSpec?.url}" }
        curAudioSpec = audioSpec
        audioSpec?.let { Logd(TAG) { "chooseAudioSpec set to: ${it.audioLocale} ${it.codec} ${it.bitrate} ${it.url}" } }
    }

    internal suspend fun chooseVideoSpec(muxed: Boolean) {
        getAVSpecs()
        val vSpecs = if (muxed) mediaSpecs.muxedSpecs else mediaSpecs.videoSpecs
        if (vSpecs.isNullOrEmpty()) {
            if (muxed) curMuxedSpec = null
            else curVideoSpec = null
            return
        }
        if (useResolution != null || useVCodex != "Any") {
            val videoSpec = when {
                useVCodex == "Any" ->  vSpecs.firstOrNull { it.resolution == useResolution }
                useResolution == null -> vSpecs.firstOrNull { it.codec == useVCodex }
                else -> vSpecs.firstOrNull { it.codec == useVCodex && it.resolution == useResolution }
            }
            if (videoSpec != null) {
                resolutionFlow.value = videoSpec.resolution ?: ""
                Logd(TAG) { "setVideoSpec use video quality: ${videoSpec.resolution}" }
                if (muxed) curMuxedSpec = videoSpec
                else curVideoSpec = videoSpec
                return
            } else Logt(TAG, "setVideoSpec Requested video with ($useVCodex and $useResolution) doesn't exist, getting one based on settings")
        }
        val videoIndex =
            if (networkMonitor.isNetworkRestricted && appPrefsFlow!!.value.lowQualityOnMobile && media.feed?.videoQualitySetting == AVQuality.GLOBAL) 0
            else {
                when (media.feed?.videoQualitySetting) {
                    AVQuality.LOW -> 0
                    AVQuality.MEDIUM -> vSpecs.size / 2
                    AVQuality.HIGH -> vSpecs.size - 1
                    else -> {
                        when (appPrefsFlow!!.value.videoQuality) {
                            AVQuality.LOW.code -> 0
                            AVQuality.MEDIUM.code -> vSpecs.size / 2
                            AVQuality.HIGH.code -> vSpecs.size - 1
                            else -> 0
                        }
                    }
                }
            }

        for (i in vSpecs.indices) Logd(TAG) { "setVideoSpec $i ${vSpecs[i].bitrate} ${vSpecs[i].fps} ${vSpecs[i].codec} ${vSpecs[i].deliveryMethod} ${vSpecs[i].resolution} ${vSpecs[i].url}" }

        val videoSpec = vSpecs[videoIndex]
        resolutionFlow.value = videoSpec.resolution ?: ""
        Logd(TAG) { "setVideoSpec use video quality: ${videoSpec.resolution}" }
        if (muxed) curMuxedSpec = videoSpec
        else curVideoSpec = videoSpec
        Logd(TAG) { "setVideoSpec vSource set to: ${videoSpec.url}" }
    }

    private fun specsFromCache() {
        mediaSpecs.audioSpecs = audioSpecsCache[media.id]
        mediaSpecs.videoSpecs = videoSpecsCache[media.id]
        mediaSpecs.muxedSpecs = muxedSpecsCache[media.id]
        Logd(TAG) { "specsFromCache mediaSpecs.videoSpecs: ${mediaSpecs.videoSpecs?.size}"}
        val url = if (!mediaSpecs.audioSpecs.isNullOrEmpty()) mediaSpecs.audioSpecs!![0].url else if (!mediaSpecs.muxedSpecs.isNullOrEmpty()) mediaSpecs.muxedSpecs!![0].url else null
        if (!url.isNullOrBlank()) {
            val expireTime = url.toUri().getQueryParameter("expire")?.toLongOrNull()
            Logd(TAG) { "specsFromCache expireTime: $expireTime ${nowInSeconds()}"}
            if (expireTime != null && expireTime < nowInSeconds()) clearSpecs()
        }
    }
    fun clearSpecs() {
        Logd(TAG) { "clearSpecs "}
        audioSpecsCache.remove(media.id)
        videoSpecsCache.remove(media.id)
        muxedSpecsCache.remove(media.id)
        mediaSpecs = MediaSpecs()
        forceFetch = true
    }

    @Composable
    fun StreamChanger(needVideo: Boolean, needMuxed: Boolean, onDismiss: ()->Unit, confirmCB: (Boolean, Boolean)->Unit) {
        var reset by remember { mutableStateOf(false) }
        var useVideo by remember { mutableStateOf(needVideo) }
        var useMuxed by remember { mutableStateOf(needMuxed) }
        var locales by remember { mutableStateOf<List<String>>(listOf()) }
        var locale by remember(media.id) { mutableStateOf(useLocale ?: curAudioSpec?.audioLocale) }
        var codecs by remember { mutableStateOf<List<String>>(listOf()) }
        var codec by remember(media.id) { mutableStateOf(useCodex) }
        var prots by remember { mutableStateOf<List<String>>(listOf()) }
        var prot by remember(media.id) { mutableStateOf(useProt ?: curAudioSpec?.deliveryMethod) }
        var bitRates by remember { mutableStateOf<List<Int>>(listOf()) }
        var bitrate by remember(media.id) { mutableIntStateOf(useABPS) }
        var vcodecs by remember { mutableStateOf<List<String>>(listOf()) }
        var vcodec by remember(media.id) { mutableStateOf(useVCodex) }
        var vprots by remember { mutableStateOf<List<String>>(listOf()) }
        var vprot by remember(media.id) { mutableStateOf(useVProt ?: if (needMuxed) curMuxedSpec?.deliveryMethod else curVideoSpec?.deliveryMethod) }
        var resolutions by remember { mutableStateOf<List<String>>(listOf()) }
        var resolution by remember(media.id) { mutableStateOf(useResolution) }
        val client = remember(media.id) { clientByEpisode(media) }

        fun buildVCodecSet(s: VideoSpec, cSet: MutableSet<String>) {
            if (vprot == null || vprot == "Any" || s.deliveryMethod == vprot) cSet.add(s.codec?:"Any")
        }
        fun buildResoSet(s: VideoSpec, rSet: MutableSet<String>) {
            Logd(TAG) { "buildResoSet vprot: $vprot vcodec: $vcodec"}
            s.resolution?.let {
                if (vprot == null || vprot == "Any" || s.deliveryMethod == vprot) {
                    if (vcodec == "Any" || s.codec == vcodec) rSet.add(it)
                }
            }
        }
        fun buildProtSet(s: AudioSpec, pSet: MutableSet<String>) {
            if (locale == null || s.audioLocale == locale) pSet.add(s.deliveryMethod ?: "Any")
        }
        fun buildCodecSet(s: AudioSpec, cSet: MutableSet<String>) {
            if (prot == null || prot == "Any" || s.deliveryMethod == prot) {
                if (locale == null || s.audioLocale == locale) cSet.add(s.codec ?: "Any")
            }
        }
        fun buildBRSet(s: AudioSpec, bSet: MutableSet<Int>) {
            Logd(TAG) { "buildBRSet prot: $prot locale: $locale codec: $codec"}
            if (prot == null || prot == "Any" || s.deliveryMethod == prot) {
                if (locale == null || s.audioLocale == locale) {
                    if (codec == "Any" || s.codec == codec) bSet.add(s.averageBitrate)
                }
            }
        }
        LaunchedEffect(media.id, useMuxed) {
            val lSet = mutableSetOf<String>()
            val pSet = mutableSetOf<String>()
            val bSet = mutableSetOf<Int>()
            val cSet = mutableSetOf("Any")
            for (s in mediaSpecs.audioSpecs?: listOf()) {
                pSet.add(s.deliveryMethod?:"")
                lSet.add(s.audioLocale?:"")
                buildBRSet(s, bSet)
                if (s.codec != null) cSet.add(s.codec!!) else cSet.add("null")
            }
            locales = lSet.toList()
            prots = pSet.toList()
            bitRates = bSet.toList()
            codecs = cSet.toList()
            if (locale == null && locales.isNotEmpty()) locale = locales.firstOrNull { it in useLocales }
            codec = useCodex
            bitrate = useABPS
            val rSet = mutableSetOf<String>()
            val vcSet = mutableSetOf<String>()
            val vpSet = mutableSetOf<String>()
            val vSpecs = if (!useMuxed && client?.attributes?.hasSeparateAVs == true) mediaSpecs.videoSpecs else mediaSpecs.muxedSpecs
            for (s in vSpecs?: listOf()) {
                buildResoSet(s, rSet)
                s.codec?.let { vcSet.add(it) }
                s.deliveryMethod?.let { vpSet.add(it) }
            }
            vprots = vpSet.toList()
            resolutions = rSet.toList()
            vcodecs = vcSet.toList()
        }
        Popup(onDismissRequest = { onDismiss() }, alignment = Alignment.TopStart, offset = IntOffset(100, 100), properties = PopupProperties(focusable = true)) {
            Card(modifier = Modifier.width(300.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, borderColor), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(stringResource(R.string.stream_composer), color = textColor, style = MaterialTheme.typography.titleLarge)
                    if (client?.attributes?.hasVideo == true) Text(stringResource(R.string.use_muxed), color = if (useMuxed) Color.Yellow else textColor, modifier = Modifier.padding(top = 5.dp, start = 10.dp).clickable { useMuxed = !useMuxed })
                    if (client?.attributes?.hasVideo != true || (!useMuxed && client.attributes?.hasSeparateAVs == true)) {
                        var showLocales by remember { mutableStateOf(false) }
                        Text(stringResource(R.string.audio)+":", color = textColor, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.audio_stream_set_sum), color = textColor, style = MaterialTheme.typography.bodySmall)
                        Text("${stringResource(R.string.locale)}: ${locale ?: "null"}", color = textColor, modifier = Modifier.padding(top = 5.dp, start = 10.dp).clickable { showLocales = !showLocales })
                        if (showLocales && locales.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                            val langs = remember { curLocales.toList() }
                            for (index in langs.indices) {
                                FilterChip(label = { Text(langs[index]) }, selected = locale==langs[index], border = filterChipBorder(locale==langs[index]), onClick = {
                                    locale = langs[index]
                                    val pSet = mutableSetOf("Any")
                                    for (s in mediaSpecs.audioSpecs?:listOf()) buildProtSet(s, pSet)
                                    prots = pSet.toList()
                                    if (prot !in prots) prot = null
                                    val cSet = mutableSetOf("Any")
                                    for (s in mediaSpecs.audioSpecs?:listOf()) buildCodecSet(s, cSet)
                                    codecs = cSet.toList()
                                    if (codec !in codecs) codec = "Any"
                                    val bSet = mutableSetOf<Int>()
                                    for (s in mediaSpecs.audioSpecs?:listOf()) buildBRSet(s, bSet)
                                    bitRates = bSet.toList()
                                    bitrate = 0
                                    showLocales = false
                                })
                            }
                        }
                        var showProts by remember { mutableStateOf(false) }
                        Text("${stringResource(R.string.protocol)}: $prot", color = textColor, modifier = Modifier.padding(top = 5.dp, start = 10.dp).clickable { showProts = !showProts })
                        if (showProts && prots.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                for (index in prots.indices) {
                                    FilterChip(label = { Text(prots[index]) }, selected = prot==prots[index], border = filterChipBorder(prot==prots[index]), onClick = {
                                        prot = prots[index]
                                        val cSet = mutableSetOf("Any")
                                        for (s in mediaSpecs.audioSpecs?:listOf()) buildCodecSet(s, cSet)
                                        codecs = cSet.toList()
                                        if (codec !in codecs) codec = "Any"
                                        val bSet = mutableSetOf<Int>()
                                        for (s in mediaSpecs.audioSpecs?:listOf()) buildBRSet(s, bSet)
                                        bitRates = bSet.toList()
                                        bitrate = 0
                                        showProts = false
                                    })
                                }
                            }
                        }
                        var showCodecs by remember { mutableStateOf(false) }
                        Text("${stringResource(R.string.codec)}: $codec", color = textColor, modifier = Modifier.padding(top = 5.dp, start = 10.dp).clickable { showCodecs = !showCodecs })
                        if (showCodecs && codecs.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                for (index in codecs.indices) {
                                    FilterChip(label = { Text(codecs[index]) }, selected = codec==codecs[index], border = filterChipBorder(codec==codecs[index]), onClick = {
                                        codec = codecs[index]
                                        val bSet = mutableSetOf<Int>()
                                        for (s in mediaSpecs.audioSpecs?:listOf()) buildBRSet(s, bSet)
                                        bitRates = bSet.toList()
                                        bitrate = 0
                                        showCodecs = false
                                    })
                                }
                            }
                        }
                        Text("${stringResource(R.string.bitrate)}: $bitrate", color = textColor, modifier = Modifier.padding(top = 5.dp, start = 10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 10.dp)) {
                            for (index in bitRates.indices) {
                                FilterChip(label = { Text(bitRates[index].toString()) }, selected = bitrate==bitRates[index], border = filterChipBorder(bitrate==bitRates[index]), onClick = {
                                    bitrate = bitRates[index]
                                    reset = true
                                })
                            }
                        }
                    }
                    if (useMuxed || needVideo) {
                        Text(stringResource(R.string.video)+":", color = textColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 3.dp))
                        Text(stringResource(R.string.video_stream_set_sum), color = textColor, style = MaterialTheme.typography.bodySmall)
                        var showProts by remember { mutableStateOf(false) }
                        Text("${stringResource(R.string.protocol)}: $vprot", color = textColor, modifier = Modifier.padding(top = 5.dp, start = 10.dp).clickable { showProts = !showProts })
                        if (showProts && vprots.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                for (index in vprots.indices) {
                                    FilterChip(label = { Text(vprots[index]) }, selected = vprot==vprots[index], border = filterChipBorder(vprot==vprots[index]), onClick = {
                                        vprot = vprots[index]
                                        val vSpecs = if (useMuxed) mediaSpecs.muxedSpecs else mediaSpecs.videoSpecs
                                        Logd(TAG) { "shoose vprot: $vprot ${vSpecs?.size}"}
                                        val cSet = mutableSetOf("Any")
                                        for (s in vSpecs?:listOf()) buildVCodecSet(s, cSet)
                                        vcodecs = cSet.toList()
                                        if (vcodec !in vcodecs) vcodec = "Any"
                                        val rSet = mutableSetOf<String>()
                                        for (s in vSpecs?:listOf()) buildResoSet(s, rSet)
                                        resolutions = rSet.toList()
                                        resolution = null
                                        showProts = false
                                    })
                                }
                            }
                        }
                        var showCodecs by remember { mutableStateOf(false) }
                        Text("${stringResource(R.string.video)} ${stringResource(R.string.codec)}: $vcodec", color = textColor, modifier = Modifier.padding(top = 5.dp, start = 10.dp).clickable { showCodecs = !showCodecs })
                        if (showCodecs && vcodecs.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                                for (index in vcodecs.indices) {
                                    FilterChip(label = { Text(vcodecs[index]) }, selected = vcodec==vcodecs[index], border = filterChipBorder(vcodec==vcodecs[index]), onClick = {
                                        vcodec = vcodecs[index]
                                        val rSet = mutableSetOf<String>()
                                        for (s in mediaSpecs.videoSpecs?:listOf()) buildResoSet(s, rSet)
                                        resolutions = rSet.toList()
                                        resolution = null
                                        showCodecs = false
                                    })
                                }
                            }
                        }
                        Text("${stringResource(R.string.resolution)}: $resolution", color = textColor, modifier = Modifier.padding(top = 5.dp, start = 10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(10.dp)) {
                            for (index in resolutions.indices) {
                                FilterChip(label = { Text(resolutions[index]) }, selected = resolution==resolutions[index], border = filterChipBorder(resolution==resolutions[index]), onClick = {
                                    resolution = resolutions[index]
                                    useVideo = true
                                    reset = true
                                })
                            }
                        }
                    }
                    if (reset) Button(onClick = {
                        setAudioStream(locale, codec, bitrate)
                        resolution?.let { useResolution = it }
                        useVCodex = vcodec
                        confirmCB(useVideo, useMuxed)
                        reset = false
                        onDismiss()
                    }) { Text(stringResource(R.string.confirm_label)) }
                }
            }
        }
    }

    companion object {

        private const val TAG = "AVSpecs"

        private val audioSpecsCache = LruCache<Long, List<AudioSpec>>(10)
        private val videoSpecsCache = LruCache<Long, List<VideoSpec>>(10)
        private val muxedSpecsCache = LruCache<Long, List<VideoSpec>>(10)
    }
}