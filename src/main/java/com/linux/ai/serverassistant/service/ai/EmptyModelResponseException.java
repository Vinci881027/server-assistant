package com.linux.ai.serverassistant.service.ai;

/**
 * Thrown when the AI model returns an empty (zero-length) response.
 * Used as a signal for the outer retry loop in {@link AiStreamRetryCoordinator}.
 */
class EmptyModelResponseException extends RuntimeException {
}
