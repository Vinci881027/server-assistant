package com.linux.ai.serverassistant.exception;

import com.linux.ai.serverassistant.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global Exception Handler
 *
 * Provides centralized exception handling for all REST controllers.
 * Converts exceptions to unified ApiResponse format with appropriate HTTP status codes.
 *
 * Exception Handling Strategy:
 * - Validation errors (400): MethodArgumentNotValidException, MissingServletRequestParameterException
 * - Authentication errors (401): AuthenticationException
 * - Authorization errors (403): AccessDeniedException
 * - Not found errors (404): ResourceNotFoundException (custom)
 * - Server errors (500): All other exceptions
 *
 * @author Claude Code - Phase 2 Refactoring
 * @version 1.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ========== Validation Errors (400) ==========

    /**
     * Handles validation errors from @Valid annotations.
     *
     * @param ex the validation exception
     * @return ResponseEntity with 400 status and error details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        logger.warn("Validation error: {}", ex.getMessage());

        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        ApiResponse<Void> response = ApiResponse.error("驗證錯誤: " + errors, "VALIDATION_ERROR");
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handles missing required request parameters.
     *
     * @param ex the missing parameter exception
     * @return ResponseEntity with 400 status and error details
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameterException(MissingServletRequestParameterException ex) {
        logger.warn("Missing parameter: {}", ex.getParameterName());

        String message = String.format("缺少必要參數: %s (類型: %s)", ex.getParameterName(), ex.getParameterType());
        ApiResponse<Void> response = ApiResponse.error(message, "MISSING_PARAMETER");
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handles type mismatch errors (e.g., passing string for integer parameter).
     *
     * @param ex the type mismatch exception
     * @return ResponseEntity with 400 status and error details
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        logger.warn("Type mismatch for parameter: {}", ex.getName());

        String message = String.format("參數類型錯誤: %s (期望類型: %s)",
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        ApiResponse<Void> response = ApiResponse.error(message, "TYPE_MISMATCH");
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handles malformed or unreadable JSON request bodies.
     *
     * @param ex the JSON parse/read exception
     * @return ResponseEntity with 400 status and error details
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        logger.warn("Malformed request body: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("請求內容格式錯誤或缺少必要欄位", "MALFORMED_REQUEST_BODY");
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handles illegal argument exceptions.
     *
     * @param ex the illegal argument exception
     * @return ResponseEntity with 400 status and error details
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn("Illegal argument: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.error("無效的參數: " + ex.getMessage(), "ILLEGAL_ARGUMENT");
        return ResponseEntity.badRequest().body(response);
    }

    // ========== Authentication & Authorization Errors (401/403) ==========

    /**
     * Handles authentication failures.
     *
     * @param ex the authentication exception
     * @return ResponseEntity with 401 status and error details
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException ex) {
        logger.warn("Authentication error: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.error("身份驗證失敗: " + ex.getMessage(), "AUTHENTICATION_ERROR");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * Handles access denied exceptions (authorization failures).
     *
     * @param ex the access denied exception
     * @return ResponseEntity with 403 status and error details
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        logger.warn("Access denied: {}", ex.getMessage());

        ApiResponse<Void> response = ApiResponse.error("權限不足: " + ex.getMessage(), "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    // ========== Rate Limit Errors (429) ==========

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleRateLimitExceededException(RateLimitExceededException ex) {
        long retryAfterSeconds = Math.max(1L, ex.getRetryAfterSeconds());
        logger.warn("Rate limit exceeded: reason={}, retryAfter={}s", ex.getReason(), retryAfterSeconds);
        ApiResponse<Map<String, Object>> response = ApiResponse.error(
                ex.getMessage(),
                "RATE_LIMIT_EXCEEDED",
                Map.of(
                        "reason", ex.getReason(),
                        "retryAfterSeconds", retryAfterSeconds,
                        "details", Map.of("retryAfterSeconds", retryAfterSeconds)));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .body(response);
    }

    @ExceptionHandler(ConcurrentStreamLimitExceededException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleConcurrentStreamLimitExceededException(
            ConcurrentStreamLimitExceededException ex) {
        long retryAfterSeconds = Math.max(1L, ex.getRetryAfterSeconds());
        logger.warn(
                "Concurrent stream limit exceeded: maxConcurrentStreams={}, retryAfter={}s",
                ex.getMaxConcurrentStreams(),
                retryAfterSeconds);
        ApiResponse<Map<String, Object>> response = ApiResponse.error(
                ex.getMessage(),
                "CONCURRENT_STREAM_LIMIT_EXCEEDED",
                Map.of(
                        "maxConcurrentStreams", ex.getMaxConcurrentStreams(),
                        "retryAfterSeconds", retryAfterSeconds,
                        "details", Map.of("retryAfterSeconds", retryAfterSeconds)));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .body(response);
    }

    // ========== Server Errors (500) ==========

    /**
     * Handles all other uncaught exceptions.
     *
     * @param ex the exception
     * @return ResponseEntity with 500 status and error details
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        logger.error("Unexpected error", ex);

        ApiResponse<Void> response = ApiResponse.error("伺服器內部錯誤，請聯絡管理員。", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
