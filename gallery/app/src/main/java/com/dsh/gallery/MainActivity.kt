@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dsh.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Color(0xFF37474F), secondary = Color(0xFF546E7A))
            ) {
                Surface(modifier = Modifier.fillMaxSize()) { GalleryApp() }
            }
        }
    }
}

fun neededPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

fun hasPhotoPermission(context: android.content.Context): Boolean =
    neededPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

fun stubPhoto(id: Long): Photo = Photo(id, "", 0L, 0, 0, 0L, "image/jpeg", "", "", 0)

/** 是否已取得「所有檔案存取」→ 刪除時可跳過系統確認 */
fun hasAllFiles(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

private enum class GalleryScreen { FOLDERS, PHOTOS, TRASH, SETTINGS }

sealed class FolderEntry {
    abstract val key: String
    abstract val name: String
    data class Media(val folder: Folder) : FolderEntry() {
        override val key: String get() = "m:" + folder.bucketId
        override val name: String get() = folder.name
    }

    data class App(val doc: DocumentFile) : FolderEntry() {
        override val key: String get() = "a:" + doc.uri.toString()
        override val name: String get() = doc.name ?: "資料夾"
    }
}

private enum class PendingKind { TRASH, RESTORE, DELETE, MOVE }

private data class PendingAction(
    val kind: PendingKind,
    val photos: List<Photo> = emptyList(),
    val ids: Set<Long> = emptySet()
)

/** 兩指以上才處理縮放/旋轉/平移，單指事件不消耗，保留給翻頁與上下滑動 */
private suspend fun PointerInputScope.multiTouch(
    onTransform: (zoom: Float, rotation: Float, pan: Offset) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                val rotation = event.calculateRotation()
                val pan = event.calculatePan()
                if (zoom != 1f || rotation != 0f || pan != Offset.Zero) {
                    onTransform(zoom, rotation, pan)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

/** 單指上下滑動（不消耗事件，讓左右翻頁正常） */
private suspend fun PointerInputScope.verticalSwipe(
    thresholdPx: Float,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dy = 0f
        var dx = 0f
        var vertical = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            dy += change.positionChange().y
            dx += change.positionChange().x
            if (abs(dy) > thresholdPx * 0.3f && abs(dy) > abs(dx) * 1.6f) vertical = true
        }
        if (vertical && abs(dy) > thresholdPx) {
            if (dy < 0f) onUp() else onDown()
        }
    }
}

@Composable
fun GalleryApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPhotoPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasPhotoPermission(context) }

    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(neededPermissions()) }

    if (!granted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("需要相片權限", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("需要讀取手機照片才能瀏覽與編輯。", color = Color.Gray, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(neededPermissions()) }) { Text("授予權限") }
        }
    } else {
        GalleryRoot()
    }
}

