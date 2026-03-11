package com.linux.ai.serverassistant.service.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class ProcessOutputCapture implements Runnable {
    private final Process process;
    private final int maxChars;
    private final StringBuilder sb = new StringBuilder();
    private volatile boolean truncated;

    ProcessOutputCapture(Process process, int maxChars) {
        this.process = process;
        this.maxChars = maxChars;
    }

    @Override
    public void run() {
        if (process == null) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1) {
                synchronized (sb) {
                    int remaining = maxChars - sb.length();
                    if (remaining <= 0) {
                        truncated = true;
                        break;
                    }
                    int toAppend = Math.min(remaining, n);
                    sb.append(buf, 0, toAppend);
                    if (toAppend < n) {
                        truncated = true;
                        break;
                    }
                }
                if (truncated) break;
            }
        } catch (IOException ignored) {
            // If the process is destroyed/forcibly terminated, stream reads can throw.
        } finally {
            if (truncated && process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                appendNoticeEnsuringVisible("\n\n[系統提示] 輸出已截斷，僅顯示前段內容。請縮小查詢範圍以取得完整結果。");
            }
        }
    }

    String getOutput() {
        synchronized (sb) {
            return sb.toString();
        }
    }

    boolean wasTruncated() {
        return truncated;
    }

    void appendNotice(String notice) {
        if (notice == null || notice.isEmpty()) return;
        synchronized (sb) {
            if (sb.length() >= maxChars) {
                truncated = true;
                return;
            }
            int remaining = maxChars - sb.length();
            sb.append(notice, 0, Math.min(remaining, notice.length()));
        }
    }

    void appendNoticeEnsuringVisible(String notice) {
        if (notice == null || notice.isEmpty()) return;
        synchronized (sb) {
            if (notice.length() >= maxChars) {
                sb.setLength(0);
                sb.append(notice, 0, maxChars);
                return;
            }
            int keepChars = Math.max(0, maxChars - notice.length());
            if (sb.length() > keepChars) {
                sb.setLength(keepChars);
            }
            sb.append(notice);
        }
    }

    void markTimedOut(long timeoutSeconds) {
        appendNotice("\n[系統提示] 命令執行逾時（超過 " + timeoutSeconds + " 秒），已強制終止。");
    }
}
