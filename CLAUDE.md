# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Linux server management assistant with AI capabilities. Users can interact with a conversational AI to manage server operations, execute commands, monitor system status, and perform administrative tasks through a web interface. All UI text, system prompts, and error messages are in **Traditional Chinese**.

**Tech Stack:**
- Backend: Spring Boot 3.4.1 (Java 21) with Spring AI 1.1.0
- Frontend: Vue 3.5.24 + Vite (Rolldown variant) + Tailwind CSS 4 + Pinia 3
- Database: PostgreSQL (`localhost:5432/serverassistant`) + Flyway migrations
- AI Provider: Groq API (OpenAI-compatible)
- System Monitoring: OSHI 6.6.1
- Authentication: Linux PAM (via libpam4j 1.11)

## Development Commands

### Backend (Maven)
```bash
# Run the application
./mvnw spring-boot:run

# Build the application
./mvnw clean package

# Run tests
./mvnw test

# Skip tests during build
./mvnw clean package -DskipTests
```

The backend runs on port **8008** (configured in application.properties).

### Frontend (npm)
```bash
cd frontend

# Development server (with hot reload)
npm run dev

# Build for production (outputs to ../src/main/resources/static)
npm run build

# Preview production build
npm run preview
```

Frontend dev server runs on port **5173** and proxies `/api/*` requests to `localhost:8008`.

## Architecture

### Backend Structure

**Package:** `com.linux.ai.serverassistant`

