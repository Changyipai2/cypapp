package com.dsh.gallery

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Folder(
    val bucketId: String,
    val name: String,
    val count: Int,
    val coverId: Long
)

data class Photo(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAdded: Long,
    val mime: String,
    val bucketName: String,
    val relativePath: String,
    val orientation: Int
)

object GalleryRepo {

    private val COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private val COLUMNS = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.ORIENTATION
    )

    fun uriOf(photo: Photo): Uri = Uri.withAppendedPath(COLLECTION, photo.id.toString())

    fun query(resolver: ContentResolver, bucketId: String?): List<Photo> {
        val selection = if (bucketId == null) null else MediaStore.Images.Media.BUCKET_ID + " = ?"
        val args = if (bucketId == null) null else arrayOf(bucketId)
        val out = mutableListOf<Photo>()
        runCatching {
            resolver.query(COLLECTION, COLUMNS, selection, args, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val iOrient = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
                while (c.moveToNext()) {
                    out.add(
                        Photo(
                            id = c.getLong(iId),
                            name = c.getString(iName) ?: "未命名",
                            sizeBytes = c.getLong(iSize),
                            width = c.getInt(iW),
                            height = c.getInt(iH),
                            dateAdded = c.getLong(iDate) * 1000L,
                            mime = c.getString(iMime) ?: "image/jpeg",
                            bucketName = c.getString(iBucket) ?: "其他",
                            relativePath = c.getString(iPath) ?: "",
                            orientation = c.getInt(iOrient)
                        )
                    )
                }
            }
        }
        return out
    }

    fun folders(resolver: ContentResolver): List<Folder> {
        val photos = query(resolver, null)
        val grouped = photos.groupBy { it.relativePath + "|" + it.bucketName }
        return grouped.map { entry ->
            val first = entry.value.first()
            Folder(
                bucketId = bucketIdOf(resolver, first),
                name = first.bucketName,
                count = entry.value.size,
                coverId = first.id
            )
        }.sortedByDescending { it.count }
    }

    private fun bucketIdOf(resolver: ContentResolver, photo: Photo): String {
        var bucket = photo.relativePath
        runCatching {
            resolver.query(
                COLLECTION,
                arrayOf(MediaStore.Images.Media.BUCKET_ID),
                MediaStore.Images.Media._ID + " = ?",
                arrayOf(photo.id.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) bucket = c.getString(0) ?: bucket }
        }
        return bucket
    }

    fun folderIdFor(resolver: ContentResolver, photo: Photo): String = bucketIdOf(resolver, photo)

    fun loadBitmap(resolver: ContentResolver, photo: Photo, maxSize: Int = 1600): Bitmap? {
        var bitmap: Bitmap? = null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uriOf(photo))?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / sample > maxSize || h / sample > maxSize) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            bitmap = resolver.openInputStream(uriOf(photo))?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        val loaded = bitmap ?: return null
        val degrees = photo.orientation % 360
        if (degrees == 0) return loaded
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(loaded, 0, 0, loaded.width, loaded.height, matrix, true)
    }

    fun saveBitmap(resolver: ContentResolver, bitmap: Bitmap, folderName: String, quality: Int = 95): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "EDITED_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + folderName)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(COLLECTION, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { stream ->
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                stream.write(out.toByteArray())
            }
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

    fun deleteRequest(resolver: ContentResolver, uris: List<Uri>): PendingIntent? =
        runCatching { MediaStore.createDeleteRequest(resolver, uris) }.getOrNull()

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.TAIWAN, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.TAIWAN, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date(millis))
}
