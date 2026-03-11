# DEPLOYMENT.md

Server Assistant deployment checklist (based on actual code behavior).

Applicable scenario: Spring Boot monolith deployment on a Linux host (frontend static files embedded in the JAR).

## 0. Authoritative Configuration Sources

Refer to the following files and startup logs as the source of truth — not legacy docs or notes:

- `src/main/resources/application.properties`
- `src/main/resources/config/*.properties`
- Startup log: `Startup config summary: ...`

## 1. Startup Prerequisites (Enforced by Application)

The following are actual validation checks performed by `StartupEnvironmentValidator`:

### 1.1 Always Required

- `GROQ_API_KEY` (or `spring.ai.openai.api-key`)
- `APP_ENV` or `DEPLOY_ENV` (at least one, must not be empty)
- If the datasource is PostgreSQL: `POSTGRES_PASSWORD`

### 1.2 Conditionally Required

- For non-local environments (not `local/dev/development/test`) with PostgreSQL datasource:
  - `APP_SECURITY_CORS_ALLOWED_ORIGINS` (or `app.security.cors.allowed-origins`)
  - `SESSION_SIGNATURE_SECRET` (or `app.security.session.signature-secret`)
- If `SPRING_PROFILES_ACTIVE` includes `prod` / `production` with PostgreSQL datasource:
  - `DATABASE_URL`

### 1.3 Recommended (Not Enforced)

- `GROQ_API_KEYS`: Multiple key rotation
- `CREDENTIAL_STORE_KEY`: Base64-encoded 32-byte AES key (`openssl rand -base64 32`)

## 2. Host & System Prerequisites

- Linux host
- Java `21+`
- Maven `3.9+`
- PostgreSQL accessible
- PAM available (login uses Linux PAM)
  - `libpam.so` loadable
  - `/etc/pam.d/sshd` exists

Recommended pre-checks:

```bash
uname -a
java -version
./mvnw -v
ldconfig -p | grep libpam || true
test -f /etc/pam.d/sshd && echo ok || echo missing
```

## 3. Configuration File Strategy

`application.properties` loads:

- `classpath:config/command.properties`
- `classpath:config/security.properties`
- `classpath:config/ai.properties`
- `classpath:config/system.properties`
- `optional:file:./.local/config.properties`
- `optional:file:./.local/secrets.properties`

Recommendations:

- Place non-secret overrides in `.local/config.properties`
- Place secrets (API keys / secrets) in `.local/secrets.properties` or environment variables

## 4. Build Process

### 4.1 Frontend

```bash
cd frontend
npm ci
npm run build
cd ..
```

- Output directory is configured in `frontend/vite.config.js` as: `../src/main/resources/static`

### 4.2 Backend

```bash
./mvnw clean package
```

Expected artifact: `target/server-assistant-0.0.1-SNAPSHOT.jar`

## 5. Startup Examples

### 5.1 Direct Startup

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

### 5.2 systemd Example

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

Enable the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now server-assistant
sudo systemctl status server-assistant
```

## 6. Reverse Proxy & Security Notes

- Only expose ports 80/443 externally; keep the application port (default 8008) on the internal network
- To trust `X-Forwarded-For` behind a reverse proxy:
  - `app.security.login.trust-forward-headers=true`
  - `app.security.login.trusted-proxy-ips=<proxy ip list>`
- Set `app.security.cors.allowed-origins` to actual domains — do not use wildcards

## 7. Pre-Launch Smoke Test

### 7.1 Basic Availability

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

Expected: Unauthorized origins should not be allowed.

### 7.3 Startup Summary Verification

Confirm the following appears in logs and looks correct:

- `Startup config summary: env=..., datasource=...`
- `Startup config summary: ai.default-model-key=..., ai.model-keys=[...]`
- `Startup config summary: user-context.store=..., user-context.namespace=...`

## 8. Common Failure Reference

- `Missing required primary GROQ API key...`
  - `GROQ_API_KEY` is not set
- `Startup blocked: APP_ENV or DEPLOY_ENV must be set and non-empty.`
  - Missing deployment environment identifier
- `Startup blocked: POSTGRES_PASSWORD ... must be set and non-empty.`
  - Datasource is PostgreSQL but password is not provided
- `Startup blocked in production: DATABASE_URL must be set and non-empty.`
  - `DATABASE_URL` not set under `prod/production` profile
- `Startup blocked in non-local environment with PostgreSQL datasource: app.security.cors.allowed-origins ...`
  - Non-local + PostgreSQL missing CORS whitelist
- `... SESSION_SIGNATURE_SECRET ... must be set and non-empty.`
  - Non-local + PostgreSQL missing session signature secret
- `CREDENTIAL_STORE_KEY must be 32 bytes ...`
  - Key format is incorrect — regenerate with a Base64 32-byte key

## 9. Redis (Optional)

To share UserContext across instances:

- Set `app.user-context.store=redis`
- Configure Redis connection (`spring.data.redis.*`)
- Optionally adjust `app.user-context.redis.namespace`

When not enabled, defaults to `memory` — context consistency is not guaranteed across restarts or multiple instances.
