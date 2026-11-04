# 兩個離線 Android App（Kotlin / Jetpack Compose）

同一個 repo 放兩個獨立專案，各自產生一個 APK。全部離線、資料只留在手機。

| 目錄 | App | 用途 |
| --- | --- | --- |
| `ledger/` | 簡記帳 | 收支記錄、分類、月度統計、預算提醒、多帳本、CSV 匯出／匯入（可讀其他記帳 App 的 CSV）、JSON 備份還原 |
| `gallery/` | 圖片櫃 | 依資料夾瀏覽照片、翻頁縮放、編輯（裁切／旋轉／翻轉／畫筆／文字）、刪除（系統垃圾桶確認）、圖片詳細資訊、存到指定資料夾 |

## 怎麼拿到 APK（手機就能完成）

1. 到 GitHub 建一個新的 repository（公開或私有都可以，公開的 Actions 完全免費）。
2. 把這個資料夾裡的**全部檔案**上傳到該 repo 的 `main` 分支（要保持目錄結構）。
3. 上傳後自動開始建置：repo → **Actions** → `Build APKs` → 等兩個 job 都變綠勾（第一次約 6～10 分鐘）。
4. 點進去 → 頁面最下方 **Artifacts** → 下載 `ledger-debug-apk` / `gallery-debug-apk`（zip 檔）。
5. 手機解壓後點 APK 安裝；第一次要允許「安裝未知的應用程式」。

> 建置產物（Artifacts）只保留 90 天，要重下就重新跑一次 workflow（Actions → Build APKs → Run workflow）。
> 更新版本前若簽章不同，Android 會要求先卸載舊版再安裝（CI 會快取 debug keystore，通常不會遇到）。

## 手動建置（有電腦時）

```bash
# 需要 JDK 17 與 Android SDK 34
cd ledger   && gradle :app:assembleDebug
cd gallery  && gradle :app:assembleDebug
```

APK 產出於 `app/build/outputs/apk/debug/app-debug.apk`。

## 資料存放位置

- 簡記帳：`filesDir/ledger.json`（單一 JSON，包含帳本、記錄、預算）。匯出檔寫在 App 的外部檔案目錄，透過系統分享面板存到雲端或傳給自己。
- 圖片櫃：不搬動你的照片；編輯後**另存新檔**到 `Pictures/圖片櫃/`，原圖保持不變。刪除走 Android 系統的垃圾桶確認流程。

## 簡記帳 CSV 匯入說明

- 支援逗號 / 分號 / Tab 分隔，自動判斷 UTF-8 或 Big5。
- 自動偵測「日期、金額、分類、備註、類型」欄位；也支援「支出金額 / 收入金額」分成兩欄的格式。
- 匯入前可手動改欄位對應，並預覽前 3 列。
- 沒有類型欄位時：可用「正數視為收入」開關；負數一律視為支出。
- 重複資料（同日期、金額、分類、備註）自動跳過。

## 已知限制

- 圖片編輯器是「直接畫在點陣圖上」的輕量版：蓋章式編輯，可逐步復原 10 步；沒有圖層。
- 編輯後存成新檔，不覆寫原圖（避免 Android 11+ 的寫入授權流程）。
- 記帳統計圖為長條比例圖，沒有折線圖與匯出圖片。
- 兩個 App 都只在本機，沒有雲端同步（依需求設計為離線）。