@Composable
fun GalleryRoot() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()

    Trash.init(context)
    GallerySettings.init(context)

    var screen by remember { mutableStateOf(GalleryScreen.FOLDERS) }
    var mediaFolders by remember { mutableStateOf(listOf<Folder>()) }
    var appFolders by remember { mutableStateOf(listOf<FolderEntry.App>()) }
    var photos by remember { mutableStateOf(listOf<Photo>()) }
    var folder by remember { mutableStateOf<Folder?>(null) }
    var reload by remember { mutableStateOf(0) }
    var selectedPhotos by remember { mutableStateOf(setOf<Long>()) }
    var selectedFolders by remember { mutableStateOf(setOf<String>()) }
    var selectedTrash by remember { mutableStateOf(setOf<Long>()) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var askDelete by remember { mutableStateOf<List<Photo>?>(null) }
    var askNotAgain by remember { mutableStateOf(false) }
    var movePhotos by remember { mutableStateOf<List<Photo>?>(null) }
    var renameTarget by remember { mutableStateOf<FolderEntry.App?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf(-1) }
    var editing by remember { mutableStateOf<Photo?>(null) }
    var details by remember { mutableStateOf<Photo?>(null) }
    var saveAsPhoto by remember { mutableStateOf<Photo?>(null) }
    var loading by remember { mutableStateOf(true) }

    val confirmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && action != null) {
            when (action.kind) {
                PendingKind.TRASH -> {
                    scope.launch {
                        withContext(Dispatchers.IO) { Trash.add(context, resolver, action.photos) }
                        selectedPhotos = emptySet()
                        reload++
                        toast(context, "已移到垃圾桶")
                    }
                }
                PendingKind.RESTORE -> {
                    Trash.forget(action.ids)
                    selectedTrash = emptySet()
                    reload++
                    toast(context, "已還原 " + action.ids.size + " 張")
                }
                PendingKind.DELETE -> {
                    Trash.forget(action.ids)
                    selectedTrash = emptySet()
                    selectedPhotos = emptySet()
                    reload++
                    toast(context, "已刪除")
                }
                PendingKind.MOVE -> {
                    selectedPhotos = emptySet()
                    reload++
                    toast(context, "已搬移")
                }
            }
        }
    }

    val rootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                GallerySettings.applyRootTreeUri(uri.toString())
                reload++
                toast(context, "已授權：" + (DocStore.root(context)?.name ?: "資料夾"))
            }
        }
    }

    fun launch(sender: android.content.IntentSender, action: PendingAction, fail: String) {
        pending = action
        runCatching { confirmLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .onFailure { pending = null; toast(context, fail) }
    }

    fun trashPhotos(list: List<Photo>) {
        if (list.isEmpty()) return
        val req = mediaTrashRequest(resolver, list.map { GalleryRepo.uriOf(it) }, true)
        if (req == null) { toast(context, "無法移到垃圾桶"); return }
        launch(req.intentSender, PendingAction(PendingKind.TRASH, photos = list), "無法開啟垃圾桶確認")
    }

    fun deletePermanently(list: List<Photo>) {
        if (list.isEmpty()) return
        val req = runCatching {
            MediaStore.createDeleteRequest(resolver, list.map { GalleryRepo.uriOf(it) })
        }.getOrNull()
        if (req == null) { toast(context, "無法刪除"); return }
        launch(req.intentSender, PendingAction(PendingKind.DELETE), "無法開啟刪除確認")
    }

    fun deleteSilently(list: List<Photo>, toTrash: Boolean) {
        if (list.isEmpty()) return
        scope.launch {
            val moved = withContext(Dispatchers.IO) {
                var ok = 0
                if (toTrash) ok = Trash.addLocal(context, resolver, list)
                list.forEach { p -> runCatching { resolver.delete(GalleryRepo.uriOf(p), null, null) } }
                ok
            }
            selectedPhotos = emptySet()
            reload++
            toast(
                context,
                if (toTrash) "已移到垃圾桶 " + moved + " 張（可還原）" else "已刪除 " + list.size + " 張"
            )
        }
    }

    fun restoreSystemTrash(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val uris = Trash.items.filter { it.id in ids }.map { GalleryRepo.uriOf(stubPhoto(it.id)) }
        val req = mediaTrashRequest(resolver, uris, false)
        if (req == null) {
            toast(context, "無法還原（系統垃圾桶）")
            return
        }
        launch(req.intentSender, PendingAction(PendingKind.RESTORE, ids = ids), "無法開啟還原確認")
    }

    // 依設定決定刪除行為
    fun requestDelete(list: List<Photo>) {
        if (list.isEmpty()) return
        val silent = hasAllFiles(context)
        when (GallerySettings.deleteBehavior) {
            "trash" -> if (silent) deleteSilently(list, true) else trashPhotos(list)
            "permanent" -> if (silent) deleteSilently(list, false) else deletePermanently(list)
            else -> { askNotAgain = false; askDelete = list }
        }
    }

    fun restoreTrash(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val local = Trash.items.filter { it.id in ids && it.localPath.isNotBlank() }
        val system = Trash.items.filter { it.id in ids && it.localPath.isBlank() }
        if (local.isEmpty()) {
            restoreSystemTrash(system.map { it.id }.toSet())
            return
        }
        scope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                local.forEach { item ->
                    runCatching {
                        val file = java.io.File(item.localPath)
                        if (!file.exists()) return@runCatching
                        val bytes = file.readBytes()
                        val uri = insertBytes(resolver, item.bucket, item.name, item.mime, bytes)
                        if (uri != null) {
                            runCatching { file.delete() }
                            ok++
                        }
                    }
                }
            }
            Trash.forget(local.map { it.id }.toSet())
            selectedTrash = emptySet()
            reload++
            toast(context, "已還原 " + ok + " 張")
            if (system.isNotEmpty()) restoreSystemTrash(system.map { it.id }.toSet())
        }
    }

    fun deleteTrashForever(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val localIds = Trash.items.filter { it.id in ids && it.localPath.isNotBlank() }.map { it.id }.toSet()
        val systemIds = ids - localIds
        if (localIds.isNotEmpty()) {
            Trash.forget(localIds)
            selectedTrash = emptySet()
            reload++
            toast(context, "已永久刪除 " + localIds.size + " 張")
        }
        if (systemIds.isEmpty()) return
        val uris = Trash.items.filter { it.id in systemIds }.map { GalleryRepo.uriOf(stubPhoto(it.id)) }
        val req = runCatching { MediaStore.createDeleteRequest(resolver, uris) }.getOrNull()
        if (req == null) { toast(context, "無法永久刪除"); return }
        launch(req.intentSender, PendingAction(PendingKind.DELETE, ids = systemIds), "無法開啟刪除確認")
    }

    fun photosOfEntries(entries: List<FolderEntry>): List<Photo> {
        val media = mediaFolders.associateBy { it.bucketId }
        val result = mutableListOf<Photo>()
        entries.forEach { entry ->
            when (entry) {
                is FolderEntry.Media -> {
                    val f = media[entry.folder.bucketId] ?: return@forEach
                    result += GalleryRepo.query(resolver, f.bucketId)
                }
                is FolderEntry.App -> {
                    val name = entry.name
                    result += GalleryRepo.query(resolver, null).filter {
                        it.relativePath.trimEnd('/').endsWith("/" + name) || it.bucketName == name
                    }
                }
            }
        }
        return result
    }

    fun moveSelectedTo(target: DocumentFile, list: List<Photo>) {
        scope.launch {
            val copied = withContext(Dispatchers.IO) { DocStore.copyPhotos(context, target, list) }
            if (copied == 0) {
                toast(context, "搬移失敗（沒有寫入權限？）")
                return@launch
            }
            val req = runCatching {
                MediaStore.createDeleteRequest(resolver, list.map { GalleryRepo.uriOf(it) })
            }.getOrNull()
            if (req == null) {
                toast(context, "已複製 " + copied + " 張，但無法刪除原檔")
                reload++
            } else {
                launch(req.intentSender, PendingAction(PendingKind.MOVE, photos = list), "無法開啟刪除確認")
            }
        }
    }

    LaunchedEffect(reload) {
        loading = true
        mediaFolders = withContext(Dispatchers.IO) { GalleryRepo.folders(resolver) }
        appFolders = withContext(Dispatchers.IO) {
            DocStore.folders(context).map { FolderEntry.App(it) }
        }
        loading = false
    }
    LaunchedEffect(folder, reload) {
        val bucket = folder?.bucketId
        photos = withContext(Dispatchers.IO) { GalleryRepo.query(resolver, bucket) }
    }
    // 刪除後留在檢視器：位置自動夾到有效範圍，全部刪完才關閉
    LaunchedEffect(photos.size) {
        if (viewerIndex >= photos.size) viewerIndex = photos.size - 1
    }

    when (screen) {
        GalleryScreen.FOLDERS -> {
            val entries = mediaFolders.map { FolderEntry.Media(it) } + appFolders
            FoldersScreen(
                entries = entries,
                loading = loading,
                columns = GallerySettings.folderColumns,
                selected = selectedFolders,
                trashCount = Trash.items.size,
                onOpenFolder = { entry ->
                    when (entry) {
                        is FolderEntry.Media -> {
                            selectedPhotos = emptySet()
                            folder = entry.folder
                            screen = GalleryScreen.PHOTOS
                        }
                        is FolderEntry.App -> {
                            val match = mediaFolders.firstOrNull { it.name == entry.name }
                            if (match != null) {
                                selectedPhotos = emptySet()
                                folder = match
                                screen = GalleryScreen.PHOTOS
                            } else {
                                toast(context, "空資料夾（可用搬移或另存新檔放照片進來）")
                            }
                        }
                    }
                },
                onToggleSelect = { key ->
                    selectedFolders = if (key in selectedFolders) selectedFolders - key else selectedFolders + key
                },
                onClearSelect = { selectedFolders = emptySet() },
                onSelectAll = { selectedFolders = entries.map { it.key }.toSet() },
                onRename = { entry -> renameTarget = entry },
                onNewFolder = { newFolderOpen = true },
                onOpenTrash = { selectedTrash = emptySet(); screen = GalleryScreen.TRASH },
                onOpenSettings = { screen = GalleryScreen.SETTINGS },
                onRefresh = { reload++ },
                onShareSelected = {
                    scope.launch {
                        val list = withContext(Dispatchers.IO) {
                            photosOfEntries(entries.filter { it.key in selectedFolders })
                        }
                        if (list.isEmpty()) toast(context, "這些資料夾沒有照片") else sharePhotos(context, list)
                    }
                },
                onDeleteSelected = {
                    scope.launch {
                        val list = withContext(Dispatchers.IO) {
                            photosOfEntries(entries.filter { it.key in selectedFolders })
                        }
                        if (list.isEmpty()) toast(context, "這些資料夾沒有照片") else {
                            requestDelete(list)
                            selectedFolders = emptySet()
                        }
                    }
                },
                onMoveSelected = {
                    scope.launch {
                        val list = withContext(Dispatchers.IO) {
                            photosOfEntries(entries.filter { it.key in selectedFolders })
                        }
                        if (list.isEmpty()) toast(context, "這些資料夾沒有照片") else movePhotos = list
                    }
                }
            )
        }

        GalleryScreen.PHOTOS -> PhotosScreen(
            title = folder?.name ?: "",
            photos = photos,
            columns = GallerySettings.photoColumns,
            selected = selectedPhotos,
            onBack = { selectedPhotos = emptySet(); screen = GalleryScreen.FOLDERS },
            onToggle = { id ->
                selectedPhotos = if (id in selectedPhotos) selectedPhotos - id else selectedPhotos + id
            },
            onSelectAllToggle = {
                selectedPhotos = if (selectedPhotos.size == photos.size) emptySet() else photos.map { it.id }.toSet()
            },
            onOpen = { index -> viewerIndex = index },
            onShare = { sharePhotos(context, photos.filter { it.id in selectedPhotos }) },
            onMove = { movePhotos = photos.filter { it.id in selectedPhotos } },
            onDelete = { requestDelete(photos.filter { it.id in selectedPhotos }) },
            onClearSelection = { selectedPhotos = emptySet() }
        )

        GalleryScreen.TRASH -> TrashScreen(
            columns = GallerySettings.trashColumns,
            selected = selectedTrash,
            onBack = { selectedTrash = emptySet(); reload++; screen = GalleryScreen.FOLDERS },
            onToggle = { id ->
                selectedTrash = if (id in selectedTrash) selectedTrash - id else selectedTrash + id
            },
            onSelectAllToggle = {
                val total = Trash.items.map { it.id }.toSet()
                selectedTrash = if (selectedTrash.size == total.size) emptySet() else total
            },
            onClearSelection = { selectedTrash = emptySet() },
            onRestore = { restoreTrash(selectedTrash) },
            onDeleteForever = { deleteTrashForever(selectedTrash) },
            onEmptyAll = { deleteTrashForever(Trash.items.map { it.id }.toSet()) }
        )

        GalleryScreen.SETTINGS -> SettingsScreen(
            onBack = { reload++; screen = GalleryScreen.FOLDERS },
            onRequestRoot = { runCatching { rootPicker.launch(DocStore.pickRootIntent()) } }
        )
    }

    if (viewerIndex in 0 until photos.size) {
        ViewerScreen(
            photos = photos,
            startIndex = viewerIndex,
            onClose = { viewerIndex = -1 },
            onEdit = { editing = it },
            onDetails = { details = it },
            onShare = { sharePhotos(context, listOf(it)) },
            onDelete = { requestDelete(listOf(it)) },
            onSaveAs = { saveAsPhoto = it }
        )
    }

    editing?.let { photo ->
        EditorScreen(photo = photo, onClose = { editing = null; reload++ }, onSaved = { editing = null; reload++ })
    }

    details?.let { photo ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text("圖片資訊") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    InfoLine("檔名", photo.name)
                    InfoLine("尺寸", photo.width.toString() + " × " + photo.height.toString())
                    InfoLine("大小", GalleryRepo.formatSize(photo.sizeBytes))
                    InfoLine("拍攝／存入", GalleryRepo.formatDate(photo.dateAdded))
                    InfoLine("資料夾", photo.bucketName)
                    InfoLine("路徑", photo.relativePath)
                    InfoLine("格式", photo.mime)
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("關閉") } }
        )
    }

    // 刪除詢問（可勾「不再詢問」）
    askDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("刪除 " + list.size + " 張圖片") },
            text = {
                Column {
                    Text("要移到垃圾桶（可還原），還是直接永久刪除？", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = askNotAgain, onCheckedChange = { askNotAgain = it })
                        Text("不再詢問（記住這次的選擇）", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (askNotAgain) GallerySettings.applyDeleteBehavior("trash")
                    askDelete = null
                    if (askNotAgain && hasAllFiles(context)) deleteSilently(list, true) else trashPhotos(list)
                }) { Text("移到垃圾桶") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        if (askNotAgain) GallerySettings.applyDeleteBehavior("permanent")
                        askDelete = null
                        if (askNotAgain && hasAllFiles(context)) deleteSilently(list, false) else deletePermanently(list)
                    }) { Text("直接刪除", color = Color(0xFFC62828)) }
                    TextButton(onClick = { askDelete = null }) { Text("取消") }
                }
            }
        )
    }

    // 新建資料夾
    if (newFolderOpen) {
        NameDialog(
            title = "新建資料夾",
            hint = "資料夾名稱",
            initial = "",
            onDismiss = { newFolderOpen = false },
            onConfirm = { name ->
                newFolderOpen = false
                if (DocStore.root(context) == null) {
                    toast(context, "請先授權 Pictures 資料夾")
                    runCatching { rootPicker.launch(DocStore.pickRootIntent()) }
                } else {
                    val doc = DocStore.createFolder(context, name)
                    if (doc == null) toast(context, "建立失敗") else {
                        toast(context, "已建立：" + name)
                        reload++
                    }
                }
            }
        )
    }

    // 改名
    renameTarget?.let { entry ->
        NameDialog(
            title = "重新命名資料夾",
            hint = "新名稱",
            initial = entry.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                val ok = DocStore.rename(entry.doc, name)
                toast(context, if (ok) "已更名為 " + name else "改名失敗（權限不足）")
                reload++
            }
        )
    }

    // 搬移目標
    movePhotos?.let { list ->
        TargetFolderDialog(
            title = "搬移到資料夾",
            count = list.size,
            onDismiss = { movePhotos = null },
            onCreate = { name ->
                val doc = DocStore.createFolder(context, name)
                if (doc == null) {
                    toast(context, "請先授權 Pictures 資料夾")
                    runCatching { rootPicker.launch(DocStore.pickRootIntent()) }
                } else {
                    movePhotos = null
                    reload++
                    moveSelectedTo(doc, list)
                }
            },
            onPick = { doc ->
                movePhotos = null
                moveSelectedTo(doc, list)
            }
        )
    }

    // 另存新檔
    saveAsPhoto?.let { photo ->
        TargetFolderDialog(
            title = "另存新檔",
            count = 1,
            onDismiss = { saveAsPhoto = null },
            onCreate = { name ->
                val doc = DocStore.createFolder(context, name)
                if (doc == null) {
                    toast(context, "請先授權 Pictures 資料夾")
                    runCatching { rootPicker.launch(DocStore.pickRootIntent()) }
                } else {
                    saveAsPhoto = null
                    reload++
                    scope.launch {
                        val bmp = withContext(Dispatchers.IO) { GalleryRepo.loadBitmap(resolver, photo, 4000) }
                        if (bmp == null) toast(context, "讀取失敗") else {
                            val ok = withContext(Dispatchers.IO) {
                                saveBitmapIntoDoc(context, doc, bmp, photo.name.substringBeforeLast('.'))
                            }
                            toast(context, if (ok) "已另存新檔" else "另存失敗")
                            reload++
                        }
                    }
                }
            },
            onPick = { doc ->
                saveAsPhoto = null
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) { GalleryRepo.loadBitmap(resolver, photo, 4000) }
                    if (bmp == null) toast(context, "讀取失敗") else {
                        val ok = withContext(Dispatchers.IO) {
                            saveBitmapIntoDoc(context, doc, bmp, photo.name.substringBeforeLast('.'))
                        }
                        toast(context, if (ok) "已另存新檔" else "另存失敗")
                        reload++
                    }
                }
            }
        )
    }
}

