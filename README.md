# 吃了什麼（eat_wat）

一個自架的飲食記錄 App，會自動從 Abbott LibreLinkUp 拉血糖資料，讓你對照每一餐吃完之後血糖怎麼變化。

- **每日**：24 小時血糖曲線，餐點標在曲線上，可以看平均、目標範圍內比例、最高和最低值。
- **記錄飲食**：記錄時間、餐別（依時間自動判斷）、吃了什麼、醣類克數、備註和照片。
- **餐後分析**：每一餐的餐前值、高峰、上升幅度、到高峰的時間、2 小時後的變化，以及 iAUC。資料不完整時會標 ⚠。
- **對照**：勾選多餐，把它們的餐後曲線對齊在用餐時間疊在一起比較。可以搜尋，例如輸入「拉麵」比較每次吃拉麵的反應。
- **CSV 匯入**：匯入 LibreView 網站下載的歷史資料。
- 介面為繁體中文、單位 mg/dL，手機可以「加入主畫面」當 App 用（PWA），支援深色模式。

技術：Bun + SQLite（`bun:sqlite`），前端是純 HTML/JS，沒有 build 步驟，也沒有執行期相依套件。

## 先決條件：LibreLinkUp 追蹤者帳號

Abbott 沒有公開 LibreView API。這個 App 用的是 **LibreLinkUp**（給家人追蹤用的 App）的非官方 API，所以你需要準備：

1. 一個**另外的** LibreLinkUp 帳號（用另一個 email 註冊）。
2. 在你自己的 FreeStyle Libre / LibreLink App 裡，到「連線的應用程式 → LibreLinkUp」邀請這個帳號。
3. 用這個帳號登入 LibreLinkUp App 並**接受邀請**，同時接受使用條款。如果沒接受條款，API 登入會停在 `step` 狀態。

`.env` 裡要填的是這個**追蹤者帳號**的帳號密碼。

## 執行

```sh
bun install
cp .env.example .env    # 填入 LLU_EMAIL / LLU_PASSWORD
bun run start           # http://127.0.0.1:3000
```

第一次同步成功後，終端機會印出 `first graph item (raw): {...}`。如果之後的資料時間看起來偏了幾小時，把這行貼出來對照（見下方「已知限制」）。

先不連 Abbott、用假資料看看：

```sh
bun run mock            # 資料存在 ./data-mock，不會碰到 ./data
```

## 手機使用

預設只綁定 `127.0.0.1`。要讓手機在同一個區網連線：

```sh
HOST=0.0.0.0 APP_TOKEN=<一串長的隨機字串> bun run start
```

用手機開一次 `http://<電腦 IP>:3000/?token=<APP_TOKEN>`，之後會記在 cookie 裡，再用「加入主畫面」安裝。沒有設定 `APP_TOKEN` 時，伺服器不會綁定在非本機位址上。

> 注意：這裡沒有 HTTPS。如果要從外網連線，請放在 Tailscale、Cloudflare Tunnel 或有 TLS 的反向代理後面。

## 資料怎麼累積

- LibreLinkUp 的 graph API **只回傳最近約 12 小時**的資料。伺服器每 5 分鐘輪詢一次，把資料存進 SQLite，所以要**一直開著**才能累積完整歷史。
- 停機超過 12 小時造成的空洞，或開始使用之前的資料，可以到 libreview.com →「下載血糖資料」，再到「設定」頁匯入 CSV。和 API 資料重疊的時段（±7 分鐘內已有資料）會自動略過。
- 資料都放在 `DATA_DIR`（預設 `./data`）：`eat_wat.sqlite` 和 `photos/`。要備份就複製這個資料夾。

## 餐後指標怎麼算

| 指標 | 定義 |
|---|---|
| 餐前 | 用餐前 30 分鐘內最後一筆讀值。如果沒有，就用餐後 10 分鐘內的第一筆，並標記 |
| 高峰／到高峰 | 餐後 3 小時內的最高值，以及從用餐到最高值經過的時間 |
| 上升 | 高峰 − 餐前 |
| 2 小時變化 | 餐後 120 分鐘的內插值 − 餐前 |
| iAUC | 餐後 3 小時內，高於餐前值那部分的面積（梯形法），單位 mg/dL·小時 |

