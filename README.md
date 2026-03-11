# Server Assistant

AI 驅動的 Linux 伺服器管理助手（Spring Boot + Vue）。
提供對話式操作、決定式斜線指令、`!` 直接指令執行、系統監控與管理員儀表板。

## 目前版本與技術棧

- 後端：Spring Boot `3.4.1`、Java `21`、Spring AI `1.1.0`
- 前端：Vue `3.5.x`、Pinia `3.x`、Tailwind CSS `4.x`、Vite（Rolldown）
- 資料庫：PostgreSQL（Flyway migration）
- AI：Groq（OpenAI-compatible API）
- 認證：Linux PAM（`libpam4j`）

## 主要功能

- 對話式 AI 操作（SSE 串流回覆）
- 決定式路由（不經 AI）
  - slash 指令：`/status`、`/docker`、`/gpu`、`/port`、`/top ...` 等
  - `!` 前綴：直接執行 Linux 指令（仍會經過安全檢查）
- 高風險操作確認流程（例如 `rm`、`mv`、`apt`、`mount`）
- 系統監控（CPU / 記憶體 / 磁碟 / GPU / Port / Process）
- Docker 快照資訊
- 管理員功能
  - 使用者操作（新增使用者、SSH key）
  - 聊天記錄 / 指令審計查詢與清理
  - AI 模型清單管理（`/api/admin/models`）
- UserContext 後端可切換 `memory` 或 `redis`

## 啟動前需求

- Linux 主機（PAM 認證依賴 Linux）
- Java `21+`
- Maven `3.9+`（由 `maven-enforcer-plugin` 驗證）
- Node.js `18+` + npm（前端建置/開發）
- PostgreSQL（預設 datasource）
- Groq API key

## 快速開始（本機）

### 1. 設定必要環境變數

```bash
export APP_ENV=local
export GROQ_API_KEY="<your-groq-key>"
export POSTGRES_PASSWORD="<db-password>"

# 可選：多把 key 輪替
export GROQ_API_KEYS="<key-2>,<key-3>"

# 可選：本機通常不需要，若要明確指定可加
export APP_SECURITY_CORS_ALLOWED_ORIGINS="http://localhost:5173"
```

說明：

- `APP_ENV` 或 `DEPLOY_ENV` 至少要有一個。
- 若 datasource 為 PostgreSQL（預設是），`POSTGRES_PASSWORD` 必填。

### 2. 準備資料庫

預設連線：`jdbc:postgresql://localhost:5432/serverassistant`
預設使用者：`serverassistant`

請先建立對應 DB / user，或改 `DATABASE_URL` 與 `spring.datasource.username`。

### 3. 建置前端靜態檔（給 Spring Boot 打包）

```bash
cd frontend
npm install
npm run build
cd ..
```

Vite 產物會輸出到：`src/main/resources/static`。

### 4. 啟動後端

```bash
./mvnw clean package -DskipTests
java -jar target/server-assistant-0.0.1-SNAPSHOT.jar
```

服務預設：`http://localhost:8008`

## 開發模式（前後端分離）

```bash
# Terminal 1: backend
./mvnw spring-boot:run

# Terminal 2: frontend
cd frontend
npm run dev
```

- 前端開發站：`http://localhost:5173`
- `vite.config.js` 已設定 `/api -> http://localhost:8008` 代理。

## 設定來源與優先序

`application.properties` 透過 `spring.config.import` 載入：

1. `src/main/resources/config/*.properties`
2. `./.local/config.properties`（optional）
3. `./.local/secrets.properties`（optional）

高到低覆蓋順序（實務上）：

- 環境變數 / 啟動參數
- `.local/secrets.properties`
- `.local/config.properties`
- `config/*.properties`

以程式實際行為為準的檔案：

- `src/main/resources/application.properties`
- `src/main/resources/config/ai.properties`
- `src/main/resources/config/security.properties`
- `src/main/resources/config/command.properties`
- `src/main/resources/config/system.properties`

## 啟動阻擋條件（程式碼實際檢查）

由 `StartupEnvironmentValidator` 驗證：

- 必須有 `APP_ENV` 或 `DEPLOY_ENV`（若兩者同時存在，值需一致）
- 必須有 `GROQ_API_KEY`（或 `spring.ai.openai.api-key`）
- 若 datasource 是 PostgreSQL，必須有 `POSTGRES_PASSWORD`
- 若是「非 local 類環境 + PostgreSQL」，必須同時設定：
  - `app.security.cors.allowed-origins` / `APP_SECURITY_CORS_ALLOWED_ORIGINS`
  - `app.security.session.signature-secret` / `SESSION_SIGNATURE_SECRET`
- 若 `spring.profiles.active` 含 `prod`/`production` 且 datasource 為 PostgreSQL，必須設定 `DATABASE_URL`

## 斜線指令（目前實作）

- 一般：`/help`、`/status`、`/docker`、`/gpu`、`/port`、`/top cpu [N]`、`/top mem [N]`
- 管理員：`/users`、`/addUser [username]`、`/addSSHKey [username]`、`/mount ...`、`/offload ...`

## API 概覽

- 認證：`/api/login`、`/api/logout`、`/api/status`
- 健康檢查：`/api/ping`
- AI：`/api/ai/stream`、`/api/ai/models`、`/api/ai/history`、`/api/ai/conversations`
- 管理員：`/api/admin/**`、`/api/admin/models/**`
- 系統資訊：`/api/system/info`

## 安全重點

- Session-based auth + Spring Security method security
- CSRF（Cookie token）
- CORS whitelist
- 指令安全驗證：封鎖危險字元、鏈式執行、shell wrapper、inline interpreter 等
- 高風險命令確認機制
- Session 使用簽章（`SESSION_SIGNATURE_SECRET` 建議生產必設）
- 密碼以記憶體加密暫存（`SecureCredentialStore`）

## 測試與檢查

```bash
# backend 測試
./mvnw test

# frontend 單元測試
cd frontend && npm run test:unit
```

## 相關文件

- 部署清單：[DEPLOYMENT.md](DEPLOYMENT.md)
- 架構與細節：[CLAUDE.md](CLAUDE.md)
