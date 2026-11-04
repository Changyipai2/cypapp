@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dsh.gallery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF37474F),
                    secondary = Color(0xFF546E7A)
                )
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

private enum class GalleryScreen { FOLDERS, PHOTOS, TRASH }

private data class PendingAction(
    val kind: String,
    val photos: List<Photo> = emptyList(),
    val ids: Set<Long> = emptySet()
)

@Composable
fun GalleryApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPhotoPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasPhotoPermission(context) }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(neededPermissions())
    }

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
    SafFolders.init(context)

    var screen by remember { mutableStateOf(GalleryScreen.FOLDERS) }
    var folders by remember { mutableStateOf(listOf<Folder>()) }
    var photos by remember { mutableStateOf(listOf<Photo>()) }
    var folder by remember { mutableStateOf<Folder?>(null) }
    var reload by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var trashSelected by remember { mutableStateOf(setOf<Long>()) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var viewerIndex by remember { mutableStateOf(-1) }
    var editing by remember { mutableStateOf<Photo?>(null) }
    var details by remember { mutableStateOf<Photo?>(null) }
    var saveTargets by remember { mutableStateOf(listOf<Photo>()) }
    var loading by remember { mutableStateOf(true) }
    var folderMenu by remember { mutableStateOf<Folder?>(null) }
    var safFolderMenu by remember { mutableStateOf<SafFolder?>(null) }

    val confirmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && action != null) {
            when (action.kind) {
                "trash" -> {
                    scope.launch {
                        withContext(Dispatchers.IO) { Trash.add(context, resolver, action.photos) }
                        selected = emptySet()
                        viewerIndex = -1
                        reload++
                        toast(context, "已移到垃圾桶（可在垃圾桶還原）")
                    }
                }
                "restore" -> {
                    Trash.forget(action.ids)
                    trashSelected = emptySet()
                    reload++
                    toast(context, "已還原 " + action.ids.size + " 張")
                }
                "delete" -> {
                    Trash.forget(action.ids)
                    trashSelected = emptySet()
                    reload++
                    toast(context, "已永久刪除 " + action.ids.size + " 張")
                }
            }
        }
    }

    fun launchDelete(kind: String, action: PendingAction, intentSender: android.content.IntentSender, failMsg: String) {
        pending = action
        runCatching {
            confirmLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }.onFailure {
            pending = null
            toast(context, failMsg)
        }
    }

    fun trashPhotos(list: List<Photo>) {
        if (list.isEmpty()) return
        val request = mediaTrashRequest(resolver, list.map { GalleryRepo.uriOf(it) }, true)
        if (request == null) {
            toast(context, "無法移到垃圾桶")
            return
        }
        launchDelete("trash", PendingAction("trash", photos = list), request.intentSender, "無法開啟垃圾桶確認")
    }

    fun restoreItems(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val uris = Trash.items.filter { it.id in ids }.map { stubPhoto(it.id) }.map { GalleryRepo.uriOf(it) }
        val request = mediaTrashRequest(resolver, uris, false)
        if (request == null) {
            toast(context, "無法還原")
            return
        }
        launchDelete("restore", PendingAction("restore", ids = ids), request.intentSender, "無法開啟還原確認")
    }

    fun deleteForever(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val uris = Trash.items.filter { it.id in ids }.map { GalleryRepo.uriOf(stubPhoto(it.id)) }
        val request = runCatching { android.provider.MediaStore.createDeleteRequest(resolver, uris) }.getOrNull()
        if (request == null) {
            toast(context, "無法永久刪除")
            return
        }
        launchDelete("delete", PendingAction("delete", ids = ids), request.intentSender, "無法開啟刪除確認")
    }

    val folderPicker = rememberLauncherForActivityResult(
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
                val added = SafFolders.add(context, uri)
                if (added == null) toast(context, "無法建立資料夾")
                else toast(context, "已建立：" + added.name + "（可用「存到資料夾」放入照片）")
            }
        }
    }

    fun pickNewFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        runCatching { folderPicker.launch(intent) }.onFailure { toast(context, "無法開啟資料夾選擇器") }
    }

    LaunchedEffect(reload) {
        loading = true
        folders = withContext(Dispatchers.IO) { GalleryRepo.folders(resolver) }
        loading = false
    }
    LaunchedEffect(folder, reload) {
        val bucket = folder?.bucketId
        photos = withContext(Dispatchers.IO) { GalleryRepo.query(resolver, bucket) }
    }

    when (screen) {
        GalleryScreen.FOLDERS -> FoldersScreen(
            folders = folders,
            safFolders = SafFolders.folders,
            loading = loading,
            trashCount = Trash.items.size,
            onRefresh = { reload++ },
            onOpenFolder = { c ->
                selected = emptySet()
                folder = c
                screen = GalleryScreen.PHOTOS
            },
            onFolderLongPress = { folderMenu = it },
            onSafLongPress = { safFolderMenu = it },
            onNewFolder = { pickNewFolder() },
            onOpenTrash = {
                trashSelected = emptySet()
                screen = GalleryScreen.TRASH
            }
        )

        GalleryScreen.PHOTOS -> PhotosScreen(
            title = folder?.name ?: "",
            photos = photos,
            selected = selected,
            onBack = {
                selected = emptySet()
                screen = GalleryScreen.FOLDERS
            },
            onToggle = { id ->
                selected = if (id in selected) selected - id else selected + id
            },
            onSelectAll = { selected = photos.map { it.id }.toSet() },
            onClearSelection = { selected = emptySet() },
            onOpen = { index -> viewerIndex = index },
            onShare = { sharePhotos(context, photos.filter { it.id in selected }) },
            onDelete = { trashPhotos(photos.filter { it.id in selected }) }
        )

        GalleryScreen.TRASH -> TrashScreen(
            selected = trashSelected,
            onBack = {
                trashSelected = emptySet()
                reload++
                screen = GalleryScreen.FOLDERS
            },
            onToggle = { id ->
                trashSelected = if (id in trashSelected) trashSelected - id else trashSelected + id
            },
            onSelectAll = { trashSelected = Trash.items.map { it.id }.toSet() },
            onClearSelection = { trashSelected = emptySet() },
            onRestore = { restoreItems(trashSelected) },
            onDeleteForever = { deleteForever(trashSelected) },
            onEmptyAll = { deleteForever(Trash.items.map { it.id }.toSet()) }
        )
    }

    // 整頁瀏覽
    if (viewerIndex in 0 until photos.size) {
        PhotoViewer(
            photos = photos,
            startIndex = viewerIndex,
            onClose = { viewerIndex = -1 },
            onEdit = { editing = it },
            onDetails = { details = it },
            onShare = { sharePhotos(context, listOf(it)) },
            onDelete = { trashPhotos(listOf(it)) },
            onSaveToFolder = { saveTargets = listOf(it) }
        )
    }

    editing?.let { photo ->
        EditorScreen(
            photo = photo,
            onClose = { editing = null; reload++ },
            onSaved = { editing = null; reload++ }
        )
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

    folderMenu?.let { target ->
        AlertDialog(
            onDismissRequest = { folderMenu = null },
            title = { Text(target.name) },
            text = {
                Column {
                    Text("共 " + target.count + " 張。", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Text("刪除資料夾會把裡面的照片全部移到垃圾桶（可還原）。", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    folderMenu = null
                    scope.launch {
                        val list = withContext(Dispatchers.IO) { GalleryRepo.query(resolver, target.bucketId) }
                        trashPhotos(list)
                    }
                }) { Text("刪除整個資料夾", color = Color(0xFFC62828)) }
            },
            dismissButton = { TextButton(onClick = { folderMenu = null }) { Text("取消") } }
        )
    }

    safFolderMenu?.let { target ->
        AlertDialog(
            onDismissRequest = { safFolderMenu = null },
            title = { Text(target.name) },
            text = { Text("這是你在系統選擇器建立／指定的資料夾。從清單移除不會刪除手機上的資料夾。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    SafFolders.remove(target.uriString)
                    safFolderMenu = null
                }) { Text("從清單移除") }
            },
            dismissButton = { TextButton(onClick = { safFolderMenu = null }) { Text("取消") } }
        )
    }

    if (saveTargets.isNotEmpty()) {
        SaveToFolderDialog(
            photos = saveTargets,
            onDismiss = { saveTargets = emptyList() },
            onNewFolder = {
                saveTargets = emptyList()
                pickNewFolder()
            },
            onPick = { saf ->
                val list = saveTargets
                saveTargets = emptyList()
                scope.launch {
                    val n = withContext(Dispatchers.IO) { copyIntoFolder(context, saf, list) }
                    toast(context, if (n > 0) "已存入 " + saf.name + "（" + n + " 張）" else "存入失敗")
                    reload++
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    folders: List<Folder>,
    safFolders: List<SafFolder>,
    loading: Boolean,
    trashCount: Int,
    onRefresh: () -> Unit,
    onOpenFolder: (Folder) -> Unit,
    onFolderLongPress: (Folder) -> Unit,
    onSafLongPress: (SafFolder) -> Unit,
    onNewFolder: () -> Unit,
    onOpenTrash: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("圖片分類", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNewFolder) { Icon(Icons.Default.Add, contentDescription = "新建資料夾") }
                    IconButton(onClick = onOpenTrash) {
                        Box {
                            Icon(Icons.Default.Delete, contentDescription = "垃圾桶")
                            if (trashCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(16.dp)
                                        .background(Color(0xFFC62828), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(trashCount.toString(), color = Color.White, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "重新整理") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (folders.isEmpty() && safFolders.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("這支手機裡沒有找到圖片", color = Color.Gray)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(folders, key = { "m-" + it.bucketId + it.name }) { f ->
                        Card(modifier = Modifier.combinedClickable(
                            onClick = { onOpenFolder(f) },
                            onLongClick = { onFolderLongPress(f) }
                        )) {
                            Column {
                                Thumb(
                                    photo = stubPhoto(f.coverId),
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1.3f)
                                )
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(f.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                                    Text(f.count.toString() + " 張", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                    items(safFolders, key = { "s-" + it.uriString }) { s ->
                        Card(modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { onSafLongPress(s) }
                        )) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1.3f)
                                        .background(Color(0xFFECEFF1)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📁", fontSize = 40.sp)
                                }
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(s.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                                    Text("App 建立的資料夾", fontSize = 12.sp, color = Color.Gray)
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
    selected: Set<Long>,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpen: (Int) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val selecting = selected.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) "已選 " + selected.size + " 張" else title,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) onClearSelection() else onBack() }) {
                        Icon(
                            if (selecting) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = if (selecting) "取消選取" else "返回"
                        )
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = onSelectAll) { Text("全選") }
                    }
                }
            )
        },
        bottomBar = {
            if (selecting) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onShare, modifier = Modifier.weight(1f)) { Text("分享") }
                    Button(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828)
                        )
                    ) { Text("刪除") }
                }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(2.dp),
            modifier = Modifier.padding(padding)
        ) {
            itemsIndexed(photos, key = { _, p -> p.id }) { index, p ->
                val isSelected = p.id in selected
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(2.dp)
                        .combinedClickable(
                            onClick = {
                                if (selecting) onToggle(p.id) else onOpen(index)
                            },
                            onLongClick = { onToggle(p.id) }
                        )
                ) {
                    Thumb(photo = p, modifier = Modifier.fillMaxSize())
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x66000000))
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(22.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    selected: Set<Long>,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
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
                navigationIcon = {
                    IconButton(onClick = { if (selecting) onClearSelection() else onBack() }) {
                        Icon(
                            if (selecting) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = if (selecting) "取消選取" else "返回"
                        )
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = onSelectAll) { Text("全選") }
                    } else if (trashItems.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("清空") }
                    }
                }
            )
        },
        bottomBar = {
            if (selecting) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onRestore, modifier = Modifier.weight(1f)) { Text("還原") }
                    Button(
                        onClick = onDeleteForever,
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC62828)
                        )
                    ) { Text("永久刪除") }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (trashItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("垃圾桶是空的", color = Color.Gray)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    items(trashItems, key = { it.id }) { item ->
                        val isSelected = item.id in selected
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .combinedClickable(
                                    onClick = { onToggle(item.id) },
                                    onLongClick = { onToggle(item.id) }
                                )
                        ) {
                            ThumbFile(path = item.thumbPath, modifier = Modifier.fillMaxSize())
                            if (isSelected) {
                                Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(22.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                                }
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
                TextButton(onClick = {
                    confirmEmpty = false
                    onEmptyAll()
                }) { Text("永久刪除", color = Color(0xFFC62828)) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("取消") } }
        )
    }
}

