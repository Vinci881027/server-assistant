package com.linux.ai.serverassistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Unified API Response Wrapper
 *
 * Provides a consistent response format for all REST API endpoints.
 * Includes support for success/error states, data payload, and error messages.
 *
 * Generic type T allows flexible data payload types.
 *
 * @param <T> the type of data payload
 * @author Claude Code - Phase 2 Refactoring
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    // ========== Fields ==========

    /**
     * Indicates whether the request was successful.
     */
    private boolean success;

    /**
     * Human-readable message (optional).
     */
    private String message;

    /**
     * Data payload (null if error or no data).
     */
    private T data;

    /**
     * Error details (null if success).
     */
    private ErrorDetails error;

    /**
     * Timestamp of the response.
     */
    private long timestamp;

    // ========== Constructors ==========

    /**
     * Default constructor (required for JSON serialization).
     */
    public ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Private constructor for builder pattern.
     *
     * @param success whether the request was successful
     * @param message human-readable message
     * @param data data payload
     * @param error error details
     */
    private ApiResponse(boolean success, String message, T data, ErrorDetails error) {
        this.success = success;
        this.message = message;
        this.data = data;
        this.error = error;
        this.timestamp = System.currentTimeMillis();
    }

    // ========== Static Factory Methods ==========

    /**
     * Creates a successful response with data.
     *
     * @param data the data payload
     * @param <T> the type of data
     * @return ApiResponse with success=true and data
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, null, data, null);
    }

    /**
     * Creates a successful response with data and message.
     *
     * @param message success message
     * @param data the data payload
     * @param <T> the type of data
     * @return ApiResponse with success=true, message, and data
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, null);
    }

    /**
     * Creates a successful response with only a message (no data).
     *
     * @param message success message
     * @return ApiResponse with success=true and message
     */
    public static ApiResponse<Void> success(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    /**
     * Creates an error response with message.
     *
     * @param message error message
     * @param <T> the type of data (null in error case)
     * @return ApiResponse with success=false and error message
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, new ErrorDetails(message, null));
    }

    /**
     * Creates an error response with message and error code.
     *
     * @param message error message
     * @param code error code
     * @param <T> the type of data (null in error case)
     * @return ApiResponse with success=false, error message, and code
     */
    public static <T> ApiResponse<T> error(String message, String code) {
        return new ApiResponse<>(false, message, null, new ErrorDetails(message, code));
    }

    /**
     * Creates an error response with message, error code, and data payload.
     *
     * @param message error message
     * @param code error code
     * @param data additional payload for clients
     * @param <T> the type of data payload
     * @return ApiResponse with success=false, message, code, and data
     */
    public static <T> ApiResponse<T> error(String message, String code, T data) {
        return new ApiResponse<>(false, message, data, new ErrorDetails(message, code));
    }

    /**
     * Creates an error response from an exception.
     *
     * @param ex the exception
     * @param <T> the type of data (null in error case)
     * @return ApiResponse with success=false and exception details
     */
    public static <T> ApiResponse<T> error(Exception ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Internal Server Error";
        return new ApiResponse<>(false, message, null, new ErrorDetails(message, ex.getClass().getSimpleName()));
    }

    // ========== Getters and Setters ==========

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public ErrorDetails getError() {
        return error;
    }

    public void setError(ErrorDetails error) {
        this.error = error;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    // ========== Inner Class: ErrorDetails ==========

    /**
     * Error Details for API responses.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetails {
        private String message;
        private String code;

        public ErrorDetails() {}

        public ErrorDetails(String message, String code) {
            this.message = message;
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }
}