// ---------------- 畫面 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    entries: List<FolderEntry>,
    loading: Boolean,
    columns: Int,
    selected: Set<String>,
    trashCount: Int,
    onOpenFolder: (FolderEntry) -> Unit,
    onToggleSelect: (String) -> Unit,
    onClearSelect: () -> Unit,
    onSelectAll: () -> Unit,
    onRename: (FolderEntry.App) -> Unit,
    onNewFolder: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onShareSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onMoveSelected: () -> Unit
) {
    val selecting = selected.isNotEmpty()
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) "已選 " + selected.size + " 個資料夾" else "圖片分類",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GallerySettings.topBar,
                    titleContentColor = GallerySettings.onTopBar,
                    navigationIconContentColor = GallerySettings.onTopBar,
                    actionIconContentColor = GallerySettings.onTopBar
                ),
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = onClearSelect) {
                            Icon(Icons.Default.Close, contentDescription = "取消選取")
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = onSelectAll) { Icon(Icons.Default.Check, contentDescription = "全選") }
                        IconButton(onClick = onShareSelected) { Icon(Icons.Default.Share, contentDescription = "分享") }
                        IconButton(onClick = onMoveSelected) { Icon(Icons.Default.Add, contentDescription = "搬移") }
                        IconButton(onClick = onDeleteSelected) { Icon(Icons.Default.Delete, contentDescription = "刪除") }
                    } else {
                        IconButton(onClick = onNewFolder) { Icon(Icons.Default.Add, contentDescription = "新建資料夾") }
                        IconButton(onClick = onOpenTrash) {
                            Box {
                                Icon(Icons.Default.Delete, contentDescription = "垃圾桶")
                                if (trashCount > 0) {
                                    Box(
                                        modifier = Modifier.align(Alignment.TopEnd).size(15.dp)
                                            .background(Color(0xFFC62828), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { Text(trashCount.toString(), color = Color.White, fontSize = 8.sp) }
                                }
                            }
                        }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "設定") }
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "重新整理") }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("這支手機裡沒有找到圖片", color = Color.Gray)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.key }) { entry ->
                        val isSel = entry.key in selected
                        Card(
                            modifier = Modifier.combinedClickable(
                                onClick = { if (selecting) onToggleSelect(entry.key) else onOpenFolder(entry) },
                                onLongClick = { onToggleSelect(entry.key) }
                            )
                        ) {
                            Box {
                                Column {
                                    when (entry) {
                                        is FolderEntry.Media -> Thumb(
                                            photo = stubPhoto(entry.folder.coverId),
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1.25f)
                                        )
                                        is FolderEntry.App -> Box(
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1.25f)
                                                .background(Color(0xFFECEFF1)),
                                            contentAlignment = Alignment.Center
                                        ) { Text("📁", fontSize = 40.sp) }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { if (selecting) onToggleSelect(entry.key) else onOpenFolder(entry) },
                                                onLongClick = {
                                                    if (entry is FolderEntry.App) onRename(entry)
                                                    else toast(ctx, "手機原有資料夾無法改名")
                                                }
                                            )
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(entry.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                                            val count = when (entry) {
                                                is FolderEntry.Media -> entry.folder.count.toString() + " 張"
                                                is FolderEntry.App -> "App 資料夾"
                                            }
                                            Text(count, fontSize = 11.sp, color = Color.Gray)
                                        }
                                    }
                                }
                                if (isSel) {
                                    Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                                    Box(
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    title: String,
    photos: List<Photo>,
    columns: Int,
    selected: Set<Long>,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAllToggle: () -> Unit,
    onOpen: (Int) -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit
) {
    val selecting = selected.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已選 " + selected.size + " 張" else title, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GallerySettings.topBar,
                    titleContentColor = GallerySettings.onTopBar,
                    navigationIconContentColor = GallerySettings.onTopBar,
                    actionIconContentColor = GallerySettings.onTopBar
                ),
                navigationIcon = {
                    IconButton(onClick = { if (selecting) onClearSelection() else onBack() }) {
                        Icon(
                            if (selecting) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = onSelectAllToggle) {
                            Icon(Icons.Default.Check, contentDescription = "全選")
                        }
                        TextButton(onClick = onMove) {
                            Text("搬移", color = GallerySettings.onTopBar)
                        }
                        IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "分享") }
                        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "刪除") }
                    } else {
                        IconButton(onClick = onSelectAllToggle) {
                            Icon(Icons.Default.Check, contentDescription = "全選")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(2.dp),
            modifier = Modifier.padding(padding)
        ) {
            itemsIndexed(photos, key = { _, p -> p.id }) { index, p ->
                val isSel = p.id in selected
                Box(
                    modifier = Modifier.aspectRatio(1f).padding(1.dp).combinedClickable(
                        onClick = { if (selecting) onToggle(p.id) else onOpen(index) },
                        onLongClick = { onToggle(p.id) }
                    )
                ) {
                    Thumb(photo = p, modifier = Modifier.fillMaxSize())
                    if (isSel) {
                        Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    columns: Int,
    selected: Set<Long>,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAllToggle: () -> Unit,
    onClearSelection: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    onEmptyAll: () -> Unit
) {
    val trashItems = Trash.items
    val selecting = selected.isNotEmpty()
    var confirmEmpty by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) "已選 " + selected.size + " 張" else "垃圾桶（" + trashItems.size + "）",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GallerySettings.topBar,
                    titleContentColor = GallerySettings.onTopBar,
                    navigationIconContentColor = GallerySettings.onTopBar,
                    actionIconContentColor = GallerySettings.onTopBar
                ),
                navigationIcon = {
                    IconButton(onClick = { if (selecting) onClearSelection() else onBack() }) {
                        Icon(if (selecting) Icons.Default.Close else Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = onSelectAllToggle) {
                            Icon(Icons.Default.Check, contentDescription = "全選")
                        }
                        IconButton(onClick = onRestore) { Icon(Icons.Default.Refresh, contentDescription = "還原") }
                        IconButton(onClick = onDeleteForever) { Icon(Icons.Default.Delete, contentDescription = "永久刪除") }
                    } else if (trashItems.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("清空", color = GallerySettings.onTopBar) }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (trashItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("垃圾桶是空的", color = Color.Gray)
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(2.dp)) {
                    items(trashItems, key = { it.id }) { item ->
                        val isSel = item.id in selected
                        Box(
                            modifier = Modifier.aspectRatio(1f).padding(1.dp).combinedClickable(
                                onClick = { onToggle(item.id) },
                                onLongClick = { onToggle(item.id) }
                            )
                        ) {
                            ThumbFile(path = item.thumbPath, modifier = Modifier.fillMaxSize())
                            if (isSel) {
                                Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                                Box(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空垃圾桶") },
            text = { Text("會永久刪除垃圾桶裡的 " + trashItems.size + " 張圖片，無法復原。確定？") },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; onEmptyAll() }) {
                    Text("永久刪除", color = Color(0xFFC62828))
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onRequestRoot: () -> Unit) {
    val context = LocalContext.current
    val rootName = DocStore.root(context)?.name ?: "尚未授權"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GallerySettings.topBar,
                    titleContentColor = GallerySettings.onTopBar,
                    navigationIconContentColor = GallerySettings.onTopBar,
                    actionIconContentColor = GallerySettings.onTopBar
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("外觀", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("上方 Bar 顏色", fontSize = 13.sp, color = Color.Gray)
            PaletteRow(current = GallerySettings.topBarColor) { GallerySettings.setTopBar(it) }
            Spacer(Modifier.height(10.dp))
            Text("底部列顏色", fontSize = 13.sp, color = Color.Gray)
            PaletteRow(current = GallerySettings.bottomBarColor) { GallerySettings.setBottomBar(it) }
            Text("Bar 上的文字會自動選黑或白，保持對比。", fontSize = 11.sp, color = Color.Gray)

            Spacer(Modifier.height(18.dp))
            Text("欄位數", fontWeight = FontWeight.Bold)
            ColumnSlider("資料夾卡片", GallerySettings.folderColumns) { GallerySettings.applyFolderColumns(it) }
            ColumnSlider("資料夾內照片", GallerySettings.photoColumns) { GallerySettings.applyPhotoColumns(it) }
            ColumnSlider("垃圾桶縮圖", GallerySettings.trashColumns) { GallerySettings.applyTrashColumns(it) }

            Spacer(Modifier.height(18.dp))
            Text("刪除行為", fontWeight = FontWeight.Bold)
            BehaviorRow("每次詢問（預設）", "ask")
            BehaviorRow("直接移到垃圾桶", "trash")
            BehaviorRow("直接永久刪除", "permanent")

            Spacer(Modifier.height(18.dp))
            Text("刪除免確認", fontWeight = FontWeight.Bold)
            val allFiles = hasAllFiles(context)
            Text(
                if (allFiles) "已開啟：選了「不再詢問」後，刪除不會再跳系統確認。"
                else "未開啟：Android 每次刪除仍會問一次。開啟後可完全靜默刪除。",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                if (!allFiles) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                }
            }) { Text(if (allFiles) "已開啟全域檔案存取" else "開啟全域檔案存取") }

            Spacer(Modifier.height(18.dp))
            Text("資料夾授權", fontWeight = FontWeight.Bold)
            Text(
                "目前：" + rootName + "\n授權 Pictures 後，新建／改名／搬移都在 App 內完成，不會再跳系統畫面。",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRequestRoot) { Text("授權 Pictures 資料夾") }

            Spacer(Modifier.height(18.dp))
            Text("資料", fontWeight = FontWeight.Bold)
            Text(
                "垃圾桶：" + Trash.items.size + " 張\nApp 資料夾：" + DocStore.folders(context).size + " 個",
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                val dir = java.io.File(context.cacheDir, "trashthumbs")
                val n = dir.listFiles()?.size ?: 0
                dir.listFiles()?.forEach { it.delete() }
                toast(context, "已清理 " + n + " 個垃圾桶縮圖")
            }) { Text("清理垃圾桶縮圖快取") }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
fun PaletteRow(current: Int, onPick: (Int) -> Unit) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        BAR_PALETTE.forEach { color ->
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    .size(28.dp)
                    .background(Color(color), CircleShape)
                    .combinedClickable(onClick = { onPick(color) }, onLongClick = { onPick(color) })
            ) {
                if (color == current) {
                    Text("✓", color = Color(0xFF00C853), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
fun ColumnSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label + "：" + value + " 欄", fontSize = 13.sp)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 2f..10f,
            steps = 7
        )
    }
}

@Composable
fun BehaviorRow(label: String, mode: String) {
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { GallerySettings.applyDeleteBehavior(mode) },
            onLongClick = { GallerySettings.applyDeleteBehavior(mode) }
        ).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = GallerySettings.deleteBehavior == mode, onClick = { GallerySettings.applyDeleteBehavior(mode) })
        Text(label, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    photos: List<Photo>,
    startIndex: Int,
    onClose: () -> Unit,
    onEdit: (Photo) -> Unit,
    onDetails: (Photo) -> Unit,
    onShare: (Photo) -> Unit,
    onDelete: (Photo) -> Unit,
    onSaveAs: (Photo) -> Unit
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { photos.size })
    val current = photos.getOrNull(pagerState.currentPage)

    var scale by remember { mutableStateOf(1f) }
    var rotation by remember { mutableStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    var askSaveOnExit by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        rotation = 0f
        offset = Offset.Zero
    }

    fun rotateAndSave(photo: Photo, thenClose: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { GalleryRepo.loadBitmap(resolver, photo, 2400) }
            if (bmp == null) {
                saving = false
                toast(context, "讀取失敗")
                return@launch
            }
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val name = "change_" + photo.name
            val uri = withContext(Dispatchers.IO) {
                saveImageTo(resolver, rotated, photo.relativePath, name)
            }
            saving = false
            toast(context, if (uri == null) "儲存失敗" else "已儲存：" + name)
            rotation = 0f
            if (thenClose) onClose()
        }
    }

    val backAction = {
        if (rotation != 0f) askSaveOnExit = true else onClose()
    }
    androidx.activity.compose.BackHandler(enabled = rotation != 0f) { askSaveOnExit = true }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scale == 1f
        ) { page ->
            val photo = photos[page]
            val bitmap by produceState<Bitmap?>(initialValue = null, photo.id) {
                value = withContext(Dispatchers.IO) { GalleryRepo.loadBitmap(context.contentResolver, photo, 2400) }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(photo.id) {
                        multiTouch { zoom, rot, pan ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            rotation += rot
                            if (scale > 1f) offset += pan else offset = Offset.Zero
                        }
                    }
                    .pointerInput(photo.id, pagerState.currentPage) {
                        verticalSwipe(
                            thresholdPx = size.height * 0.22f,
                            onUp = {
                                if (scale == 1f && rotation == 0f) onDelete(photo)
                            },
                            onDown = {
                                if (scale == 1f && rotation == 0f) onShare(photo)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp == null) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = photo.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                rotationZ = rotation
                            }
                    )
                }
            }
        }

        // 上方浮層工具列
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x66000000))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = backAction) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) }
            Text(
                (pagerState.currentPage + 1).toString() + " / " + photos.size,
                color = Color.White,
                fontSize = 14.sp
            )
            Spacer(Modifier.weight(1f))
            val photo = current
            if (photo != null) {
                if (rotation != 0f) {
                    IconButton(onClick = { rotateAndSave(photo, false) }) {
                        Icon(Icons.Default.Check, "儲存旋轉", tint = Color.White)
                    }
                }
                IconButton(onClick = { onSaveAs(photo) }) {
                    Icon(Icons.Default.Add, "另存新檔", tint = Color.White)
                }
                IconButton(onClick = { onEdit(photo) }) { Icon(Icons.Default.Edit, "編輯", tint = Color.White) }
                IconButton(onClick = { onDetails(photo) }) { Icon(Icons.Default.Info, "資訊", tint = Color.White) }
                IconButton(onClick = { onDelete(photo) }) { Icon(Icons.Default.Delete, "刪除", tint = Color.White) }
            }
        }
    }

    if (askSaveOnExit) {
        val photo = current
        AlertDialog(
            onDismissRequest = { askSaveOnExit = false },
            title = { Text("儲存旋轉結果？") },
            text = { Text("這張圖片有未儲存的旋轉。要存成新的 change_ 檔嗎？（原圖不會被改）", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    askSaveOnExit = false
                    if (photo != null) rotateAndSave(photo, true) else onClose()
                }) { Text("儲存並離開") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askSaveOnExit = false; rotation = 0f; onClose() }) { Text("不儲存") }
                    TextButton(onClick = { askSaveOnExit = false }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
fun NameDialog(
    title: String,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(hint) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun TargetFolderDialog(
    title: String,
    count: Int,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onPick: (DocumentFile) -> Unit
) {
    val context = LocalContext.current
    val folders = remember { DocStore.folders(context) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("共 " + count + " 張。選一個資料夾：", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                folders.forEach { doc ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onPick(doc) }, onLongClick = { onPick(doc) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📁", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(doc.name ?: "資料夾", fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("或新建資料夾") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (newName.isNotBlank()) onCreate(newName.trim()) }) { Text("新建並使用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.width(80.dp))
        Text(value, fontSize = 13.sp)
    }
}

@Composable
fun Thumb(photo: Photo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, photo.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    GalleryRepo.uriOf(photo),
                    android.util.Size(480, 480),
                    null
                )
            }.getOrNull() ?: GalleryRepo.loadBitmap(context.contentResolver, photo, 500)
        }
    }
    val bmp = bitmap
    if (bmp == null) Box(modifier = modifier.background(Color(0xFFE0E0E0)))
    else Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = photo.name,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Color(0xFFEFEFEF))
    )
}

@Composable
fun ThumbFile(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    val bmp = bitmap
    if (bmp == null) Box(modifier = modifier.background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
        Text("無縮圖", fontSize = 10.sp, color = Color.Gray)
    } else Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}
