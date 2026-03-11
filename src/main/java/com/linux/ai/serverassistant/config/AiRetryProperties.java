package com.linux.ai.serverassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for AI stream retry behaviour.
 *
 * <pre>
 * app.ai.retry.max-empty-retries=1
 * app.ai.retry.max-http-retries=3
 * app.ai.retry.http-initial-backoff-seconds=2
 * app.ai.retry.temperature-base=0.2
 * app.ai.retry.temperature-on-empty-retry=0.3
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app.ai.retry")
public class AiRetryProperties {

    /** Maximum times to retry after the model returns an empty response. */
    private int maxEmptyRetries = 1;

    /** Maximum times to retry after a retryable HTTP error (429/500/503). */
    private int maxHttpRetries = 3;

    /** Initial backoff (seconds) for HTTP retries; doubles on each subsequent attempt. */
    private int httpInitialBackoffSeconds = 2;

    /** Temperature used on the first attempt. */
    private double temperatureBase = 0.2;

    /** Temperature used on empty-response retries to encourage model variation. */
    private double temperatureOnEmptyRetry = 0.3;

    public int getMaxEmptyRetries() { return maxEmptyRetries; }
    public void setMaxEmptyRetries(int maxEmptyRetries) { this.maxEmptyRetries = maxEmptyRetries; }

    public int getMaxHttpRetries() { return maxHttpRetries; }
    public void setMaxHttpRetries(int maxHttpRetries) { this.maxHttpRetries = maxHttpRetries; }

    public int getHttpInitialBackoffSeconds() { return httpInitialBackoffSeconds; }
    public void setHttpInitialBackoffSeconds(int httpInitialBackoffSeconds) {
        this.httpInitialBackoffSeconds = httpInitialBackoffSeconds;
    }

    public double getTemperatureBase() { return temperatureBase; }
    public void setTemperatureBase(double temperatureBase) { this.temperatureBase = temperatureBase; }

    public double getTemperatureOnEmptyRetry() { return temperatureOnEmptyRetry; }
    public void setTemperatureOnEmptyRetry(double temperatureOnEmptyRetry) {
        this.temperatureOnEmptyRetry = temperatureOnEmptyRetry;
    }
}
