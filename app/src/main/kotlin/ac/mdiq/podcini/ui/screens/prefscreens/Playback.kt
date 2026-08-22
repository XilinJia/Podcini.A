package ac.mdiq.podcini.ui.screens.prefscreens

import ac.mdiq.podcini.PodciniApp.Companion.forceRestart
import ac.mdiq.podcini.R
import ac.mdiq.podcini.playback.forcePlaybackReset
import ac.mdiq.podcini.sources.clientsHaveMultiQ
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.prefStreamOverDownload
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.streamingCacheSizeMB
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.storage.specs.AVQuality
import ac.mdiq.podcini.storage.specs.VideoMode
import ac.mdiq.podcini.ui.compose.CommonConfirmAttrib
import ac.mdiq.podcini.ui.compose.CustomTextStyles
import ac.mdiq.podcini.ui.compose.NumberEditor
import ac.mdiq.podcini.ui.compose.SetAVQuality
import ac.mdiq.podcini.ui.compose.TitleSummaryActionColumn
import ac.mdiq.podcini.ui.compose.TitleSummarySwitchRow
import ac.mdiq.podcini.ui.compose.VideoModeDialog
import ac.mdiq.podcini.ui.compose.commonConfirms
import ac.mdiq.podcini.ui.compose.textColor
import ac.mdiq.podcini.utils.Logd
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class PrefHardwareForwardButton(val res: Int, val res1: Int) {
    FF(R.string.button_action_fast_forward, R.string.keycode_media_fast_forward),
    RW(R.string.button_action_rewind, R.string.keycode_media_rewind),
    SKIP(R.string.button_action_skip_episode, R.string.keycode_media_next),
    START(R.string.button_action_restart_episode, R.string.keycode_media_previous);
}

