package ac.mdiq.podcini.storage.utils

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.R
import ac.mdiq.podcini.config.settings.ClipsTransporter
import ac.mdiq.podcini.config.settings.DatabaseTransporter
import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.upsertBlk
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import ac.mdiq.podcini.utils.dateStampFilename
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val autoBackupDirName = "Podcini-AutoBackups"

fun autoBackup() {
    val TAG = "autoBackup"
    val context = getAppContext()

    val isAutoBackup = appPrefsFlow!!.value.autoBackup
    if (!isAutoBackup) return
    val uriString = appPrefsFlow!!.value.autoBackupFolder
    if (uriString.isNullOrBlank()) {
        Loge(TAG, context.getString(R.string.auto_backup_folder_not_specified))
        return
    }

    Logd("autoBackup", "in autoBackup directory: $uriString")
    suspend fun deleteDirectoryAndContents(directory: UnifiedFile): Boolean {
        if (directory.isDirectory()) {
            directory.listChildren().forEach { file ->
                if (file.isDirectory()) deleteDirectoryAndContents(file)
                Logd(TAG, "deleting ${file.name}")
                try { file.delete() } catch (e: Throwable) {
                    Loge(TAG, e, "deleteDirectoryAndContents: failed to delete ${file.name} ")
                }
            }
        }
        try { return  directory.delete() } catch (e: Throwable) { Loge(TAG, e, "deleteDirectoryAndContents: failed to delete ${directory.name} ") }
        return false
    }

    val curTime = nowInMillis()
    if ((curTime - appPrefsFlow!!.value.autoBackupTimeStamp) > 3600000L * appPrefsFlow!!.value.autoBackupIntervall)
        CoroutineScope(Dispatchers.IO).launch {
            val uri = uriString.toSafeUri()
            val permissions = context.contentResolver.persistedUriPermissions.find { it.uri == uri }
            if (permissions != null && permissions.isReadPermission && permissions.isWritePermission) {
                val chosenDir = uri.toUF()
                if (chosenDir.exists()) {
                    val backedupDirs = mutableListOf<UnifiedFile>()
                    try {
                        chosenDir.listChildren().forEach { file ->
                            Logd(TAG, "file: $file")
                            if (file.isDirectory() && file.name.startsWith(autoBackupDirName, ignoreCase = true)) backedupDirs.add(file)
                        }
                        Logd(TAG, "backupDirs: ${backedupDirs.size}")
                        val limit = appPrefsFlow!!.value.autoBackupLimit
                        if (backedupDirs.size >= limit) {
                            backedupDirs.sortBy { it.name }
                            for (i in 0..(backedupDirs.size - limit)) deleteDirectoryAndContents(backedupDirs[i])
                        }
                        val dirName = dateStampFilename("$autoBackupDirName-%s")
                        val exportSubDir = chosenDir.createDirectory(dirName)
                        val realmFile = exportSubDir.createFile("application/octet-stream", "backup.realm")
                        DatabaseTransporter().exportToUri(realmFile)
                        ClipsTransporter("Podcini-Clips").fromMediaDirToUF(exportSubDir)
                        upsertBlk(appPrefsFlow!!.value) { it.autoBackupTimeStamp = curTime }
                    } catch (e: Exception) { Logs("autoBackup", e, "Error backing up") }
                } else Loge("autoBackup", context.getString(R.string.auto_backup_folder_not_available))
            } else Loge("autoBackup", "Uri permissions are no longer valid")
        }
}