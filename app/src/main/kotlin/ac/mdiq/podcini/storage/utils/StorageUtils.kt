package ac.mdiq.podcini.storage.utils

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.storage.database.appPrefsFlow
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import ac.mdiq.podcini.utils.Logs
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import io.ktor.http.ContentType
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import java.io.File
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

private const val TAG: String = "StorageUtils"

const val MAX_FILENAME_LENGTH: Int = 242 // limited by CircleCI

private const val MD5_HEX_LENGTH = 32

private val validChars = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 _-").toCharArray()

val customMediaUriString: String
    get() = if (appPrefsFlow!!.value.useCustomMediaFolder) appPrefsFlow!!.value.customMediaUri else ""

val fs = FileSystem.SYSTEM

val internalDir: UnifiedFile
    get() = PathFile(getAppContext().filesDir.absolutePath.toPath())

val mediaDir: UnifiedFile
    get() {
        if (customMediaUriString.isNotBlank()) {
            val d = customMediaUriString.toUF()
            if (d.isDirectory()) {
                Logd(TAG) { "mediaDir: $customMediaUriString" }
                return d
            } else {
                Loge(TAG, "The chosen custom media folder is not valid: ${appPrefsFlow!!.value.customMediaUri}. Reset!")
                runOnIOScope {
                    upsert(appPrefsFlow!!.value) {
                        it.useCustomMediaFolder = false
                        it.customMediaUri = ""
                        it.customFolderUnavailable = true
                    }
                }
            }
        }
        Logd(TAG) { "mediaDir use internal media folder" }
        val mediaDir_ = getAppContext().getExternalFilesDir("media") ?: throw IllegalArgumentException("Invalid media dir")
        return mediaDir_.toUF()
    }

val clipsDir: UnifiedFile
    get() {
        var dir = mediaDir / "clips"
        runBlocking { if (!dir.exists()) dir = mediaDir.createDirectory("clips") }
        return dir
    }

val freeSpaceAvailable: Long
    get() {
        return if (customMediaUriString.isBlank()) {
            val stat = StatFs(internalDir.absPath)
            val availableBlocks = stat.availableBlocksLong
            val blockSize = stat.blockSizeLong
            availableBlocks * blockSize
        } else {
            val storageManager = getAppContext().getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val appSpecificDir = getAppContext().getExternalFilesDir(null) ?: return 0L
            val uuid = storageManager.getUuidForPath(appSpecificDir)
            storageManager.getAllocatableBytes(uuid)
        }
    }

val cacheDir: UnifiedFile = internalDir / "cache"

fun setupStorage() {
    CoroutineScope(Dispatchers.IO).launch {
        if (!cacheDir.exists()) internalDir.createDirectory("cache")
        // Create a .nomedia file to prevent scanning by the media scanner.
        val nomediaFile = mediaDir / ".nomedia"
        if (!nomediaFile.exists()) {
            try { mediaDir.createFile("", ".nomedia")
            } catch (e: Exception) {
                Logs(TAG, e, "failed creating .nomedia file")
                if (customMediaUriString.isNotBlank()) {
                    upsert(appPrefsFlow!!.value) {
                        it.useCustomMediaFolder = false
                        it.customMediaUri = ""
                        it.customFolderUnavailable = true
                    }
                }
            }
        }
    }
}

var tempRoottree: Uri? = null

val persistedTrees by lazy { getAppContext().contentResolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }.map { it.uri }.toMutableSet() }

fun findRootForUri(uri: Uri): Uri? {
    try {
        val childId = DocumentsContract.getDocumentId(uri)
        return persistedTrees.find { rootUri ->
            val treeId = DocumentsContract.getTreeDocumentId(rootUri)
            childId.startsWith(treeId) && rootUri.authority == uri.authority
        }
    } catch (e: Exception) { return null }
}

fun String.OKPath(): Path {
    Logd(TAG) { "String.OKPath() $this" }
    val uri = this.toSafeUri()
    return when (uri.scheme) {
        "file" -> uri.path?.toPath() ?: throw kotlinx.io.IOException("Invalid file URI")
        "content" -> this.toPath()
        else -> toPath()
    }
}

fun String.toSafeUri(): Uri {
    Logd(TAG) { "String.toSafeUri() $this" }
    return when {
        startsWith("content://") || startsWith("file://") || startsWith("http") -> this.toUri()
        startsWith("android_asset/") -> "file:///android_asset/${this.substring(14)}".toUri()
        startsWith("/") -> Uri.fromFile(File(this))
        else -> this.toUri()
    }
}

suspend fun quietlyDeleteFile(uri: Uri) {
    val file = uri.toUF()
    file.delete()
}

