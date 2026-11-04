package com.dsh.gallery

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TrashItem(
    val id: Long,
    val name: String,
    val bucket: String,
    val size: Long,
    val mime: String,
    val trashedAt: Long,
    val thumbPath: String,
    /** 非空表示這是 App 自己保管的垃圾桶（可直接還原，不經系統對話框） */
    val localPath: String = ""
)

data class SafFolder(val uriString: String, val name: String)

fun mediaTrashRequest(resolver: ContentResolver, uris: List<Uri>, trash: Boolean = true) =
    runCatching { MediaStore.createTrashRequest(resolver, uris, trash) }.getOrNull()

fun makeThumb(resolver: ContentResolver, uri: Uri, dest: File, maxSize: Int = 480): Boolean {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return false
        dest.parentFile?.mkdirs()
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        true
    }.getOrDefault(false)
}

/** 系統垃圾桶的紀錄（照片本身由 Android 保管，這裡只記可還原所需的資訊與縮圖） */
object Trash {
    private var file: File? = null
    private var thumbDir: File? = null

    var items by mutableStateOf(listOf<TrashItem>())
        private set

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "trash.json")
        thumbDir = File(ctx.cacheDir, "trashthumbs").apply { mkdirs() }
        val f = file ?: return
        if (f.exists()) runCatching { load(f.readText()) }
    }

    fun add(ctx: Context, resolver: ContentResolver, photos: List<Photo>) {
        val dir = File(ctx.cacheDir, "trashthumbs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val added = photos.map { p ->
            val dest = File(dir, "t" + p.id + "_" + now + ".jpg")
            makeThumb(resolver, GalleryRepo.uriOf(p), dest)
            TrashItem(p.id, p.name, p.bucketName, p.sizeBytes, p.mime, now, dest.absolutePath)
        }
        items = items + added
        save()
    }

    /** 把照片搬進 App 自己的垃圾桶目錄（同一台裝置，直接可還原、不跳系統確認） */
    fun addLocal(context: Context, resolver: ContentResolver, photos: List<Photo>): Int {
        val dir = File(context.filesDir, "trash_files").apply { mkdirs() }
        val thumbDir = File(context.cacheDir, "trashthumbs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val added = mutableListOf<TrashItem>()
        photos.forEach { p ->
            runCatching {
                val out = File(dir, now.toString() + "_" + p.id + "_" + p.name)
                resolver.openInputStream(GalleryRepo.uriOf(p))?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                val thumb = File(thumbDir, "t" + p.id + "_" + now + ".jpg")
                runCatching {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = android.graphics.BitmapFactory.decodeFile(out.absolutePath, opts) ?: return@runCatching
                    thumb.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                }
                added.add(
                    TrashItem(
                        id = p.id,
                        name = p.name,
                        bucket = p.relativePath,
                        size = p.sizeBytes,
                        mime = p.mime,
                        trashedAt = now,
                        thumbPath = thumb.absolutePath,
                        localPath = out.absolutePath
                    )
                )
            }
        }
        if (added.isNotEmpty()) {
            items = items + added
            save()
        }
        return added.size
    }

    fun forget(ids: Set<Long>) {
        val gone = items.filter { it.id in ids }
        gone.forEach {
            runCatching { File(it.thumbPath).delete() }
            if (it.localPath.isNotBlank()) runCatching { File(it.localPath).delete() }
        }
        items = items.filterNot { it.id in ids }
        save()
    }

    fun clearAll() {
        items.forEach {
            runCatching { File(it.thumbPath).delete() }
            if (it.localPath.isNotBlank()) runCatching { File(it.localPath).delete() }
        }
        items = emptyList()
        save()
    }

    private fun save() {
        val root = JSONObject()
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("bucket", it.bucket)
                    .put("size", it.size)
                    .put("mime", it.mime)
                    .put("at", it.trashedAt)
                    .put("thumb", it.thumbPath)
                    .put("local", it.localPath)
            )
        }
        root.put("items", arr)
        file?.let { f -> runCatching { f.writeText(root.toString()) } }
    }

    private fun load(text: String) {
        val root = JSONObject(text)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val list = mutableListOf<TrashItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val thumb = o.optString("thumb", "")
            if (thumb.isNotEmpty() && !File(thumb).exists()) continue
            list.add(
                TrashItem(
                    id = o.optLong("id"),
                    name = o.optString("name", ""),
                    bucket = o.optString("bucket", ""),
                    size = o.optLong("size"),
                    mime = o.optString("mime", "image/jpeg"),
                    trashedAt = o.optLong("at"),
                    thumbPath = thumb,
                    localPath = o.optString("local", "")
                )
            )
        }
        items = list
    }
}

