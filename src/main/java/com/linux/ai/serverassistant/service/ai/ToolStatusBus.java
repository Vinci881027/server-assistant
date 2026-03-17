package com.linux.ai.serverassistant.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges synchronous AI tool execution events into reactive SSE streams.
 *
 * <p>Each AI request registers a sink keyed by toolContextKey. Tool functions
 * emit {@code [STATUS:...]} events to the sink before/after execution.
 * ChatService merges the sink's Flux into the main AI response stream so that
 * the frontend receives real-time phase indicators (thinking → executing → done).
 */
@Component
public class ToolStatusBus {

    private static final Logger log = LoggerFactory.getLogger(ToolStatusBus.class);

    public static final String PREFIX_TOOL_CALL = "[STATUS:TOOL_CALL:";
    public static final String PREFIX_TOOL_DONE = "[STATUS:TOOL_DONE]";

    private final ConcurrentHashMap<String, Sinks.Many<String>> activeSinks = new ConcurrentHashMap<>();

    /**
     * Registers a new multicast sink for the given contextKey and returns it.
     * Must be called once per request before the AI stream starts.
     */
    public Sinks.Many<String> createSink(String contextKey) {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(32, false);
        activeSinks.put(contextKey, sink);
        return sink;
    }

    /**
     * Emits a raw status event string to the sink registered for contextKey.
     * No-op if no sink is registered (e.g. deterministic routes that skip AI).
     */
    public void emit(String contextKey, String statusEvent) {
        if (contextKey == null) return;
        Sinks.Many<String> sink = activeSinks.get(contextKey);
        if (sink == null) return;
        Sinks.EmitResult result = sink.tryEmitNext(statusEvent);
        if (result.isFailure()) {
            log.debug("Tool status emit dropped ({}): contextKey={}", result, contextKey);
        }
    }

    /** Emits a TOOL_CALL status event: {@code [STATUS:TOOL_CALL:type:detail]}. */
    public void emitToolCall(String contextKey, String type, String detail) {
        emit(contextKey, PREFIX_TOOL_CALL + type + ":" + truncate(detail, 60) + "]");
    }

    /** Emits the TOOL_DONE event signalling the tool has returned. */
    public void emitToolDone(String contextKey) {
        emit(contextKey, PREFIX_TOOL_DONE);
    }

    /**
     * Completes and removes the sink for the given contextKey.
     * Called in the model stream's {@code doFinally} so the merged Flux can terminate.
     */
    public void complete(String contextKey) {
        if (contextKey == null) return;
        Sinks.Many<String> sink = activeSinks.remove(contextKey);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