private val ACCENT_MAP: Map<Char, Char> = mapOf(
    'À' to 'A','Á' to 'A','Â' to 'A','Ã' to 'A','Ä' to 'A','Å' to 'A',
    'à' to 'a','á' to 'a','â' to 'a','ã' to 'a','ä' to 'a','å' to 'a',
    'Ç' to 'C','ç' to 'c',
    'È' to 'E','É' to 'E','Ê' to 'E','Ë' to 'E',
    'è' to 'e','é' to 'e','ê' to 'e','ë' to 'e',
    'Ì' to 'I','Í' to 'I','Î' to 'I','Ï' to 'I',
    'ì' to 'i','í' to 'i','î' to 'i','ï' to 'i',
    'Ñ' to 'N','ñ' to 'n',
    'Ò' to 'O','Ó' to 'O','Ô' to 'O','Õ' to 'O','Ö' to 'O',
    'ò' to 'o','ó' to 'o','ô' to 'o','õ' to 'o','ö' to 'o',
    'Ù' to 'U','Ú' to 'U','Û' to 'U','Ü' to 'U',
    'ù' to 'u','ú' to 'u','û' to 'u','ü' to 'u',
    'Ý' to 'Y','ý' to 'y','ÿ' to 'y',
    'Æ' to 'A','æ' to 'a','Ø' to 'O','ø' to 'o',
    'Þ' to 'T','þ' to 't','ß' to 's'
)

fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
    var filename: String? = null
    contentDisposition?.let { filename = Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
    if (filename == null) {
        val decodedUrl = try { Url(url).encodedPath } catch (e: Exception) { url }
        val lastSegment = decodedUrl.split('/').lastOrNull { it.isNotEmpty() }
        filename = lastSegment?.substringBefore('?') ?: "downloadfile"
    }
    val extension = mimeType?.let { ContentType.parse(it).contentSubtype }
    return if (!filename.contains(".") && extension != null) "$filename.$extension" else { filename }
}

/**
 * This method will return a new string that doesn't contain any illegal characters of the given string.
 */
fun generateFileName(input: String, replacement: Char = '-'): String {
    val buf = StringBuilder(input.length)
    for (ch in input) {
        val c = ACCENT_MAP[ch] ?: ch
        if (Character.isSpaceChar(c) && (buf.isEmpty() || Character.isSpaceChar(buf[buf.length - 1]))) continue
        if (validChars.contains(c)) buf.append(c)
    }
    val filename = buf.toString().trim { it <= ' ' }

    return when {
        filename.isEmpty() -> {
            val length = 8
            val sb = StringBuilder(length)
            for (i in 0 until length) sb.append(validChars[(Math.random() * validChars.size).toInt()])
            sb.toString()
        }
        filename.length >= MAX_FILENAME_LENGTH -> filename.take(MAX_FILENAME_LENGTH - MD5_HEX_LENGTH - 1) + "_" + md5(filename)
        else -> filename
    }
}

private fun md5(md5: String): String? {
    try {
        val md = MessageDigest.getInstance("MD5")
        val array = md.digest(md5.toByteArray(charset("UTF-8")))
        val sb = StringBuilder()
        for (b in array) sb.append(Integer.toHexString((b.toInt() and 0xFF) or 0x100).substring(1, 3))
        return sb.toString()
    } catch (e: NoSuchAlgorithmException) { return null
    } catch (e: Exception) { return null }
}

class AddLocalFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

class CountingSource(delegate: Source) : ForwardingSource(delegate) {
    var count: Long = 0
        private set

    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read > 0) count += read
        return read
    }
}

enum class MediaFormat {
    MP3,
    OGG,
    FLAC,
    M4A,
    WAV,
    UNKNOWN
}

fun peekFileFormat(fileSource: BufferedSource): MediaFormat {
    val peek = fileSource.peek()
    peek.request(12)
    val b = peek.buffer
    return when {
        b.size >= 3 && b.rangeEquals(0, "ID3".encodeUtf8()) -> MediaFormat.MP3
        b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> MediaFormat.MP3
        b.size >= 4 && b.rangeEquals(0, "OggS".encodeUtf8()) -> MediaFormat.OGG
        b.size >= 4 && b.rangeEquals(0, "fLaC".encodeUtf8()) -> MediaFormat.FLAC
        b.size >= 8 && b.rangeEquals(4, "ftyp".encodeUtf8()) -> MediaFormat.M4A
        b.size >= 12 && b.rangeEquals(0, "RIFF".encodeUtf8()) && b.rangeEquals(8, "WAVE".encodeUtf8()) -> MediaFormat.WAV
        else -> MediaFormat.UNKNOWN
    }
}

/**
 * On SDK<29, this class does not have a close method yet, so the app crashes when using try-with-resources.
 */
class MediaMetadataRetrieverCompat : MediaMetadataRetriever(), AutoCloseable {
    override fun close() {
        try { release() } catch (e: Exception) { Logs(TAG, e, "MediaMetadataRetriever failed") }
    }
}