/** 用系統資料夾選擇器建立的資料夾（SAF），可供「存到資料夾」使用 */
object SafFolders {
    private var file: File? = null

    var folders by mutableStateOf(listOf<SafFolder>())
        private set

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "saf_folders.json")
        file = f
        if (f.exists()) runCatching { load(f.readText()) }
    }

    fun add(ctx: Context, treeUri: Uri): SafFolder? {
        val doc = runCatching { DocumentFile.fromTreeUri(ctx, treeUri) }.getOrNull() ?: return null
        val name = doc.name ?: treeUri.lastPathSegment ?: "資料夾"
        val item = SafFolder(treeUri.toString(), name)
        folders = folders.filterNot { it.uriString == item.uriString } + item
        save()
        return item
    }

    fun remove(uriString: String) {
        folders = folders.filterNot { it.uriString == uriString }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        folders.forEach { arr.put(JSONObject().put("uri", it.uriString).put("name", it.name)) }
        file?.let { f -> runCatching { f.writeText(JSONObject().put("folders", arr).toString()) } }
    }

    private fun load(text: String) {
        val arr = JSONObject(text).optJSONArray("folders") ?: JSONArray()
        val list = mutableListOf<SafFolder>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(SafFolder(o.optString("uri"), o.optString("name", "資料夾")))
        }
        folders = list
    }
}

fun sharePhotos(context: Context, photos: List<Photo>) {
    if (photos.isEmpty()) return
    val uris = ArrayList(photos.map { GalleryRepo.uriOf(it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = photos.first().mime.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "分享圖片")) }
}

/** 把照片原始位元組複製到 SAF 資料夾（真正建立／使用該資料夾） */
fun copyIntoFolder(context: Context, folder: SafFolder, photos: List<Photo>): Int {
    val tree = runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(folder.uriString))
    }.getOrNull() ?: return 0
    var copied = 0
    photos.forEach { p ->
        runCatching {
            val base = p.name.substringBeforeLast('.').ifBlank { "IMG_" + p.id }
            val target = tree.createFile("image/jpeg", base) ?: return@runCatching
            context.contentResolver.openInputStream(GalleryRepo.uriOf(p))?.use { input ->
                context.contentResolver.openOutputStream(target.uri)?.use { out -> input.copyTo(out) }
            }
            copied++
        }
    }
    return copied
}

/** 用 MediaStore 的方式存檔（建在 Pictures/<名稱>） */
fun saveCopyToNewFolder(context: Context, folderName: String, photos: List<Photo>): Int {
    var n = 0
    photos.forEach { p ->
        val bmp = GalleryRepo.loadBitmap(context.contentResolver, p, 4000) ?: return@forEach
        val uri = GalleryRepo.saveBitmap(context.contentResolver, bmp, folderName)
        if (uri != null) n++
    }
    return n
}

/** 存到指定的 RELATIVE_PATH，可自訂檔名（用於「儲存」成 change_ 檔） */
fun saveImageTo(
    resolver: ContentResolver,
    bitmap: Bitmap,
    relativePath: String,
    displayName: String,
    quality: Int = 95
): Uri? {
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (relativePath.isNotBlank()) put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return null
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
    if (!ok) {
        runCatching { resolver.delete(uri, null, null) }
        return null
    }
    return uri
}

/** 把 Bitmap 寫進 SAF 目錄（另存新檔用） */
fun saveBitmapIntoDoc(context: Context, target: DocumentFile, bitmap: Bitmap, name: String): Boolean =
    runCatching {
        val file = target.createFile("image/jpeg", name) ?: return false
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        true
    }.getOrDefault(false)

/** 直接把位元組寫回 MediaStore（還原 App 垃圾桶用，不重新編碼） */
fun insertBytes(
    resolver: ContentResolver,
    relativePath: String,
    displayName: String,
    mime: String,
    bytes: ByteArray
): Uri? {
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mime.ifBlank { "image/jpeg" })
        if (relativePath.isNotBlank()) put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
    if (!ok) { runCatching { resolver.delete(uri, null, null) }; return null }
    return uri
}