- **controller/**: REST API endpoints
  - `ChatController`: AI chat streaming (`/api/ai/stream`), history, confirm/cancel commands, conversations
  - `LoginController`: PAM-based authentication (`/api/login`, `/api/status`, `/api/logout`)
  - `SystemController`: System IP info (`/api/system/info`)
  - `AdminController`: Admin-only operations (user list, history, audit, purge, reset)
  - `ModelAdminController`: AI model CRUD (`/api/admin/models`)
  - `TestController`: Health check (`/api/ping`)

- **service/**: Business logic (modular, organized by domain)
  - `SystemService`: Facade delegating to specialized services below
  - `AiModelService`: AI model configuration management
  - `JpaChatMemory`: Spring AI ChatMemory implementation (JPA-backed, text-only)
  - `LinuxAuthService`: Linux PAM authentication

  - **service/ai/**:
    - `ChatService`: Core AI chat orchestration with streaming, deterministic router chain, and confirmation flow
    - `ConversationService`: Conversation CRUD and ownership verification
    - `AiStreamRetryCoordinator`: Handles intelligent retry with temperature escalation (0.2→0.3), max 1 empty retry, max 3 HTTP retries
    - `ConfirmCommandHandler`: Handles confirm/cancel command REST endpoints
    - `ResponseFormattingUtils`: Utilities for formatting AI responses
    - `EmptyModelResponseException`, `ModelHttpStatusException`: AI-specific exceptions

  - **service/command/**:
    - `CommandExecutionService`: Secure command execution, audit logging, command type routing
    - `CommandConfirmationService`: Manages pending confirmation lifecycle
    - `PendingConfirmationManager`: Stores pending commands with 10-min TTL
    - `SlashCommandRouter` (Order 0): Deterministic routing for `/status`, `/gpu`, `/docker`, `/port`, `/top`, `/users`, `/addUser`, `/addSSHKey`, `/mount`, `/offload`, `/help`
    - `ExclamationCommandRouter` (Order 5): Raw shell commands prefixed with `!` (e.g., `!docker ps`)
    - `UserManagementRouter` (Order 10): Multi-step interactive flows for user operations
    - `DiskUsageRouter` (Order 20): Disk usage queries
    - `DeleteRouter` (Order 30): File deletion with confirmation flow
    - `DeterministicRouter`: Interface all routers implement; `ChatService` auto-discovers via Spring
    - `SudoCredentialInjector`: Injects sudo credentials for privileged operations
    - `OffloadJobService`, `CommandJobService`: Background job scheduling for directory copy + symlink

  - **service/system/**:
    - `SystemMetricsService`: Uptime, load, memory, temperature, disk
    - `ProcessMonitorService`: Top CPU/memory processes
    - `NetworkMonitorService`: Network interfaces
    - `GpuStatusService`: GPU monitoring (nvidia-smi with timeouts, OSHI fallback)
    - `DiskMountService`: Disk formatting and mounting operations
    - `PortStatusService`: Listening ports (TCP/UDP)

  - **service/docker/**:
    - `DockerSnapshotService`: Docker state snapshots

  - **service/file/**:
    - `FileOperationService`: Safe file operations with allowlist path validation and 1MB write limit

  - **service/user/**:
    - `UserManagementService`: User lifecycle (list, add, delete, sudo), SSH key management via ProcessBuilder stdin
    - `UserCommandConstants`: Constants for user management commands

  - **service/security/**:
    - `AdminAuthorizationService`: Admin role checking (sudo group membership, cached)
    - `LoginAttemptService`: Brute-force protection (10 fails/IP, 8 fails/user, 10-min window, 15-min lock)
    - `ChatRateLimiter`: 500 req/user per 5-min window, 20 global concurrent streams
    - `SlashCommandRateLimiter`: 50 req/user per 1-min window
    - `FileOperationRateLimiter`: 20 req/user per 1-min window
    - `UserTpmLimiter`: Per-user token-per-minute budget (assumes up to 10 concurrent users)
    - `SessionAuthenticationSignatureService`: Signed session validation for non-dev deployments

- **config/**: Spring configuration
  - `AiToolsConfig`: Defines AI function tools (executeLinuxCommand, listDirectory, readFileContent, createDirectory, writeFileContent, manageUsers, manageSshKeys); each tool has `@Description` in Chinese and requires a `contextKey` parameter
  - `SecurityConfig`: Spring Security (CSRF cookie, CORS whitelist, CSP headers, role-based admin access)
  - `AiModelProperties`: AI model configuration from properties
  - `CommandExecutionConfig`: Thread pool config (8 parallel jobs, 80-item queue; 32-thread output capture pool)
  - `SystemStatusConfig`: System monitoring cache configuration
  - `UserContextStoreConfiguration`: Switches between in-memory and Redis UserContextStore
  - `StartupEnvironmentValidator`: Validates required environment on startup
  - `WebMvcConfig`: MVC configuration (static resources, etc.)

- **security/**: Security utilities
  - `CommandValidator`: Command validation (dangerous chars, chaining, pipe injection, high-risk blocklist, protected paths)
  - `CommandTokenizer`: Parses command strings safely
  - `PathValidator`: Path-based access control checks
  - `DockerCommandParser`: Parses and validates Docker commands
  - `SecureCredentialStore`: AES-256-GCM encrypted credential storage with auto-expiry

- **entity/**: JPA entities
  - `ChatMessage`: Conversation history (conversationId, username, messageType, content, createdAt)
  - `CommandLog`: Audit log (username, command, executionTime, output, success, exitCode, commandType)
  - `AiModelConfig`: Configurable AI model definitions (id/key, name, tpm, label, category, enabled)
  - `CommandType`: Enum (MODIFY, READ, TRUSTED, etc.)

- **repository/**: JPA repositories
  - `ChatMessageRepository`: Includes `existsByConversationIdAndUsername` for ownership checks
  - `CommandLogRepository`, `AiModelConfigRepository`

- **dto/**: Data transfer objects
  - `ApiResponse<T>`: Unified REST response wrapper
  - `LoginRequest`: Authentication request
  - `ChatStreamRequest`: Chat request (message, conversationId, model)
  - `ConfirmCommandRequest`, `CancelCommandRequest`: Confirmation flow DTOs

- **exception/**: Error handling
  - `GlobalExceptionHandler`: Centralized exception handling (no sensitive info leakage in 500 responses)
  - `RateLimitExceededException`: Thrown when rate limits exceeded
  - `ConcurrentStreamLimitExceededException`: Thrown when global stream limit exceeded

- **util/**: Utilities
  - `UserContext`: Identity resolver using HttpSession + contextKey; no ThreadLocal fallback
  - `UserContextStore` (interface): Abstraction for context key storage
  - `InMemoryUserContextStore`: Default in-memory implementation (TTL 5 min, hard TTL 1 hour)
  - `RedisUserContextStore`: Optional Redis-backed implementation
  - `CommandMarkers`: Parses `[CMD:::...:::]` markers from AI responses
  - `ToolResultUtils`: Tool result formatting utilities
  - `UsernameUtils`: Username sanitization/validation helpers

### Frontend Structure

**Directory:** `frontend/src`

- **components/**: Vue 3 components (`<script setup>` Composition API)
  - `App.vue`: Main application container
  - `ChatInterfaceLayout.vue`: Layout wrapper for chat interface
  - `MessageList.vue`: Chat message display (markdown-it with `html: false`, DOMPurify, highlight.js)
  - `ChatHeader.vue`: Chat interface header
  - `Login.vue`: Authentication form
  - `Sidebar.vue`: Conversation history sidebar (with delete, export)
  - `AdminDashboard.vue`: Admin panel (lazy-loaded)
  - `ControlPanel.vue`: Control panel UI (model switching)
  - `ChatCommandRequest.vue`: Command confirmation dialogs
  - `ModelSwitchToast.vue`: Toast notification for model changes
  - `NetworkOfflineBanner.vue`: Offline status banner
  - `ShortcutHelpDialog.vue`: Keyboard shortcuts help dialog
  - `UndoDeleteToast.vue`: Undo conversation deletion toast

- **composables/**:
  - `useChat.js`: Streaming chat logic (EventSource SSE), command marker parsing, error handling, abort controller
  - `useCommandConfirmation.js`: Handles `[CMD:::...:::]` markers, calls confirm/cancel endpoints
  - `useConversationDeletion.js`: Multi-step deletion with undo support
  - `useConversationExport.js`: Export chat as markdown/JSON
  - `useKeyboardShortcuts.js`: Keyboard navigation (Enter to send, shortcuts)
  - `useMessageEditing.js`: In-message editing drafts
  - `useModelSwitchToast.js`: Model switch notifications
  - `useNetworkStatus.js`: Online/offline detection
  - `useSwipeGesture.js`: Swipe gesture for mobile sidebar

- **stores/** (Pinia):
  - `authStore.js`: Authentication state (isLoggedIn, currentUser, isAdmin)
  - `chatStore.js`: Chat state (messages, userInput, isProcessing, model, hasMoreHistory, draft management)
  - `conversationStore.js`: Conversation management (currentConversationId, conversations, isSidebarOpen)
  - `systemStore.js`: System state (serverIp, availableModels, statusMessage)
  - `themeStore.js`: Theme management (light/dark mode persistence)

- **api/**: API layer
  - `httpClient.js`: Axios with CSRF token handling
  - `authApi.js`, `chatApi.js`, `systemApi.js`: Domain-specific API calls
  - `index.js`: Module exports

- **utils/**:
  - `commandMarkers.js`: Command marker parsing (`[CMD:::...:::]`)
  - `exportUtils.js`: Conversation export logic
  - `messageActions.js`: Message action handlers
  - `modelSwitch.js`: Model switching utilities

- **config/**: `app.config.js` — Application constants
- **constants/**: `index.js` — Global constants
- **main.js**: Vue application entry point

Tests live under `__tests__/` subdirectories in components/, composables/, stores/, and utils/.

### AI Integration

The system uses **Spring AI** with function calling to integrate AI with system operations:

1. **Tools**: Defined in `AiToolsConfig` as Spring `@Bean` functions with `@Description` annotations in Chinese; each tool requires a `contextKey` parameter for cross-thread user identity
2. **Chat Flow**:
   - User sends message → `ChatController.streamChat()` → `ChatService.streamChat()`
   - Deterministic router chain runs first (by `@Order`): SlashCommand → Exclamation → UserManagement → DiskUsage → Delete → AI fallback
   - AI can call tools (e.g., `executeLinuxCommand`, `manageUsers`, `manageSshKeys`)
   - Tools execute system operations via `SystemService` facade
   - Results streamed back to frontend via SSE
3. **Memory**: JPA-backed chat memory (`JpaChatMemory`) storing text-only content (tool calls are NOT persisted); max 300 messages per conversation
4. **Safety**: High-risk operations require explicit user confirmation via `[CMD:::command:::]` protocol
5. **Confirmation Flow**: Direct `POST /api/ai/confirm-command` and `/api/ai/cancel-command` endpoints bypass AI (frontend calls directly)
6. **Retry**: `AiStreamRetryCoordinator` retries on empty responses (max 1) and HTTP errors (max 3), escalating temperature

### Security Model

- **Authentication**: Linux PAM-based (via libpam4j, `sshd` service), session-backed; brute-force protected by `LoginAttemptService`
- **Authorization**: Admin = member of the `sudo` group on the Linux system (checked by `AdminAuthorizationService.checkSudoGroup()`, result cached)
- **Command Validation** (`CommandValidator`):
  - Dangerous characters: `; & \` $ > < \n \r \x00`
  - Command chaining detection (`||`, `&&`)
  - Blocked commands (rejected entirely): `iptables`, `ip6tables`, `ufw`, `dd`, `mkfs`, `fdisk`, `reboot`, `shutdown`, `halt`, `poweroff`, `init`
  - High-risk commands (require confirmation): `rm`, `rmdir`, `mv`, `cp`, `rsync`, `tar`, `zip`, `unzip`, `chmod`, `chown`, `useradd`, `userdel`, `usermod`, `groupadd`, `groupdel`, `passwd`, `mount`, `umount`, `crontab`, `apt`, `yum`, `dnf`
  - Protected paths (blocks rm/rmdir): `/`, `/etc`, `/bin`, `/sbin`, `/usr`, `/lib`, `/lib64`, `/boot`, `/dev`, `/proc`, `/sys`, `/run`, `/home`, `/root`, `/var`, `/opt`, `/tmp`
  - Pipe whitelist: `grep`, `cut`, `sort`, `uniq`, `head`, `tail`, `wc`, `tr`, `more`, `less`, `cat`
- **File Path Security** (`FileOperationService`): Allowlist-based
  - Read allowed: `/home/`, `/tmp/`, `/var/log/`, `/var/www/`, `/opt/`, `/srv/`, `/usr/local/`, `/etc/nginx/`, `/etc/apache2/`
  - Write allowed: `/home/`, `/tmp/`, `/var/www/`, `/opt/`, `/srv/`
  - Write size limit: 1MB
- **Rate Limiting**: Chat (500 req/user/5min, 20 concurrent streams), slash commands (50 req/user/min), file ops (20 req/user/min), TPM budgeting per user
- **Credential Safety**: `chpasswd` and SSH key operations use ProcessBuilder + stdin (no shell injection)
- **Command Execution Types**:
  - `executeLinuxCommand`: User-originated commands → validated by CommandValidator
  - `executeTrustedRootCommand`: Backend-generated commands (Docker, cron, system updates) → bypasses CommandValidator
  - `executeConfirmedLinuxCommand`: Confirmed high-risk commands → requires username + pending confirmation ownership check
- **Conversation Ownership**: History endpoints verify `conversationId` belongs to authenticated user
- **Audit**: All commands logged to `CommandLog` table (exitCode, commandType MODIFY/READ)
- **CSRF**: Cookie-based token for SPA compatibility; exemptions: `/api/login`, `/api/status`
- **Security Headers**: CSP, HSTS, X-Frame-Options, X-Content-Type-Options, X-XSS-Protection

### Key Configurations

**Configuration file hierarchy** (later overrides earlier):
1. `src/main/resources/config/*.properties` — git-tracked defaults (ai, command, security, system)
2. `.local/config.properties` — admin-tunable operational overrides (git-ignored)
3. `.local/secrets.properties` — secrets only: API keys, DB password (git-ignored)
4. Environment variables — highest precedence, override everything

See `.local/config.properties.example` (git-tracked template) for all available admin settings.

**application.properties:**
- Server port: `8008`
- AI API: Groq (OpenAI-compatible) at `https://api.groq.com/openai`
- AI models: `openai/gpt-oss-20b` (default), `openai/gpt-oss-120b` — both 8000 TPM; multi-key rotation via `GROQ_API_KEYS` (comma-separated)
- Database: PostgreSQL at `localhost:5432/serverassistant`; schema managed by Flyway, Hibernate validates
- Session timeout: 8 hours
- Admin: determined by Linux `sudo` group membership (no config property)
- CORS origins: `app.security.cors.allowed-origins` (defaults to `http://localhost:5173`)

**Command timeouts (command.properties):**
- Default: 10s; `du`: 30s; `find`: 60s; archives: 5min; package managers (`apt`): 10min; long-running: 30min
- Output limits: 2000 chars (AI tools), 8000 chars (deterministic routers)
- Thread pool: 8 parallel jobs / 80-item queue; output capture: 32 threads / 128-item queue

**vite.config.js:**
- Dev server proxies `/api` to `http://localhost:8008`
- Production build outputs to `../src/main/resources/static`
- Uses Rolldown for fast bundling; chunk size warning at 600 KB

### Database Schema

PostgreSQL database (`serverassistant`) — schema in `src/main/resources/db/migration/V1__init.sql`:
- `chat_messages`: Conversation history (conversation_id, username, message_type, content, created_at)
- `command_logs`: Command execution logs (username, command, output, success, exit_code, execution_time, command_type)
- `ai_model_configs`: Configurable AI models (id, name, tpm, label, category, is_enabled)

## Slash Commands Reference

| Command | Admin Only | Description |
|---------|-----------|-------------|
| `/status` | No | System status overview |
| `/gpu` | No | GPU status (nvidia-smi or OSHI) |
| `/docker` | No | Docker containers & resource usage |
| `/port` | No | Listening ports (TCP/UDP) |
| `/top cpu\|mem [limit]` | No | High CPU/memory processes (limit 1-30) |
| `/help` | No | List all commands |
| `/users` | Yes | List system users |
| `/addUser [username]` | Yes | Interactive user creation |
| `/addSSHKey [username]` | Yes | Add SSH public key |
| `/mount <device> <target> [fstype] [opts]` | Yes | Format and mount disk |
| `/offload <source> <target_disk>` | Yes | Copy directory + create symlink |

The `ExclamationCommandRouter` also handles raw shell commands prefixed with `!` (e.g., `!ls /tmp`), bypassing AI entirely.

## Common Development Tasks

### Adding a New AI Tool

1. Add function bean in `AiToolsConfig` with `@Description` (in Chinese)
2. Create request record class with `contextKey` parameter
3. Implement business logic in appropriate service (or delegate via `SystemService`)
4. Add tool name to `ChatService` tool names list

### Adding a New Slash Command

1. Add pattern matching in `SlashCommandRouter` (or create a new `DeterministicRouter` with `@Order`)
2. Implement handler method that calls the appropriate service
3. Return formatted markdown string

### Adding a New Router

1. Implement `DeterministicRouter` interface
2. Annotate with `@Component` and `@Order(n)` where n determines priority (lower = earlier)
3. `ChatService` auto-discovers all `DeterministicRouter` beans via Spring injection

### Modifying Frontend Components

Frontend uses Vue 3 Composition API (`<script setup>`). Build with `npm run build` to deploy to Spring Boot static resources.

### Changing AI Models

Edit `src/main/resources/config/ai.properties` or `.local/config.properties`:
```properties
app.ai.models.<key>.name=model-name
app.ai.models.<key>.tpm=token-limit
app.ai.models.<key>.label=Display Name
app.ai.models.<key>.category=Category
```

Or use the admin UI at `/api/admin/models`.

### Debugging

- Backend logs to console (SLF4J/Logback)
- Frontend console in browser DevTools
- Command logs stored in database: query `command_logs` table
- Chat history in `chat_messages` table

## Important Design Decisions

- **Identity resolution caveat**: Spring AI executes tool functions on Netty/Reactor thread pool threads. Do not rely on ThreadLocal user state; each tool call must carry a `contextKey` (server-generated UUID) and resolve identity via `UserContext.resolveFromActiveSessions()`.
- **JpaChatMemory stores text only**: Tool call metadata is NOT persisted. When AI calls a tool but produces empty final text, an empty `AssistantMessage("")` gets stored. Cancel flow uses `deleteLastMessages(conversationId, 2)` to clean up the entire failed exchange.
- **Confirmation bypasses AI**: Confirm/cancel buttons call REST endpoints directly (`ConfirmCommandHandler`), not through AI re-invocation. This avoids empty response issues with small models.
- **Trusted vs validated commands**: Backend-generated commands (containing `|`, `>`, `&&`) use `executeTrustedRootCommand`. User-originated commands go through `CommandValidator`.
- **Deterministic routing priority**: Routers run before AI, ordered by `@Order`. `SlashCommandRouter` (0) is first; AI is the fallback. Each router returns `null` to pass control to the next.
- **Chinese-language UI**: System prompts, error messages, UI labels, and `@Description` annotations are in Traditional Chinese.
- **Frontend markdown**: Uses `markdown-it` with `html: false` (security) + DOMPurify + highlight.js for code blocks.
- **Login protection**: `LoginAttemptService` tracks failed attempts per IP (max 10) and per username (max 8) with a 10-minute window and 15-minute lockout.
- **PostgreSQL + Flyway**: Schema versioning with Flyway; Hibernate only validates (never auto-creates/updates tables).
- **UserContextStore abstraction**: Switchable between in-memory (default) and Redis for distributed deployments.