以下情況會標 ⚠（低可信度）：

- 有超過 30 分鐘的缺資料
- 餐前沒有讀值
- 讀值涵蓋不足
- 前後 3 小時內有其他餐（餐前值可能還在上一餐的曲線上）
- 3 小時還沒過完

## 已知限制

- 這是**非官方 API**，Abbott 隨時可能更改。目前依照社群實作（例如 pylibrelinkup），要求 `version: 4.16.0` 這個 header，以及 `account-id = sha256(user.id)`。如果 API 開始拒絕請求，先試著在 `.env` 把 `LLU_VERSION` 調高。
- `FactoryTimestamp` 目前被當成 UTC，格式是 `M/D/YYYY h:mm:ss AM`。這是依照社群文件推斷的，**還沒有用真的帳號驗證過**。如果最新讀值看起來在未來，或剛好偏了整數小時，「設定」頁會顯示「時間警告」。
- CSV 的時間是裝置的當地時間，匯入時用瀏覽器的時區換算。
- 這個 App 只是個人記錄工具，**不是醫療器材**。

## 開發

```sh
bun test          # 時間解析、CSV（mg/dL 和 mmol/L）、餐後分析、mock API 的整條流程
bun run check     # TypeScript 型別檢查
```

---

# Android App（`android/`）

原生 Android 版（Kotlin + Jetpack Compose），在手機背景自己同步，不需要電腦或伺服器。功能和網頁版相同：每日曲線、記錄飲食（可拍照或從相簿選）、餐後分析、多餐對照、匯入 CSV。

- **背景同步**：用 WorkManager 每 15 分鐘抓一次（需要網路），重開機後也會自動恢復。LibreLinkUp 的 graph 本來就是每 15 分鐘一個點、涵蓋 12 小時，所以不會漏資料。
- **帳密**：在「設定」頁輸入，以 Android Keystore 的 AES-GCM 金鑰加密，存在手機上。登入 session 也會保存，不會每次同步都重新登入。
- **錯誤處理**：帳密錯誤、需要接受條款、沒有分享連線這三種錯誤，會暫停背景同步，直到你修改帳密或手動按「立即同步」。遇到 429（請求太頻繁）會等 30 分鐘再試。
- **省電**：部分手機品牌（小米、OPPO、vivo、三星等）會強制關掉背景工作。如果「上次成功」常常停住，到「設定 → 電池最佳化設定」把這個 App 設成不受限制。
- **備份**：飲食紀錄和血糖資料庫會跟著 Android 系統備份走。照片只在換機搬移時一起帶走，不進雲端備份，因為雲端備份有 25 MB 上限。帳密不備份，換手機後要重新輸入，因為它綁定這支手機的 Keystore。
- **暫停後自動重試**：因錯誤而暫停的背景同步，仍會每 6 小時自動再試一次，避免短暫錯誤讓同步永久停擺。

## 建置與安裝

需要 JDK 17 以上，以及 Android SDK 36。

```sh
cd android
./gradlew assembleRelease          # 產出 app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # 核心邏輯單元測試
```

沒有接 adb 的話，也可以把 APK 傳到手機（例如雲端硬碟或 LocalSend），直接點開安裝。第一次安裝要允許「安裝未知應用程式」。

release 版用這台電腦的 debug 金鑰（`~/.android/debug.keystore`）簽章。之後要更新 App，必須用同一把金鑰簽章才能覆蓋安裝，請保留這個檔案。

debug 版的「設定」頁最下面有「假資料模式」，可以在不連 Abbott 的情況下測試。release 版沒有這個開關。

手機版的資料和網頁版（`./data`）是分開的。要把歷史資料帶到手機，請在手機上匯入 LibreView CSV。
