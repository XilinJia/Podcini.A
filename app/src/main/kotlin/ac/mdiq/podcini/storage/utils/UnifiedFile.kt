package ac.mdiq.podcini.storage.utils

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.ktor.utils.io.charsets.Charset
import kotlinx.io.IOException
import kotlinx.io.files.FileNotFoundException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import java.io.File

private const val TAG = "UnifiedFile"

interface UnifiedFile {
    val name: String

    val absPath: String

    val extension: String?
        get() {
            val name = this.name
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return null
            return name.substring(dot + 1)
        }

    suspend fun exists(): Boolean

    suspend fun delete(): Boolean

    fun isDirectory(): Boolean
    suspend fun listChildren(): List<UnifiedFile>

    suspend fun createDirectory(name: String): UnifiedFile
    suspend fun createFile(mimeType: String, name: String): UnifiedFile

    //    suspend fun createFile(uri: Uri): UnifiedFile?

    suspend fun createFile(): UnifiedFile

    fun source(): Source
    fun sink(append: Boolean = false): Sink
    suspend fun size(): Long?

    suspend fun readBytes(): ByteArray = source().buffer().use { it.readByteArray() }

    suspend fun readString(charset: Charset = Charsets.UTF_8): String = source().buffer().use { it.readString(charset) }

    suspend fun writeBytes(bytes: ByteArray) = sink(append = false).buffer().use { it.write(bytes) }

    suspend fun appendBytes(bytes: ByteArray) = sink(append = true).buffer().use { it.write(bytes) }

    suspend fun writeString(string: String, charset: Charset = Charsets.UTF_8) {
        sink(append = false).buffer().use { sink ->
            sink.writeString(string, charset)
            sink.flush()
        }
    }

    suspend fun copyTo(target: UnifiedFile) {
        if (!target.exists()) target.createFile()
        source().buffer().use { src -> target.sink().buffer().use { dst -> dst.writeAll(src) } }
    }

    suspend fun moveTo(target: UnifiedFile) {
        copyTo(target)
        delete()
    }
}

class PathFile(
    val path: Path,
    val fs: FileSystem = FileSystem.SYSTEM
) : UnifiedFile {

    override val name: String
        get() = path.name

    override val absPath: String = path.toString()

    override suspend fun exists(): Boolean = fs.exists(path)

    override fun isDirectory(): Boolean = fs.metadata(path).isDirectory

    override suspend fun listChildren(): List<UnifiedFile> = fs.list(path).map { PathFile(it) }

    override suspend fun delete(): Boolean = try { fs.delete(this.path);true } catch (e: Throwable) { false }

    override suspend fun createFile(mimeType: String, name: String): UnifiedFile {
        if (!fs.exists(path)) throw IllegalStateException("Parent path does not exist: $path")
        if (!fs.metadata(path).isDirectory) throw IllegalStateException("Cannot create file under a file: $path")
        val newPath: Path = path / name
        if (!fs.exists(newPath)) fs.sink(newPath).use { }
        return PathFile(newPath, fs)
    }

    override suspend fun createFile(): UnifiedFile {
        writeString("")
        return this
    }

    override suspend fun createDirectory(name: String): UnifiedFile {
        if (!fs.exists(path) || !fs.metadata(path).isDirectory) throw IllegalStateException("Cannot create a subdirectory under a non-directory path: $path")
        val newDirPath = path / name
        fs.createDirectories(newDirPath)
        return PathFile(newDirPath, fs)
    }

    override fun source(): Source = fs.source(path)

    override fun sink(append: Boolean): Sink = if (append) fs.appendingSink(path) else fs.sink(path)

    override suspend fun size(): Long? = try { fs.metadata(path).size } catch (e: Exception) { null }
}

