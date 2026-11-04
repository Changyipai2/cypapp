package com.dsh.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** 用 SAF 授權的根目錄（Pictures），讓資料夾的新增/改名/搬移都在 App 內完成 */
object DocStore {

    fun root(context: Context): DocumentFile? {
        val uri = GallerySettings.rootTreeUri
        if (uri.isBlank()) return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull()
    }

    fun folders(context: Context): List<DocumentFile> =
        runCatching { root(context)?.listFiles()?.filter { it.isDirectory } ?: emptyList() }.getOrDefault(emptyList())

    fun createFolder(context: Context, name: String): DocumentFile? =
        runCatching { root(context)?.createDirectory(name) }.getOrNull()

    fun find(context: Context, uriString: String): DocumentFile? =
        runCatching { DocumentFile.fromSingleUri(context, Uri.parse(uriString)) }.getOrNull()

    fun rename(folder: DocumentFile, newName: String): Boolean =
        runCatching { folder.renameTo(newName) }.getOrDefault(false)

    /** 把照片「複製進」SAF 目錄（呼叫端負責刪除原檔） */
    fun copyPhotos(context: Context, target: DocumentFile, photos: List<Photo>): Int {
        var copied = 0
        photos.forEach { p ->
            runCatching {
                val base = p.name.substringBeforeLast('.', p.name).ifBlank { "IMG_" + p.id }
                val file = target.createFile("image/jpeg", base) ?: return@runCatching
                context.contentResolver.openInputStream(GalleryRepo.uriOf(p))?.use { input ->
                    context.contentResolver.openOutputStream(file.uri)?.use { out -> input.copyTo(out) }
                }
                copied++
            }
        }
        return copied
    }

    /** 授權請求意圖（初始位置指到 Pictures） */
    fun pickRootIntent(): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        runCatching {
            intent.putExtra(
                "android.provider.extra.INITIAL_URI",
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3APictures")
            )
        }
        return intent
    }
}
