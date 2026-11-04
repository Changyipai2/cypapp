package com.dsh.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.io.File

/** 全域設定：Bar 顏色、欄數、刪除行為、授權的根目錄 */
object GallerySettings {
    private var file: File? = null

    var topBarColor by mutableStateOf(0xFF37474F.toInt())
        private set
    var bottomBarColor by mutableStateOf(0xFF37474F.toInt())
        private set
    var folderColumns by mutableStateOf(2)
        private set
    var photoColumns by mutableStateOf(3)
        private set
    var trashColumns by mutableStateOf(3)
        private set
    /** system = 跟隨系統 / light = 淺色 / dark = 深色 / custom = 自訂 Bar 顏色 */
    var themeMode by mutableStateOf("custom")
        private set
    /** ask = 每次詢問 / trash = 直接進垃圾桶 / permanent = 直接永久刪除 */
    var deleteBehavior by mutableStateOf("ask")
        private set
    var rootTreeUri by mutableStateOf("")
        private set

    val topBar: Color get() = Color(topBarColor)
    val bottomBar: Color get() = Color(bottomBarColor)
    /** 依 Bar 亮度自動選黑或白字 */
    val onTopBar: Color get() = contrastOn(topBarColor)
    val onBottomBar: Color get() = contrastOn(bottomBarColor)

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "gallery_settings.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            topBarColor = o.optInt("topBar", topBarColor)
            bottomBarColor = o.optInt("bottomBar", bottomBarColor)
            folderColumns = o.optInt("folderColumns", folderColumns).coerceIn(2, 10)
            photoColumns = o.optInt("photoColumns", photoColumns).coerceIn(2, 10)
            trashColumns = o.optInt("trashColumns", trashColumns).coerceIn(2, 10)
            themeMode = o.optString("themeMode", "custom")
            deleteBehavior = o.optString("deleteBehavior", "ask")
            rootTreeUri = o.optString("rootTreeUri", "")
        }
    }

    fun setTopBar(color: Int) { topBarColor = color; save() }
    fun setBottomBar(color: Int) { bottomBarColor = color; save() }
    fun applyFolderColumns(n: Int) { folderColumns = n.coerceIn(2, 10); save() }
    fun applyPhotoColumns(n: Int) { photoColumns = n.coerceIn(2, 10); save() }
    fun applyTrashColumns(n: Int) { trashColumns = n.coerceIn(2, 10); save() }
    fun applyDeleteBehavior(mode: String) { deleteBehavior = mode; save() }
    fun applyThemeMode(mode: String) { themeMode = mode; save() }
    fun applyRootTreeUri(uri: String) { rootTreeUri = uri; save() }

    private fun save() {
        val o = JSONObject()
            .put("topBar", topBarColor)
            .put("bottomBar", bottomBarColor)
            .put("folderColumns", folderColumns)
            .put("photoColumns", photoColumns)
            .put("trashColumns", trashColumns)
            .put("themeMode", themeMode)
            .put("deleteBehavior", deleteBehavior)
            .put("rootTreeUri", rootTreeUri)
        file?.let { runCatching { it.writeText(o.toString()) } }
    }

    private fun contrastOn(argb: Int): Color {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.6) Color(0xFF212121) else Color.White
    }
}

/** 常見色盤（上面 Bar 用） */
val BAR_PALETTE: List<Int> = listOf(
    0xFF37474F.toInt(), // 深藍灰
    0xFF000000.toInt(), // 黑
    0xFF1565C0.toInt(), // 藍
    0xFF00695C.toInt(), // 深綠
    0xFF6A1B9A.toInt(), // 紫
    0xFFAD1457.toInt(), // 桃紅
    0xFF8D6E63.toInt(), // 棕
    0xFF546E7A.toInt(), // 灰藍
    0xFFECEFF1.toInt(), // 淺灰
    0xFFFFFFFF.toInt()  // 白
)
