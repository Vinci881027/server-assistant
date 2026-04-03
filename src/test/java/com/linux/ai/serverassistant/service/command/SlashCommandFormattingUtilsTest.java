package com.linux.ai.serverassistant.service.command;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandFormattingUtilsTest {

    @Test
    void formatSystemOverviewAsMarkdown_shouldRenderTimestampInLocalTimeWithoutFractionalSeconds() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));

        try {
            String markdown = SlashCommandFormattingUtils.formatSystemOverviewAsMarkdown(
                    Map.of("timestamp", "2026-03-27T14:01:57.667257760Z"),
                    NaturalLanguageQueryMatcher.OVERVIEW_WARNING_KEY,
                    NaturalLanguageQueryMatcher.DISK_LINE
            );

            assertTrue(markdown.contains("| 掃描時間 | 2026-03-27 22:01:57 |"));
            assertFalse(markdown.contains("667257760"));
            assertFalse(markdown.contains("14:01:57.667257760Z"));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
