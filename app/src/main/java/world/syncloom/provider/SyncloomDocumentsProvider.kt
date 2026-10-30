package world.syncloom.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import uniffi.syncloom_core.FfiNode
import uniffi.syncloom_core.Session
import world.syncloom.R
import world.syncloom.SessionStore
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Shows Syncloom in the system file picker and Files app. Content is decrypted on demand. */
class SyncloomDocumentsProvider : DocumentsProvider() {
    private var session: Session? = null
    private val worker by lazy { HandlerThread("syncloom-docs").apply { start() }.let { Handler(it.looper) } }

    override fun onCreate() = true

    private fun session(): Session {
        session?.let { return it }
        val s = SessionStore(context!!).load() ?: throw IllegalStateException("Open Syncloom and sign in first")
        return Session.fromSecrets(s.server, s.token, s.secrets).also { session = it }
    }

    private fun nodeId(documentId: String) = if (documentId == ROOT_DOC) null else documentId

    private val rootColumns = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_SUMMARY)
    private val docColumns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: rootColumns)
        if (SessionStore(context!!).load() != null) {
            c.newRow().add(Root.COLUMN_ROOT_ID, "syncloom").add(Root.COLUMN_ICON, R.mipmap.ic_launcher).add(Root.COLUMN_TITLE, "Syncloom")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD).add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC).add(Root.COLUMN_SUMMARY, "End-to-end encrypted")
        }
        return c
    }

    private fun mime(n: FfiNode): String {
        if (n.kind == "folder") return Document.MIME_TYPE_DIR
        val ext = n.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun addNode(c: MatrixCursor, n: FfiNode) {
        var flags = 0
        if (n.access != "ro") {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            flags = if (n.kind == "folder") flags or Document.FLAG_DIR_SUPPORTS_CREATE else flags or Document.FLAG_SUPPORTS_WRITE
        }
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, n.id).add(Document.COLUMN_DISPLAY_NAME, n.name).add(Document.COLUMN_MIME_TYPE, mime(n))
            .add(Document.COLUMN_SIZE, n.size).add(Document.COLUMN_LAST_MODIFIED, if (n.mtimeMs > 0) n.mtimeMs else n.updatedAt).add(Document.COLUMN_FLAGS, flags)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: docColumns)
        if (documentId == ROOT_DOC) {
            c.newRow().add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC).add(Document.COLUMN_DISPLAY_NAME, "Syncloom").add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE).add(Document.COLUMN_SIZE, 0).add(Document.COLUMN_LAST_MODIFIED, null)
        } else addNode(c, session().get(documentId))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: docColumns)
        session().list(nodeId(parentDocumentId)).forEach { addNode(c, it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_DOC) return true
        var cur: String? = documentId
        while (cur != null) {
            if (cur == parentDocumentId) return true
            cur = runCatching { session().get(cur!!).parentId }.getOrNull()
        }
        return false
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val s = session()
        val tmp = File(context!!.cacheDir, "doc-${UUID.randomUUID()}")
        val writing = mode.contains('w')
        if (!writing || mode.contains('a') || !mode.contains('t')) {
            runCatching { s.downloadFile(documentId, null, tmp.path) }.onFailure { if (!writing) throw FileNotFoundException(it.message) }
        }
        if (!writing) return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        // writing: upload as a new version when the client closes the file
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.parseMode(mode), worker) { err ->
            if (err == null) {
                runCatching {
                    val head = s.get(documentId).headVersion
                    s.uploadVersion(documentId, head, tmp.path, System.currentTimeMillis())
                    context?.contentResolver?.notifyChange(android.provider.DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_DOC), null)
                }
            }
            tmp.delete()
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val s = session()
        val parent = nodeId(parentDocumentId)
        if (mimeType == Document.MIME_TYPE_DIR) return s.createFolder(parent, displayName).id
        val empty = File.createTempFile("new", null, context!!.cacheDir)
        try { return s.uploadNewFile(parent, displayName, empty.path, System.currentTimeMillis(), false).id } finally { empty.delete() }
    }

    override fun deleteDocument(documentId: String) = session().trash(documentId)

    override fun renameDocument(documentId: String, displayName: String): String? {
        session().rename(documentId, displayName)
        return documentId
    }

    companion object {
        const val AUTHORITY = "world.syncloom.documents"
        const val ROOT_DOC = "root"
    }
}