class ContentUriFile(
    val uri: Uri,
    val context: Context = getAppContext()
) : UnifiedFile {

    val docFile: DocumentFile? = if (isTreeRoot) DocumentFile.fromTreeUri(context, uri) else DocumentFile.fromSingleUri(context, uri)

    val isTreeRoot: Boolean
        get() {
            return uri in persistedTrees
            //            return try { DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(mediaDir.toAndroidUri()) } catch (e: Exception) { false }
        }

    fun queryMimeType(): String? =
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun findSavedRoot(): Uri? {
//        Logd(TAG) { "findSavedRoot uri: $uri" }
        if (tempRoottree != null && uri.toString().startsWith(tempRoottree.toString())) return tempRoottree
//        persistedTrees.forEach { Logd(TAG) { "saved root: $it" } }
        val targetTreeId = DocumentsContract.getTreeDocumentId(uri)
        return persistedTrees.find { DocumentsContract.getTreeDocumentId(it) == targetTreeId }
        //        return persistedTrees.find { uri.toString().startsWith(it.toString()) }
    }

    override val name: String
        get() = docFile?.name ?: "unknown"

    override val absPath: String = uri.toString()

    override suspend fun exists(): Boolean {
        return isTreeRoot || try {
            val df = if (DocumentsContract.isDocumentUri(context, uri)) DocumentFile.fromSingleUri(context, uri) else DocumentFile.fromTreeUri(context, uri)
            df?.exists() == true
        } catch (e: FileNotFoundException) { false } catch (e: IllegalArgumentException) { false }
    }

    override suspend fun size(): Long? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index != -1) cursor.getLong(index) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    override fun isDirectory(): Boolean {
        if (isTreeRoot) return true
        val projection = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val mime = c.getString(0)
                return mime == DocumentsContract.Document.MIME_TYPE_DIR
            }
        }
        return false
    }

    override suspend fun listChildren(): List<UnifiedFile> {
        val rootUri = findSavedRoot() ?: uri
//        Logd(TAG) { "listChildren rootUri: $rootUri" }
        val result = mutableListOf<Uri>()
        val parentId = if (rootUri == uri) DocumentsContract.getTreeDocumentId(rootUri) else DocumentsContract.getDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentId)
        context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, childId)
                if (childUri == uri) continue
                result.add(childUri)
            }
        }
        return result.map { ContentUriFile(it, context) }
    }

    override fun source(): Source {
        val inputStream = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open input stream for $uri")
        return inputStream.source()
    }

    override fun sink(append: Boolean): Sink {
        val mode = if (append) "wa" else "w"
        val outputStream = context.contentResolver.openOutputStream(uri, mode) ?: throw IllegalStateException("Cannot open output stream for $uri")
        return outputStream.sink()
    }

    override suspend fun delete(): Boolean = docFile?.delete() ?: false

    override suspend fun createFile(mimeType: String, name: String): UnifiedFile {
        val rootUri = findSavedRoot() ?: uri
//        Logd(TAG) { "createFile rootUri: $rootUri" }
        val newUri = try {
            val parentId = if (rootUri == uri) DocumentsContract.getTreeDocumentId(rootUri) else DocumentsContract.getDocumentId(uri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
            DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, name)
        } catch (e: Exception) { throw IOException("failed creating file $name under $uri") }
        return ContentUriFile(newUri!!)
    }

    override suspend fun createFile(): UnifiedFile {
        val docId = DocumentsContract.getDocumentId(uri)
        val parentId = if (docId.contains("/")) docId.substringBeforeLast("/") else DocumentsContract.getTreeDocumentId(uri)
        val name = docId.substringAfterLast('/')
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
        val newFileUri = try { DocumentsContract.createDocument(context.contentResolver, parentUri, "application/octet-stream", name) } catch (e: Exception) { throw IOException("failed creating file at $uri") }
        return ContentUriFile(newFileUri!!)
    }

    override suspend fun createDirectory(name: String): UnifiedFile {
//        Logd(TAG) { "createDirectory $name $uri" }
        val rootUri = findSavedRoot() ?: uri
        val parentDocId: String = if (rootUri == uri) DocumentsContract.getTreeDocumentId(rootUri) else DocumentsContract.getDocumentId(uri)
        val parentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(uri, parentDocId)
        val newUri = DocumentsContract.createDocument(context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException("error creating directory $name under $uri")
        return ContentUriFile(newUri, context)
    }
}

operator fun UnifiedFile.div(child: String): UnifiedFile = when (this) {
    is PathFile -> PathFile(this.path / child, this.fs)
    is ContentUriFile -> {
        if (DocumentsContract.isTreeUri(this.uri)) {
            val parentId = DocumentsContract.getTreeDocumentId(this.uri)?: throw IOException("getting parentId for uri failed: $uri")
            val childUri = DocumentsContract.buildDocumentUriUsingTree(this.uri, "$parentId/$child")
            ContentUriFile(childUri)
        } else throw UnsupportedOperationException("Cannot append child to document URI")
    }
    else -> throw UnsupportedOperationException("Unsupported UnifiedFile type")
}

fun UnifiedFile.parent(): UnifiedFile? = when (this) {
    is PathFile -> this.path.parent?.let { PathFile(it, fs) }
    is ContentUriFile -> this.docFile?.parentFile?.let { ContentUriFile(it.uri, context) }
    else -> null
}

fun UnifiedFile.toAndroidUri(): Uri? = when (this) {
    is PathFile -> absPath.toSafeUri()
    is ContentUriFile -> uri
    else -> null
}

fun File.toUF(): UnifiedFile = PathFile(absolutePath.toPath())

fun Uri.toUF(): UnifiedFile {
//    Logd(TAG) { "Uri.toUF() $this" }
    return when (scheme) {
        "file" -> PathFile(this.path!!.toPath())
        "content" -> ContentUriFile(this)
        else -> PathFile(this.toString().toPath())
    }
}

fun String.toUF(): UnifiedFile {
//    Logd(TAG) { "String.toUF() $this" }
    val uri = try { this.toSafeUri() } catch (e: Exception) { null }
    return when (uri?.scheme) {
        null -> PathFile(this.toPath())
        "content" -> ContentUriFile(uri)
        "file" -> PathFile(uri.path!!.toPath())
        else -> error("Unsupported URI scheme: ${uri.scheme}")
    }
}

suspend fun deleteDirectoryRecursively(dir: UnifiedFile) {
//    Logd(TAG) { "deleteDirectoryRecursively ${dir.absPath}" }
    if (dir.isDirectory()) {
        for (file in dir.listChildren()) deleteDirectoryRecursively(file)
    }
    dir.delete()
}
