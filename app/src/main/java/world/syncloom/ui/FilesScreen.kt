package world.syncloom.ui

import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.syncloom_core.FfiNode
import uniffi.syncloom_core.Session
import java.io.File
import java.util.UUID

private data class Crumb(val id: String?, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(session: Session, rootTitle: String = "Files", shared: Boolean = false, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf(Crumb(null, rootTitle)) }
    var items by remember { mutableStateOf<List<FfiNode>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<FfiNode?>(null) }
    var pendingDownload by remember { mutableStateOf<FfiNode?>(null) }
    val here = stack.last()
    val readOnlyHere = shared && here.id == null

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    suspend fun load() {
        try {
            val list = withContext(Dispatchers.IO) { if (shared && here.id == null) session.sharedWithMe() else session.list(here.id) }
            items = list.sortedWith(compareBy({ !it.isFolder() }, { it.name.lowercase() }))
            failure = null
        } catch (e: Throwable) { failure = e.message }
    }
    LaunchedEffect(here) { items = null; load() }
    LaunchedEffect(here) { while (true) { delay(20_000); load() } } // light polling keeps the list fresh
    BackHandler(stack.size > 1) { stack.removeLast() }

    fun io(block: suspend () -> Unit) = scope.launch {
        try { withContext(Dispatchers.IO) { block() }; load(); onChanged() } catch (e: Throwable) { toast(e.message ?: "Something went wrong") }
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) io {
            for (uri in uris) {
                val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
                val tmp = File(ctx.cacheDir, UUID.randomUUID().toString())
                ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                try { session.uploadNewFile(here.id, name, tmp.path, System.currentTimeMillis(), false) } finally { tmp.delete() }
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dest: Uri? ->
        val node = pendingDownload
        if (dest != null && node != null) io {
            val tmp = File(ctx.cacheDir, UUID.randomUUID().toString())
            try {
                session.downloadFile(node.id, null, tmp.path)
                ctx.contentResolver.openOutputStream(dest)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            } finally { tmp.delete() }
        }
        pendingDownload = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(here.name) },
                navigationIcon = { if (stack.size > 1) IconButton({ stack.removeLast() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!readOnlyHere) {
                        IconButton({ newFolder = true }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
                        IconButton({ upload.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.Upload, "Upload") }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val list = items
            when {
                list == null && failure == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                failure != null && list == null -> Text(failure!!, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                list.isNullOrEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Storage, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (shared) "Nothing shared with you yet" else "This folder is empty", style = MaterialTheme.typography.titleMedium)
                    if (!shared) Text("Tap the upload button to add files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { n ->
                        ListItem(
                            headlineContent = { Text(n.name, maxLines = 1) },
                            supportingContent = { Text(if (n.isFolder()) "Folder" else if (n.headVersion == null) "Uploading…" else Formatter.formatShortFileSize(ctx, n.size)) },
                            leadingContent = { Icon(if (n.isFolder()) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = if (n.isFolder()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = {
                                Box {
                                    IconButton({ menuFor = n }) { Icon(Icons.Filled.MoreVert, "More") }
                                    DropdownMenu(menuFor?.id == n.id, { menuFor = null }) {
                                        if (n.kind == "file") DropdownMenuItem({ Text("Download") }, { menuFor = null; pendingDownload = n; save.launch(n.name) }, leadingIcon = { Icon(Icons.Filled.Download, null) })
                                        if (n.access != "ro") DropdownMenuItem({ Text("Move to trash") }, { menuFor = null; io { session.trash(n.id) } }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                                    }
                                }
                            },
                            modifier = Modifier.clickable(enabled = n.isFolder()) { stack.add(Crumb(n.id, n.name)) }
                        )
                    }
                }
            }
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("Untitled folder") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ newFolder = false; io { session.createFolder(here.id, name.trim()) } }) { Text("Create") } },
            dismissButton = { TextButton({ newFolder = false }) { Text("Cancel") } }
        )
    }
}

fun FfiNode.isFolder() = kind == "folder"
