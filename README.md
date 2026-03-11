# Server Assistant

AI-powered Linux server management assistant (Spring Boot + Vue).
Provides conversational operations, deterministic slash commands, `!` direct command execution, system monitoring, and an admin dashboard.

## Current Version & Tech Stack

- Backend: Spring Boot `3.4.1`, Java `21`, Spring AI `1.1.0`
- Frontend: Vue `3.5.x`, Pinia `3.x`, Tailwind CSS `4.x`, Vite (Rolldown)
- Database: PostgreSQL (Flyway migrations)
- AI: Groq (OpenAI-compatible API)
- Authentication: Linux PAM (`libpam4j`)

## Key Features

- Conversational AI operations (SSE streaming responses)
- Deterministic routing (bypasses AI)
  - Slash commands: `/status`, `/docker`, `/gpu`, `/port`, `/top ...`, etc.
  - `!` prefix: Direct Linux command execution (still goes through security checks)
- High-risk operation confirmation flow (e.g., `rm`, `mv`, `apt`, `mount`)
- System monitoring (CPU / Memory / Disk / GPU / Ports / Processes)
- Docker snapshot information
- Admin features
  - User operations (add users, SSH keys)
  - Chat history / command audit queries and cleanup
  - AI model list management (`/api/admin/models`)
- UserContext backend switchable between `memory` and `redis`

## Prerequisites

- Linux host (PAM authentication requires Linux)
- Java `21+`
- Maven `3.9+` (enforced by `maven-enforcer-plugin`)
- Node.js `18+` + npm (frontend build/development)
- PostgreSQL (default datasource)
- Groq API key

## Quick Start (Local)

### 1. Set Required Environment Variables

```bash
export APP_ENV=local
export GROQ_API_KEY="<your-groq-key>"
export POSTGRES_PASSWORD="<db-password>"

# Optional: multiple key rotation
export GROQ_API_KEYS="<key-2>,<key-3>"

# Optional: usually not needed locally, but can be set explicitly
export APP_SECURITY_CORS_ALLOWED_ORIGINS="http://localhost:5173"
```

Notes:

- At least one of `APP_ENV` or `DEPLOY_ENV` must be set.
- If the datasource is PostgreSQL (the default), `POSTGRES_PASSWORD` is required.

### 2. Prepare the Database

Default connection: `jdbc:postgresql://localhost:5432/serverassistant`
Default user: `serverassistant`

Create the corresponding database and user first, or override `DATABASE_URL` and `spring.datasource.username`.

### 3. Build Frontend Static Files (for Spring Boot packaging)

```bash
cd frontend
npm install
npm run build
cd ..
```

Vite output goes to: `src/main/resources/static`.

### 4. Start the Backend

```bash
./mvnw clean package -DskipTests
java -jar target/server-assistant-0.0.1-SNAPSHOT.jar
```

Default service URL: `http://localhost:8008`

## Development Mode (Separate Frontend & Backend)

```bash
# Terminal 1: backend
./mvnw spring-boot:run

# Terminal 2: frontend
cd frontend
npm run dev
```

- Frontend dev server: `http://localhost:5173`
- `vite.config.js` is configured to proxy `/api -> http://localhost:8008`.

## Configuration Sources & Priority

`application.properties` loads via `spring.config.import`:

1. `src/main/resources/config/*.properties`
2. `./.local/config.properties` (optional)
3. `./.local/secrets.properties` (optional)

Override priority (highest to lowest):

- Environment variables / startup arguments
- `.local/secrets.properties`
- `.local/config.properties`
- `config/*.properties`

Authoritative configuration files:

- `src/main/resources/application.properties`
- `src/main/resources/config/ai.properties`
- `src/main/resources/config/security.properties`
- `src/main/resources/config/command.properties`
- `src/main/resources/config/system.properties`

## Startup Blocking Conditions (Actual Code Checks)

Validated by `StartupEnvironmentValidator`:

- `APP_ENV` or `DEPLOY_ENV` must be set (if both are present, values must match)
- `GROQ_API_KEY` (or `spring.ai.openai.api-key`) is required
- If the datasource is PostgreSQL, `POSTGRES_PASSWORD` is required
- For non-local environments + PostgreSQL, the following must also be set:
  - `app.security.cors.allowed-origins` / `APP_SECURITY_CORS_ALLOWED_ORIGINS`
  - `app.security.session.signature-secret` / `SESSION_SIGNATURE_SECRET`
- If `spring.profiles.active` includes `prod`/`production` and the datasource is PostgreSQL, `DATABASE_URL` is required

## Slash Commands (Current Implementation)

- General: `/help`, `/status`, `/docker`, `/gpu`, `/port`, `/top cpu [N]`, `/top mem [N]`
- Admin: `/users`, `/addUser [username]`, `/addSSHKey [username]`, `/mount ...`, `/offload ...`

## API Overview

- Auth: `/api/login`, `/api/logout`, `/api/status`
- Health check: `/api/ping`
- AI: `/api/ai/stream`, `/api/ai/models`, `/api/ai/history`, `/api/ai/conversations`
- Admin: `/api/admin/**`, `/api/admin/models/**`
- System info: `/api/system/info`

## Security Highlights

- Session-based auth + Spring Security method security
- CSRF (cookie token)
- CORS whitelist
- Command security validation: blocks dangerous characters, command chaining, shell wrappers, inline interpreters, etc.
- High-risk command confirmation mechanism
- Session signature (`SESSION_SIGNATURE_SECRET` recommended for production)
- Passwords temporarily stored with in-memory encryption (`SecureCredentialStore`)

## Testing

```bash
# Backend tests
./mvnw test

# Frontend unit tests
cd frontend && npm run test:unit
```

## Related Documentation

- Deployment checklist: [DEPLOYMENT.md](DEPLOYMENT.md)
- Architecture & details: [CLAUDE.md](CLAUDE.md)
