# DEPLOYMENT.md

Server Assistant 部署檢查清單（以目前程式碼行為為準）。

適用情境：Linux 主機上的 Spring Boot 單體部署（前端靜態檔內嵌於 JAR）。

## 0. 真實設定來源

請以以下檔案與啟動 log 為準，不以舊文件或舊筆記為準：

- `src/main/resources/application.properties`
- `src/main/resources/config/*.properties`
- 啟動 log：`Startup config summary: ...`

## 1. 啟動前必要條件（會被程式阻擋）

以下為 `StartupEnvironmentValidator` 的實際驗證條件：

### 1.1 一定要有

- `GROQ_API_KEY`（或 `spring.ai.openai.api-key`）
- `APP_ENV` 或 `DEPLOY_ENV`（至少一個，且不可空）
- 若 datasource 為 PostgreSQL：`POSTGRES_PASSWORD`

### 1.2 條件式必填

- 若環境為非 local 類（非 `local/dev/development/test`）且 datasource 為 PostgreSQL：
  - `APP_SECURITY_CORS_ALLOWED_ORIGINS`（或 `app.security.cors.allowed-origins`）
  - `SESSION_SIGNATURE_SECRET`（或 `app.security.session.signature-secret`）
- 若 `SPRING_PROFILES_ACTIVE` 含 `prod` / `production` 且 datasource 為 PostgreSQL：
  - `DATABASE_URL`

### 1.3 建議（非硬性）

- `GROQ_API_KEYS`：多 key 輪替
- `CREDENTIAL_STORE_KEY`：Base64 編碼的 32-byte AES key（`openssl rand -base64 32`）

## 2. 主機與系統前置

- Linux 主機
- Java `21+`
- Maven `3.9+`
- PostgreSQL 可連線
- PAM 可用（登入走 Linux PAM）
  - `libpam.so` 可載入
  - `/etc/pam.d/sshd` 存在

建議先檢查：

```bash
uname -a
java -version
./mvnw -v
ldconfig -p | grep libpam || true
test -f /etc/pam.d/sshd && echo ok || echo missing
```

## 3. 設定檔策略

`application.properties` 會載入：

- `classpath:config/command.properties`
- `classpath:config/security.properties`
- `classpath:config/ai.properties`
- `classpath:config/system.properties`
- `optional:file:./.local/config.properties`
- `optional:file:./.local/secrets.properties`

建議：

- 把非機密調整放 `.local/config.properties`
- 把機密（API keys / secret）放 `.local/secrets.properties` 或環境變數

## 4. 建置流程

### 4.1 前端

```bash
cd frontend
npm ci
npm run build
cd ..
```

- 輸出目錄由 `frontend/vite.config.js` 設定為：`../src/main/resources/static`

### 4.2 後端

```bash
./mvnw clean package
```

預期產物：`target/server-assistant-0.0.1-SNAPSHOT.jar`

## 5. 啟動範例

### 5.1 直接啟動

```bash
APP_ENV=production \
SPRING_PROFILES_ACTIVE=prod \
GROQ_API_KEY='***' \
DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/serverassistant' \
POSTGRES_PASSWORD='***' \
APP_SECURITY_CORS_ALLOWED_ORIGINS='https://admin.example.com' \
SESSION_SIGNATURE_SECRET='***' \
java -jar target/server-assistant-0.0.1-SNAPSHOT.jar
```

### 5.2 systemd 範例

`/etc/systemd/system/server-assistant.service`:

```ini
[Unit]
Description=Server Assistant
After=network.target

[Service]
User=serverassistant
WorkingDirectory=/opt/server-assistant
EnvironmentFile=/opt/server-assistant/.env
ExecStart=/usr/bin/java -jar /opt/server-assistant/server-assistant.jar
SuccessExitStatus=143
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

啟用：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now server-assistant
sudo systemctl status server-assistant
```

## 6. 反向代理與安全注意

- 建議只對外開放 80/443，應用埠（預設 8008）走內網
- 反向代理後若要信任 `X-Forwarded-For`：
  - `app.security.login.trust-forward-headers=true`
  - `app.security.login.trusted-proxy-ips=<proxy ip list>`
- `app.security.cors.allowed-origins` 請填實際網域，不要萬用字元

## 7. 上線前 Smoke Test

### 7.1 基本可用性

```bash
curl -i http://127.0.0.1:8008/api/ping
curl -i http://127.0.0.1:8008/
```

### 7.2 CORS

```bash
curl -i -X OPTIONS "http://127.0.0.1:8008/api/status" \
  -H "Origin: https://evil.example.com" \
  -H "Access-Control-Request-Method: GET"
```

預期：不應放行未授權來源。

### 7.3 啟動摘要核對

確認 log 出現並合理：

- `Startup config summary: env=..., datasource=...`
- `Startup config summary: ai.default-model-key=..., ai.model-keys=[...]`
- `Startup config summary: user-context.store=..., user-context.namespace=...`

## 8. 常見失敗對照

- `Missing required primary GROQ API key...`
  - 未設定 `GROQ_API_KEY`
- `Startup blocked: APP_ENV or DEPLOY_ENV must be set and non-empty.`
  - 缺少部署環境標記
- `Startup blocked: POSTGRES_PASSWORD ... must be set and non-empty.`
  - datasource 為 PostgreSQL 但沒給密碼
- `Startup blocked in production: DATABASE_URL must be set and non-empty.`
  - `prod/production` profile 下沒設 `DATABASE_URL`
- `Startup blocked in non-local environment with PostgreSQL datasource: app.security.cors.allowed-origins ...`
  - 非 local + PostgreSQL 缺 CORS 白名單
- `... SESSION_SIGNATURE_SECRET ... must be set and non-empty.`
  - 非 local + PostgreSQL 缺 session 簽章 secret
- `CREDENTIAL_STORE_KEY must be 32 bytes ...`
  - key 格式錯誤，請重新產生 Base64 32-byte key

## 9. Redis（選用）

若要跨 instance 共享 UserContext：

- 設定 `app.user-context.store=redis`
- 設定 Redis 連線（`spring.data.redis.*`）
- 可調整 `app.user-context.redis.namespace`

未啟用時預設 `memory`，重啟或多 instance 不保證上下文一致。
