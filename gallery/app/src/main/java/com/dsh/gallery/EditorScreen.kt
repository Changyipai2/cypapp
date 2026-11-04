@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dsh.gallery

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditTool { NONE, BRUSH, TEXT, CROP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(photo: Photo, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()

    var working by remember { mutableStateOf<Bitmap?>(null) }
    var history by remember { mutableStateOf(listOf<Bitmap>()) }
    var tool by remember { mutableStateOf(EditTool.NONE) }
    var brushColor by remember { mutableStateOf(Color(0xFFE53935)) }
    var brushSize by remember { mutableStateOf(14f) }
    var strokePoints by remember { mutableStateOf(listOf<Offset>()) }
    var cropRect by remember { mutableStateOf<Rect?>(null) }
    var textAnchor by remember { mutableStateOf<Offset?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(photo.id) {
        working = withContext(Dispatchers.IO) { GalleryRepo.loadBitmap(resolver, photo, 2400) }
    }

    val bmp = working
    val scaleFit = if (bmp == null || boxSize.width == 0 || boxSize.height == 0) 1f else
        minOf(boxSize.width.toFloat() / bmp.width.toFloat(), boxSize.height.toFloat() / bmp.height.toFloat())
    val offX = if (bmp == null) 0f else (boxSize.width.toFloat() - bmp.width * scaleFit) / 2f
    val offY = if (bmp == null) 0f else (boxSize.height.toFloat() - bmp.height * scaleFit) / 2f

    fun toImagePoint(point: Offset): Offset = Offset((point.x - offX) / scaleFit, (point.y - offY) / scaleFit)

    fun applyEdit(previous: Bitmap, result: Bitmap) {
        history = (history + previous).takeLast(10)
        working = result
        cropRect = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("編輯圖片", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (saving) {
                        Text("儲存中…", fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                    } else {
                        TextButton(onClick = {
                            val current = working ?: return@TextButton
                            saving = true
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    GalleryRepo.saveBitmap(resolver, current, "圖片櫃")
                                }
                                saving = false
                                if (uri == null) {
                                    Toast.makeText(context, "儲存失敗", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "已存到 Pictures/圖片櫃", Toast.LENGTH_LONG).show()
                                    onSaved()
                                }
                            }
                        }) { Text("儲存") }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF111111))
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(tool, bmp, boxSize) {
                        when (tool) {
                            EditTool.BRUSH -> {
                                detectDragGestures(
                                    onDragStart = { strokePoints = listOf(it) },
                                    onDrag = { change, _ ->
                                        strokePoints = strokePoints + change.position
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        val source = bmp
                                        val points = strokePoints
                                        if (source != null && points.size > 1) {
                                            val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
                                            val canvas = android.graphics.Canvas(mutable)
                                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                            paint.color = brushColor.toArgb()
                                            paint.style = android.graphics.Paint.Style.STROKE
                                            paint.strokeWidth = (brushSize / scaleFit).coerceAtLeast(1f)
                                            paint.strokeCap = android.graphics.Paint.Cap.ROUND
                                            paint.strokeJoin = android.graphics.Paint.Join.ROUND
                                            val path = android.graphics.Path()
                                            val first = toImagePoint(points[0])
                                            path.moveTo(first.x, first.y)
                                            for (i in 1 until points.size) {
                                                val p = toImagePoint(points[i])
                                                path.lineTo(p.x, p.y)
                                            }
                                            canvas.drawPath(path, paint)
                                            applyEdit(source, mutable)
                                        }
                                        strokePoints = emptyList()
                                    },
                                    onDragCancel = { strokePoints = emptyList() }
                                )
                            }

                            EditTool.CROP -> {
                                var start = Offset.Zero
                                detectDragGestures(
                                    onDragStart = {
                                        start = it
                                        cropRect = Rect(it.x, it.y, it.x, it.y)
                                    },
                                    onDrag = { change, _ ->
                                        val p = change.position
                                        cropRect = Rect(
                                            minOf(start.x, p.x),
                                            minOf(start.y, p.y),
                                            maxOf(start.x, p.x),
                                            maxOf(start.y, p.y)
                                        )
                                        change.consume()
                                    }
                                )
                            }

                            EditTool.TEXT -> {
                                detectTapGestures(onTap = { textAnchor = it })
                            }

                            EditTool.NONE -> Unit
                        }
                    }
            ) {
                if (bmp == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("載入中…", color = Color.White)
                    }
                } else {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = photo.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (strokePoints.size > 1) {
                        val preview = Path()
                        preview.moveTo(strokePoints[0].x, strokePoints[0].y)
                        for (i in 1 until strokePoints.size) {
                            preview.lineTo(strokePoints[i].x, strokePoints[i].y)
                        }
                        drawPath(
                            path = preview,
                            color = brushColor,
                            style = Stroke(width = brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                    cropRect?.let { r ->
                        drawRect(
                            color = Color.White,
                            topLeft = r.topLeft,
                            size = Size(r.width, r.height),
                            style = Stroke(width = 3f)
                        )
                    }
                }
            }

            if (tool == EditTool.BRUSH) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            Color(0xFFE53935), Color(0xFFFFEB3B), Color(0xFF43A047),
                            Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFFFFFFF), Color(0xFF000000)
                        ).forEach { c ->
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(26.dp)
                                    .background(c, CircleShape)
                                    .clickable { brushColor = c }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("粗細", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = brushSize,
                            onValueChange = { brushSize = it },
                            valueRange = 4f..48f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (tool == EditTool.CROP && cropRect != null) {
                Button(
                    onClick = {
                        val source = bmp
                        val r = cropRect
                        if (source != null && r != null) {
                            val x = (((r.left - offX) / scaleFit)).toInt().coerceIn(0, source.width - 1)
                            val y = (((r.top - offY) / scaleFit)).toInt().coerceIn(0, source.height - 1)
                            val w = ((r.width / scaleFit).toInt()).coerceIn(1, source.width - x)
                            val h = ((r.height / scaleFit).toInt()).coerceIn(1, source.height - y)
                            if (w > 8 && h > 8) {
                                applyEdit(source, Bitmap.createBitmap(source, x, y, w, h))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) { Text("套用裁切") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ToolButton("復原", false) {
                    val last = history.lastOrNull()
                    if (last != null) {
                        working = last
                        history = history.dropLast(1)
                    }
                }
                ToolButton("畫筆", tool == EditTool.BRUSH) {
                    tool = if (tool == EditTool.BRUSH) EditTool.NONE else EditTool.BRUSH
                    cropRect = null
                }
                ToolButton("文字", tool == EditTool.TEXT) {
                    tool = if (tool == EditTool.TEXT) EditTool.NONE else EditTool.TEXT
                    cropRect = null
                }
                ToolButton("裁切", tool == EditTool.CROP) {
                    tool = if (tool == EditTool.CROP) EditTool.NONE else EditTool.CROP
                    cropRect = null
                }
                ToolButton("旋轉 90°", false) {
                    val source = bmp ?: return@ToolButton
                    val matrix = Matrix()
                    matrix.postRotate(90f)
                    applyEdit(source, Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true))
                }
                ToolButton("左右翻轉", false) {
                    val source = bmp ?: return@ToolButton
                    val matrix = Matrix()
                    matrix.postScale(-1f, 1f)
                    applyEdit(source, Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true))
                }
                ToolButton("縮小", false) {
                    val source = bmp ?: return@ToolButton
                    val maxSide = 1600
                    val longest = maxOf(source.width, source.height)
                    if (longest > maxSide) {
                        val ratio = maxSide.toFloat() / longest.toFloat()
                        applyEdit(
                            source,
                            Bitmap.createScaledBitmap(
                                source,
                                (source.width * ratio).toInt().coerceAtLeast(1),
                                (source.height * ratio).toInt().coerceAtLeast(1),
                                true
                            )
                        )
                    }
                }
            }
        }
    }

    val anchor = textAnchor
    if (anchor != null) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textAnchor = null },
            title = { Text("加入文字") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("文字內容") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = value.trim()
                    val source = bmp
                    textAnchor = null
                    if (text.isNotEmpty() && source != null) {
                        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
                        val canvas = android.graphics.Canvas(mutable)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        paint.color = brushColor.toArgb()
                        paint.textSize = (source.width / 18f).coerceAtLeast(24f)
                        val point = toImagePoint(anchor)
                        canvas.drawText(text, point.x, point.y, paint)
                        applyEdit(source, mutable)
                    }
                }) { Text("加入") }
            },
            dismissButton = { TextButton(onClick = { textAnchor = null }) { Text("取消") } }
        )
    }
}

@Composable
fun ToolButton(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color(0xFF80CBC4) else Color(0xFFEEEEEE),
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}
