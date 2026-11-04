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
    val thumbPath: String
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

    fun forget(ids: Set<Long>) {
        val gone = items.filter { it.id in ids }
        gone.forEach { runCatching { File(it.thumbPath).delete() } }
        items = items.filterNot { it.id in ids }
        save()
    }

    fun clearAll() {
        items.forEach { runCatching { File(it.thumbPath).delete() } }
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
                    thumbPath = thumb
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
