package com.linux.ai.serverassistant.controller;

import com.linux.ai.serverassistant.dto.ApiResponse;
import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.repository.ChatMessageRepository;
import com.linux.ai.serverassistant.service.AiModelService;
import com.linux.ai.serverassistant.service.user.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 100;
    private static final int MAX_SIZE = 500;
    private static final int MAX_USERNAME_LENGTH = 64;

    /** Minimum interval (ms) between destructive operations per admin actor. */
    private static final long DESTRUCTIVE_OP_MIN_INTERVAL_MS = 5_000L;
    private final ConcurrentHashMap<String, AtomicLong> lastDestructiveOp = new ConcurrentHashMap<>();

    private final ChatMessageRepository chatMessageRepository;
    private final AiModelService aiModelService;
    private final CommandLogRepository commandLogRepository;

    public AdminController(ChatMessageRepository chatMessageRepository, AiModelService aiModelService, CommandLogRepository commandLogRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.aiModelService = aiModelService;
        this.commandLogRepository = commandLogRepository;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    public ApiResponse<List<String>> getAllUsers() {
        return ApiResponse.success(chatMessageRepository.findDistinctUsernames());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/history/{username}")
    public ApiResponse<Map<String, Object>> getUserHistory(
            @PathVariable String username,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        ApiResponse<Map<String, Object>> validationError = validateUsername(username);
        if (validationError != null) return validationError;
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        Page<Map<String, String>> resultPage = chatMessageRepository.findByUsernameOrderByCreatedAtDesc(username, pageable)
                .map(msg -> Map.of(
                        "role", (msg.getMessageType() != null && "USER".equalsIgnoreCase(msg.getMessageType())) ? "user" : "ai",
                        "content", msg.getContent(),
                        "timestamp", msg.getCreatedAt().toString()
                ));

        Map<String, Object> data = Map.of(
                "items", resultPage.getContent(),
                "page", resultPage.getNumber(),
                "size", resultPage.getSize(),
                "totalElements", resultPage.getTotalElements(),
                "totalPages", resultPage.getTotalPages(),
                "hasNext", resultPage.hasNext()
        );
        return ApiResponse.success(data);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/audit/{username}")
    public ApiResponse<Map<String, Object>> getUserAuditLogs(
            @PathVariable String username,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        ApiResponse<Map<String, Object>> validationError = validateUsername(username);
        if (validationError != null) return validationError;
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        Page<CommandLog> resultPage = commandLogRepository.findByUsernameOrderByExecutionTimeDesc(username, pageable);
        Map<String, Object> data = Map.of(
                "items", resultPage.getContent(),
                "page", resultPage.getNumber(),
                "size", resultPage.getSize(),
                "totalElements", resultPage.getTotalElements(),
                "totalPages", resultPage.getTotalPages(),
                "hasNext", resultPage.hasNext()
        );
        return ApiResponse.success(data);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(timeout = 30)
    @DeleteMapping("/reset")
    public ApiResponse<Void> resetDatabase() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = (auth != null) ? auth.getName() : "unknown";
        if (isDestructiveOpThrottled(actor)) {
            return ApiResponse.error("操作過於頻繁，請稍後再試");
        }
        long chatCount = chatMessageRepository.count();
        long commandCount = commandLogRepository.count();
        chatMessageRepository.deleteAllInBatch();
        commandLogRepository.deleteAllInBatch();
        aiModelService.deleteAll();
        aiModelService.init();
        log.warn("Database reset by admin '{}': deleted {} chat messages, {} command logs", actor, chatCount, commandCount);
        return ApiResponse.success("Database reset successfully");
    }

    /**
     * One-click purge for operational data to keep the DB from growing too large.
     * Deletes:
     * - all chat messages (conversation memory)
     * - all command execution audit logs
     *
     * Does NOT delete model configs.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(timeout = 30)
    @DeleteMapping("/purge/activity")
    public ApiResponse<Map<String, Long>> purgeActivity() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = (auth != null) ? auth.getName() : "unknown";
        if (isDestructiveOpThrottled(actor)) {
            return ApiResponse.error("操作過於頻繁，請稍後再試");
        }
        long chatCount = chatMessageRepository.count();
        long commandCount = commandLogRepository.count();

        // Use batch deletes for better performance on large tables.
        commandLogRepository.deleteAllInBatch();
        chatMessageRepository.deleteAllInBatch();

        return ApiResponse.success(
                "已清除所有對話與指令紀錄",
                Map.of(
                        "deletedChatMessages", chatCount,
                        "deletedCommandLogs", commandCount
                )
        );
    }

    /**
     * Purges only chat messages (conversation memory).
     *
     * Does NOT delete command logs or model configs.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(timeout = 30)
    @DeleteMapping("/purge/chats")
    public ApiResponse<Map<String, Long>> purgeChats() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = (auth != null) ? auth.getName() : "unknown";
        if (isDestructiveOpThrottled(actor)) {
            return ApiResponse.error("操作過於頻繁，請稍後再試");
        }
        long chatCount = chatMessageRepository.count();
        chatMessageRepository.deleteAllInBatch();
        return ApiResponse.success(
                "已清除所有對話紀錄",
                Map.of("deletedChatMessages", chatCount)
        );
    }

    /**
     * Purges only command execution audit logs.
     *
     * Does NOT delete chat messages or model configs.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(timeout = 30)
    @DeleteMapping("/purge/commands")
    public ApiResponse<Map<String, Long>> purgeCommands() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = (auth != null) ? auth.getName() : "unknown";
        if (isDestructiveOpThrottled(actor)) {
            return ApiResponse.error("操作過於頻繁，請稍後再試");
        }
        long commandCount = commandLogRepository.count();
        commandLogRepository.deleteAllInBatch();
        return ApiResponse.success(
                "已清除所有指令紀錄",
                Map.of("deletedCommandLogs", commandCount)
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(timeout = 30)
    @DeleteMapping("/purge/users/{username}/chats")
    public ApiResponse<Map<String, Long>> purgeUserChats(@PathVariable String username) {
        ApiResponse<Map<String, Long>> validationError = validateUsername(username);
        if (validationError != null) return validationError;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (isDestructiveOpThrottled((auth != null) ? auth.getName() : "unknown")) {
            return ApiResponse.error("操作過於頻繁，請稍後再試");
        }
        long chatCount = chatMessageRepository.countByUsername(username);
        if (chatCount == 0) {
            return ApiResponse.error("找不到使用者 '" + username + "' 的對話紀錄");
        }
        chatMessageRepository.deleteAllByUsername(username);
        return ApiResponse.success(
                "已清除該使用者的對話紀錄",
                Map.of("deletedChatMessages", chatCount)
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(timeout = 30)
    @DeleteMapping("/purge/users/{username}/commands")
    public ApiResponse<Map<String, Long>> purgeUserCommands(@PathVariable String username) {
        ApiResponse<Map<String, Long>> validationError = validateUsername(username);
        if (validationError != null) return validationError;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (isDestructiveOpThrottled((auth != null) ? auth.getName() : "unknown")) {
            return ApiResponse.error("操作過於頻繁，請稍後再試");
        }
        long commandCount = commandLogRepository.countByUsername(username);
        if (commandCount == 0) {
            return ApiResponse.error("找不到使用者 '" + username + "' 的指令紀錄");
        }
        commandLogRepository.deleteAllByUsername(username);
        return ApiResponse.success(
                "已清除該使用者的指令紀錄",
                Map.of("deletedCommandLogs", commandCount)
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(timeout = 30)
    @DeleteMapping("/purge/users/{username}/activity")
    public ApiResponse<Map<String, Long>> purgeUserActivity(@PathVariable String username) {
        ApiResponse<Map<String, Long>> validationError = validateUsername(username);
        if (validationError != null) return validationError;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (isDestructiveOpThrottled((auth != null) ? auth.getName() : "unknown")) {
            return ApiResponse.error("操作過於頻繁，請稍後再試");
        }

        long chatCount = chatMessageRepository.countByUsername(username);
        long commandCount = commandLogRepository.countByUsername(username);

        if (chatCount == 0 && commandCount == 0) {
            return ApiResponse.error("找不到使用者 '" + username + "' 的任何紀錄");
        }

        commandLogRepository.deleteAllByUsername(username);
        chatMessageRepository.deleteAllByUsername(username);

        return ApiResponse.success(
                "已清除該使用者的對話與指令紀錄",
                Map.of(
                        "deletedChatMessages", chatCount,
                        "deletedCommandLogs", commandCount
                )
        );
    }

    /**
     * Returns true (and blocks) if the actor has performed a destructive operation
     * within the last DESTRUCTIVE_OP_MIN_INTERVAL_MS milliseconds.
     * Otherwise records the current timestamp and returns false.
     */
    private boolean isDestructiveOpThrottled(String actor) {
        long now = System.currentTimeMillis();
        AtomicLong last = lastDestructiveOp.computeIfAbsent(actor, k -> new AtomicLong(0));
        long prev = last.get();
        if (now - prev < DESTRUCTIVE_OP_MIN_INTERVAL_MS) {
            return true;
        }
        return !last.compareAndSet(prev, now);
    }

    private <T> ApiResponse<T> validateUsername(String username) {
        if (username == null || username.isBlank()) {
            return ApiResponse.error("username 不可為空");
        }
        if (username.length() > MAX_USERNAME_LENGTH) {
            return ApiResponse.error("username 長度不可超過 " + MAX_USERNAME_LENGTH + " 個字元");
        }
        if (!UserManagementService.isValidLinuxUsername(username)) {
            return ApiResponse.error("username 包含不允許的字元");
        }
        return null;
    }
}