@Composable
fun SaveToFolderDialog(
    photos: List<Photo>,
    onDismiss: () -> Unit,
    onNewFolder: () -> Unit,
    onPick: (SafFolder) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存到資料夾") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("會複製 " + photos.size + " 張到指定資料夾，原圖不動。", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                if (SafFolders.folders.isEmpty()) {
                    Text("還沒有建立資料夾。先按下面按鈕，用系統選擇器建立一個。", fontSize = 13.sp)
                } else {
                    SafFolders.folders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { onPick(folder) }, onLongClick = { onPick(folder) })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(folder.name, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNewFolder) { Text("新建資料夾") } },
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
            GalleryRepo.loadBitmap(context.contentResolver, photo, 500)
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(modifier = modifier.background(Color(0xFFE0E0E0)))
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = photo.name,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Color(0xFFEFEFEF))
        )
    }
}

@Composable
fun ThumbFile(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(modifier = modifier.background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
            Text("無縮圖", fontSize = 10.sp, color = Color.Gray)
        }
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewer(
    photos: List<Photo>,
    startIndex: Int,
    onClose: () -> Unit,
    onEdit: (Photo) -> Unit,
    onDetails: (Photo) -> Unit,
    onShare: (Photo) -> Unit,
    onDelete: (Photo) -> Unit,
    onSaveToFolder: (Photo) -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { photos.size })
    val current = photos.getOrNull(pagerState.currentPage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text((pagerState.currentPage + 1).toString() + " / " + photos.size, fontSize = 15.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    val photo = current
                    if (photo != null) {
                        IconButton(onClick = { onEdit(photo) }) { Icon(Icons.Default.Edit, contentDescription = "編輯") }
                        IconButton(onClick = { onShare(photo) }) { Icon(Icons.Default.Share, contentDescription = "分享") }
                        IconButton(onClick = { onDetails(photo) }) { Icon(Icons.Default.Info, contentDescription = "資訊") }
                        IconButton(onClick = { onDelete(photo) }) { Icon(Icons.Default.Delete, contentDescription = "刪除") }
                    }
                }
            )
        },
        bottomBar = {
            val photo = current
            if (photo != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { onSaveToFolder(photo) }) { Text("存到資料夾") }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val photo = photos[page]
                var scale by remember(photo.id) { mutableStateOf(1f) }
                var offsetX by remember(photo.id) { mutableStateOf(0f) }
                var offsetY by remember(photo.id) { mutableStateOf(0f) }
                val bitmap by produceState<Bitmap?>(initialValue = null, photo.id) {
                    value = withContext(Dispatchers.IO) {
                        GalleryRepo.loadBitmap(context.contentResolver, photo, 2400)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(photo.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
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
                                    translationX = offsetX
                                    translationY = offsetY
                                }
                        )
                    }
                }
            }
        }
    }
}
