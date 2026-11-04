@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dsh.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
                GalleryApp()
            }
        }
    }
}

fun neededPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

fun hasPhotoPermission(context: android.content.Context): Boolean {
    return neededPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

fun stubPhoto(id: Long): Photo =
    Photo(id, "", 0L, 0, 0, 0L, "image/jpeg", "", "", 0)

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
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("需要相片權限", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("圖片櫃需要讀取手機照片才能瀏覽與編輯。", color = Color.Gray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(neededPermissions()) }) {
                Text("授予權限")
            }
        }
    } else {
        GalleryContent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryContent() {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var folders by remember { mutableStateOf(listOf<Folder>()) }
    var photos by remember { mutableStateOf(listOf<Photo>()) }
    var currentFolder by remember { mutableStateOf<Folder?>(null) }
    var reload by remember { mutableStateOf(0) }
    var viewerIndex by remember { mutableStateOf(-1) }
    var editingPhoto by remember { mutableStateOf<Photo?>(null) }
    var detailsPhoto by remember { mutableStateOf<Photo?>(null) }
    var saveToFolderPhoto by remember { mutableStateOf<Photo?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewerIndex = -1
        reload++
        Toast.makeText(context, if (result.resultCode == android.app.Activity.RESULT_OK) "已刪除" else "已取消刪除", Toast.LENGTH_SHORT).show()
    }

    fun askDelete(photo: Photo) {
        val pending = GalleryRepo.deleteRequest(resolver, listOf(GalleryRepo.uriOf(photo)))
        if (pending == null) {
            Toast.makeText(context, "無法刪除這張圖片", Toast.LENGTH_SHORT).show()
        } else {
            runCatching {
                deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            }.onFailure {
                Toast.makeText(context, "無法開啟刪除確認", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(reload) {
        loading = true
        folders = withContext(Dispatchers.IO) { GalleryRepo.folders(resolver) }
        loading = false
    }
    LaunchedEffect(currentFolder, reload) {
        val bucket = currentFolder?.bucketId
        photos = withContext(Dispatchers.IO) { GalleryRepo.query(resolver, bucket) }
    }

    val folder = currentFolder
    if (folder == null) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("圖片櫃", fontWeight = FontWeight.Bold) }, actions = {
                    TextButton(onClick = { reload++ }) { Text("重新整理") }
                })
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (folders.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("這支手機裡沒有找到圖片", color = Color.Gray)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(folders, key = { it.bucketId + it.name }) { f ->
                            Card(modifier = Modifier.clickable { currentFolder = f }) {
                                Column {
                                    Thumb(
                                        photo = stubPhoto(f.coverId),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1.3f)
                                    )
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(f.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text(f.count.toString() + " 張", fontSize = 12.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(folder.name, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { currentFolder = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    itemsIndexed(photos, key = { _, p -> p.id }) { index, p ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clickable { viewerIndex = index }
                        ) {
                            Thumb(photo = p, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    if (viewerIndex in 0 until photos.size) {
        PhotoViewer(
            photos = photos,
            startIndex = viewerIndex,
            onClose = { viewerIndex = -1 },
            onEdit = { editingPhoto = it },
            onDetails = { detailsPhoto = it },
            onDelete = { askDelete(it) },
            onSaveToFolder = { saveToFolderPhoto = it }
        )
    }

    editingPhoto?.let { photo ->
        EditorScreen(
            photo = photo,
            onClose = { editingPhoto = null; reload++ },
            onSaved = {
                editingPhoto = null
                reload++
            }
        )
    }

    detailsPhoto?.let { photo ->
        AlertDialog(
            onDismissRequest = { detailsPhoto = null },
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
            confirmButton = { TextButton(onClick = { detailsPhoto = null }) { Text("關閉") } }
        )
    }

    saveToFolderPhoto?.let { photo ->
        var name by remember { mutableStateOf("我的相簿") }
        AlertDialog(
            onDismissRequest = { saveToFolderPhoto = null },
            title = { Text("存到資料夾") },
            text = {
                Column {
                    Text("會在 Pictures/ 底下建立（或使用）這個資料夾，並存入一份副本。", fontSize = 13.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("資料夾名稱") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val folderName = name.trim()
                    if (folderName.isEmpty()) return@TextButton
                    saveToFolderPhoto = null
                    scope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            GalleryRepo.loadBitmap(resolver, photo, 4000)
                        }
                        if (bitmap == null) {
                            Toast.makeText(context, "讀取圖片失敗", Toast.LENGTH_SHORT).show()
                        } else {
                            val uri = withContext(Dispatchers.IO) {
                                GalleryRepo.saveBitmap(resolver, bitmap, folderName)
                            }
                            Toast.makeText(
                                context,
                                if (uri == null) "存入失敗" else "已存入 Pictures/" + folderName,
                                Toast.LENGTH_SHORT
                            ).show()
                            reload++
                        }
                    }
                }) { Text("存入") }
            },
            dismissButton = { TextButton(onClick = { saveToFolderPhoto = null }) { Text("取消") } }
        )
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewer(
    photos: List<Photo>,
    startIndex: Int,
    onClose: () -> Unit,
    onEdit: (Photo) -> Unit,
    onDetails: (Photo) -> Unit,
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
                    Text(
                        (pagerState.currentPage + 1).toString() + " / " + photos.size,
                        fontSize = 15.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val photo = current
                    if (photo != null) {
                        IconButton(onClick = { onEdit(photo) }) {
                            Icon(Icons.Default.Edit, contentDescription = "編輯")
                        }
                        IconButton(onClick = { onSaveToFolder(photo) }) {
                            Icon(Icons.Default.Share, contentDescription = "存到資料夾")
                        }
                        IconButton(onClick = { onDetails(photo) }) {
                            Icon(Icons.Default.Info, contentDescription = "資訊")
                        }
                        IconButton(onClick = { onDelete(photo) }) {
                            Icon(Icons.Default.Delete, contentDescription = "刪除")
                        }
                    }
                }
            )
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