private const val TAG = "PlaybackScreen"
@Composable
fun PlaybackScreen() {
    val context by rememberUpdatedState(LocalContext.current)
    val appPrefs by appPrefsFlow!!.collectAsStateWithLifecycle()

    BackHandler(enabled = true) { pfBackStack.removeLastOrNull() }

    var selectedRingtoneUri by remember { mutableStateOf(appPrefs.ringToneUriString?.toUri()) }
    var ringtoneName by remember { mutableStateOf(appPrefs.ringToneName) }
    val ringtonePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data ?: return@rememberLauncherForActivityResult
            val uri = IntentCompat.getParcelableExtra(intent, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) ?: return@rememberLauncherForActivityResult
//            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedRingtoneUri = uri
            ringtoneName = RingtoneManager.getRingtone(context, uri).getTitle(context) ?: "Silent"
            upsertBlk(appPrefs) {
                it.ringToneName = ringtoneName
                it.ringToneUriString = uri.toString()
            }
            Logd(TAG, "ringtoneName $ringtoneName")
        }
    }

    val appAttribs by appAttribsFlow!!.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp).verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface)) {
        Text(stringResource(R.string.interruptions), color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        TitleSummarySwitchRow(R.string.pref_pauseOnHeadsetDisconnect_title, R.string.pref_pauseOnDisconnect_sum, appPrefs.pauseOnHeadsetDisconnect) {
            upsertBlk(appPrefs) { p-> p.pauseOnHeadsetDisconnect = it }
        }
        if (appPrefs.pauseOnHeadsetDisconnect) {
            TitleSummarySwitchRow(R.string.pref_unpauseOnHeadsetReconnect_title, R.string.pref_unpauseOnHeadsetReconnect_sum, appPrefs.unpauseOnHeadsetReconnect) {
                upsertBlk(appPrefs) { p-> p.unpauseOnHeadsetReconnect = it }
            }
            TitleSummarySwitchRow(R.string.pref_unpauseOnBluetoothReconnect_title, R.string.pref_unpauseOnBluetoothReconnect_sum, appPrefs.unpauseOnBluetoothReconnect) {
                upsertBlk(appPrefs) { p-> p.unpauseOnBluetoothReconnect = it }
            }
        }
        HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        Text(stringResource(R.string.playback_control), color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 15.dp))
        if (appAttribs.langSet.size > 1) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp)) {
                Text(stringResource(R.string.preferred_languages), color = textColor, style = CustomTextStyles.titleCustom, fontWeight = FontWeight.Bold)
                var showIcon by remember { mutableStateOf(false) }
                var newName by remember { mutableStateOf(appAttribs.langsPreferred.joinToString(", ")) }
                TextField(value = newName, singleLine = true, label = { Text("Case sensitive. Separate with ,", style = MaterialTheme.typography.bodySmall) },
                    onValueChange = {
                        newName = it
                        showIcon =  true
                    },
                    trailingIcon = {
                        if (showIcon) Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings icon", modifier = Modifier.size(30.dp).clickable(
                            onClick = {
                                runOnIOScope { upsert(appAttribs) { att->
                                    att.langsPreferred.clear()
                                    att.langsPreferred.addAll(newName.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                                } }
                                forcePlaybackReset = true
                                showIcon =  false
                            }))
                    })
                val langs = remember { appAttribs.langSet.joinToString(", ") }
                Text("Candidates: $langs", color = textColor, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.preferred_languages_sum), color = textColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        TitleSummarySwitchRow(R.string.use_ring_tone, R.string.use_ring_tone_sum, appPrefs.useRingTone) {
            upsertBlk(appPrefs) { p-> p.useRingTone = it }
        }
        if (appPrefs.useRingTone) {
            Column(Modifier.padding(start = 10.dp)) {
                Text("Ringtone: $ringtoneName", modifier = Modifier.padding(start = 16.dp))
                TitleSummaryActionColumn(R.string.select_ring_tone, R.string.select_ring_tone_sum) {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Tone")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedRingtoneUri)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    }
                    ringtonePickerLauncher.launch(intent)
                }
                TitleSummarySwitchRow(R.string.disable_ring_tone_on_music, R.string.disable_ring_tone_on_music_sum, appPrefs.disableRingToneOnMusic) {
                    upsertBlk(appPrefs) { p-> p.disableRingToneOnMusic = it }
                }
            }
        }

        var prefStreaming by remember { mutableStateOf(prefStreamOverDownload) }
        TitleSummarySwitchRow(R.string.pref_stream_over_download_title, R.string.pref_stream_over_download_sum, appPrefs.streamOverDownload) {
            prefStreaming = it
            upsertBlk(appPrefs) { p-> p.streamOverDownload = it }
        }
        if (prefStreaming) Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pref_stream_cache), color = textColor, style = CustomTextStyles.titleCustom, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                NumberEditor(streamingCacheSizeMB, label = "MD", modifier = Modifier.weight(0.6f)) {
                    streamingCacheSizeMB = it
                    forceRestart()
                }
            }
            Text(stringResource(R.string.pref_stream_cache_sum), color = textColor, style = MaterialTheme.typography.bodySmall)
        }
        val hasMultiQ = remember { clientsHaveMultiQ() }
        if (hasMultiQ) {
            var audioQuality by remember { mutableStateOf(  AVQuality.fromCode(appPrefs.audioQuality)) }
            var videoQuality by remember { mutableStateOf(AVQuality.fromCode(appPrefs.videoQuality)) }

            var showAudioDialog by remember { mutableStateOf(false) }
            if (showAudioDialog) SetAVQuality(selectedOption = audioQuality.tag, showGlobal = false, onDismiss = { showAudioDialog = false }) { type ->
                audioQuality = type
                upsertBlk(appPrefs) { it.audioQuality  = audioQuality.code }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.pref_feed_audio_quality), style = CustomTextStyles.titleCustom, color = textColor, modifier = Modifier.clickable { showAudioDialog = true })
                    Spacer(modifier = Modifier.width(30.dp))
                    Text(audioQuality.tag, style = MaterialTheme.typography.bodyMedium, color = textColor)
                }
                Text(text = stringResource(R.string.pref_audio_quality_sum), style = MaterialTheme.typography.bodyMedium, color = textColor)
            }
            var showVideoDialog by remember { mutableStateOf(false) }
            if (showVideoDialog) SetAVQuality(selectedOption = videoQuality.tag, showGlobal = false, onDismiss = { showVideoDialog = false }) { type->
                videoQuality = type
                upsertBlk(appPrefs) { it.videoQuality  = videoQuality.code }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.pref_feed_video_quality), style = CustomTextStyles.titleCustom, color = textColor, modifier = Modifier.clickable { showVideoDialog = true })
                    Spacer(modifier = Modifier.width(30.dp))
                    Text(videoQuality.tag, style = MaterialTheme.typography.bodyMedium, color = textColor)
                }
                Text(text = stringResource(R.string.pref_video_quality_sum), style = MaterialTheme.typography.bodyMedium, color = textColor)
            }
            TitleSummarySwitchRow(R.string.pref_low_quality_on_mobile_title, R.string.pref_low_quality_on_mobile_sum, appPrefs.lowQualityOnMobile) {
                upsertBlk(appPrefs) { p-> p.lowQualityOnMobile = it }
            }
        }
        TitleSummarySwitchRow(R.string.pref_use_adaptive_progress_title, R.string.pref_use_adaptive_progress_sum, appPrefs.useAdaptiveProgressUpdate) {
            upsertBlk(appPrefs) { p-> p.useAdaptiveProgressUpdate = it }
        }
        var showVideoModeDialog by remember { mutableStateOf(false) }
        if (showVideoModeDialog) VideoModeDialog(initMode =  VideoMode.fromCode(appPrefs.videoPlaybackMode), isDemuxed = false, muxed = appPrefs.useMuxedVideo, onDismiss = { showVideoModeDialog = false }) { mode, muxed ->
            upsertBlk(appPrefs) {
                it.videoPlaybackMode = mode.code
//                it.useMuxedVideo = muxed  // not used now
            }
        }
        TitleSummaryActionColumn(R.string.pref_playback_video_mode, R.string.pref_playback_video_mode_sum) { showVideoModeDialog = true }

        HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        Text(stringResource(R.string.reassign_hardware_buttons), color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 15.dp))
        var showHardwareForwardButtonOptions by remember { mutableStateOf(false) }
        var tempFFSelectedOption by remember { mutableIntStateOf(R.string.keycode_media_fast_forward) }
        TitleSummaryActionColumn(R.string.pref_hardware_forward_button_title, R.string.pref_hardware_forward_button_summary) { showHardwareForwardButtonOptions = true }
        if (showHardwareForwardButtonOptions) {
            AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = { showHardwareForwardButtonOptions = false },
                title = { Text(stringResource(R.string.pref_hardware_forward_button_title), style = CustomTextStyles.titleCustom) },
                text = {
                    Column(modifier = Modifier) {
                        PrefHardwareForwardButton.entries.forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(2.dp).clickable { tempFFSelectedOption = option.res1 }) {
                                Checkbox(checked = tempFFSelectedOption == option.res1, onCheckedChange = { tempFFSelectedOption = option.res1 })
                                Text(stringResource(option.res), modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        upsertBlk(appPrefs) { it.hardwareForwardButton = tempFFSelectedOption.toString() }
                        showHardwareForwardButtonOptions = false
                    }) { Text(text = "OK") }
                },
                dismissButton = { TextButton(onClick = { showHardwareForwardButtonOptions = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        var showHardwarePreviousButtonOptions by remember { mutableStateOf(false) }
        var tempPRSelectedOption by remember { mutableIntStateOf(R.string.keycode_media_rewind) }
        TitleSummaryActionColumn(R.string.pref_hardware_previous_button_title, R.string.pref_hardware_previous_button_summary) { showHardwarePreviousButtonOptions = true }
        if (showHardwarePreviousButtonOptions) {
            AlertDialog(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraLarge), onDismissRequest = { showHardwarePreviousButtonOptions = false },
                title = { Text(stringResource(R.string.pref_hardware_previous_button_title), style = CustomTextStyles.titleCustom) },
                text = {
                    Column(modifier = Modifier) {
                        PrefHardwareForwardButton.entries.forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(2.dp).clickable { tempPRSelectedOption = option.res1 }) {
                                Checkbox(checked = tempPRSelectedOption == option.res1, onCheckedChange = { tempPRSelectedOption = option.res1 })
                                Text(stringResource(option.res), modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        upsertBlk(appPrefs) { it.hardwarePreviousButton = tempPRSelectedOption.toString()}
                        showHardwarePreviousButtonOptions = false
                    }) { Text(text = "OK") }
                },
                dismissButton = { TextButton(onClick = { showHardwarePreviousButtonOptions = false }) { Text(stringResource(R.string.cancel_label)) } }
            )
        }
        HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        Text(stringResource(R.string.queue_label) + "/" + stringResource(R.string.episodes_label), color = textColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 15.dp))
        TitleSummarySwitchRow(R.string.pref_enqueue_downloaded_title, R.string.pref_enqueue_downloaded_summary, appPrefs.enqueueDownloaded) {
            upsertBlk(appPrefs) { p-> p.enqueueDownloaded = it }
        }

        TitleSummarySwitchRow(R.string.pref_skip_keeps_episodes_title, R.string.pref_skip_keeps_episodes_sum, appPrefs.skipKeepsEpisode) {
            upsertBlk(appPrefs) { p-> p.skipKeepsEpisode = it }
        }
        TitleSummarySwitchRow(R.string.pref_mark_played_removes_from_queue_title, R.string.pref_mark_played_removes_from_queue_sum, appPrefs.removeFromQueueMarkPlayed) {
            upsertBlk(appPrefs) { p-> p.removeFromQueueMarkPlayed = it }
        }

        TitleSummarySwitchRow(R.string.auto_delete, R.string.pref_auto_delete_sum, appPrefs.autoDelete) {
            upsertBlk(appPrefs) { p-> p.autoDelete = it }
        }
        var blockAutoDeleteLocal by remember { mutableStateOf(true) }
        TitleSummarySwitchRow(R.string.pref_auto_local_delete_title, R.string.pref_auto_local_delete_sum, appPrefs.autoDeleteLocal) {
            if (blockAutoDeleteLocal && it) {
                commonConfirms.add(CommonConfirmAttrib(
                    title = "",
                    message = context.getString(R.string.pref_auto_local_delete_dialog_body),
                    confirmRes = R.string.yes,
                    cancelRes = R.string.cancel_label,
                    onConfirm = {
                        blockAutoDeleteLocal = false
                        upsertBlk(appPrefs) { p-> p.autoDeleteLocal = it }
                        blockAutoDeleteLocal = true
                    }))
            }
        }
        TitleSummarySwitchRow(R.string.pref_keeps_important_episodes_title, R.string.pref_keeps_important_episodes_sum, appPrefs.favoriteKeepsEpisode) {
            upsertBlk(appPrefs) { p-> p.favoriteKeepsEpisode = it }
        }
        TitleSummarySwitchRow(R.string.pref_delete_removes_from_queue_title, R.string.pref_delete_removes_from_queue_sum, appPrefs.deleteRemovesFromQueue) {
            upsertBlk(appPrefs) { p-> p.deleteRemovesFromQueue = it }
        }
    }
}
