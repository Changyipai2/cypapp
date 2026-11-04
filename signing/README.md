# 簽章（可隨時替換）

這個資料夾讓 APK 的簽章固定下來，這樣每次更新都能直接覆蓋安裝（App 資料不會被清掉）。

## 目前
- `keystore.b64`：base64 的 PKCS12 金鑰庫（由 CI 產生後提交）
- `signing.properties`：storePassword / keyAlias / keyPassword
- 建置時 Gradle 會把 `keystore.b64` 解碼成 `signing/keystore.p12`（不進版控），debug 與 release 都用它簽。

## 要換簽章
1. 換掉 `keystore.b64`（base64 的新金鑰庫）
2. 改 `signing.properties` 的三個值
3. push，CI 重建成新版

> 注意：換簽章後使用者必須先卸載舊版才能安裝（簽章不同 Android 會拒絕覆蓋）。
> `keystore.b64` 放在公開 repo 等於任何人都能簽出「你的 App」，要給別人用之前務必換成私密流程（例如 CI secret）。

<!-- signing verification: 1793798236 -->
