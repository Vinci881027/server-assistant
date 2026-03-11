package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.util.CommandMarkers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseFormattingUtilsTest {

    @Test
    void maybeFormatDiskUsageAsMarkdownTable_shouldFormatRowsAndPreserveMiscOutput() {
        String command = "du -h --max-depth=1 /var | sort -h";
        String toolResult = """
                12K /tmp/cache
                bad-line
                1.0G /var/lib/docker|overlay2
                """;

        String formatted = ResponseFormattingUtils.maybeFormatDiskUsageAsMarkdownTable(command, toolResult);

        assertTrue(formatted.contains("| 大小 | 路徑 |"));
        assertTrue(formatted.contains("| 12K | /tmp/cache |"));
        assertTrue(formatted.contains("/var/lib/docker\\|overlay2"));
        assertTrue(formatted.contains("其他輸出："));
        assertTrue(formatted.contains("bad-line"));
    }

    @Test
    void maybeFormatDiskUsageAsMarkdownTable_withoutParsableRows_shouldReturnRawOutput() {
        String command = "du -h --max-depth=1 /var | sort -h";
        String raw = "unparseable";

        String formatted = ResponseFormattingUtils.maybeFormatDiskUsageAsMarkdownTable(command, raw);

        assertEquals(raw, formatted);
    }

    @Test
    void maybeFormatDiskUsageAsMarkdownTable_withNonDuCommand_shouldReturnRawOutput() {
        String raw = "1.0G /var";

        String formatted = ResponseFormattingUtils.maybeFormatDiskUsageAsMarkdownTable("ls -la", raw);

        assertEquals(raw, formatted);
    }

    @Test
    void maybeFormatTerminalOutputAsCodeBlock_shouldWrapAlignedMultiLineOutput() {
        String raw = """
                Filesystem      Size   Used   Avail
                /dev/sda1       100G    60G    40G
                /dev/sdb1       200G    20G   180G
                /dev/sdc1       300G   120G   180G
                """;

        String formatted = ResponseFormattingUtils.maybeFormatTerminalOutputAsCodeBlock(raw);

        assertTrue(formatted.startsWith("```text\n"));
        assertTrue(formatted.endsWith("\n```"));
        assertTrue(formatted.contains("/dev/sdb1"));
    }

    @Test
    void maybeFormatTerminalOutputAsCodeBlock_withBackticks_shouldUseFourTickFence() {
        String raw = """
                colA  colB
                v1    v2
                v3    v4
                note ``` inline marker
                """;

        String formatted = ResponseFormattingUtils.maybeFormatTerminalOutputAsCodeBlock(raw);

        assertTrue(formatted.startsWith("````text\n"));
        assertTrue(formatted.endsWith("\n````"));
    }

    @Test
    void maybeFormatTerminalOutputAsCodeBlock_shouldKeepMarkdownAndMarkersUntouched() {
        String marker = CommandMarkers.cmdMarker("rm -rf /tmp/test");
        assertEquals(marker, ResponseFormattingUtils.maybeFormatTerminalOutputAsCodeBlock(marker));

        String markdown = "| A | B |\n| --- | --- |\n| 1 | 2 |";
        assertEquals(markdown, ResponseFormattingUtils.maybeFormatTerminalOutputAsCodeBlock(markdown));
    }

    @Test
    void maybeRewriteExclamationCommandNotFound_shouldRewriteFriendlyMessage() {
        String rewritten = ResponseFormattingUtils.maybeRewriteExclamationCommandNotFound(
                "!foobar --version",
                "foobar --version",
                "[System Notice] this line is ignored\n/bin/sh: foobar: command not found"
        );

        assertEquals("找不到指令：`foobar`", rewritten);
    }

    @Test
    void maybeRewriteExclamationCommandNotFound_whenCommandIsBlank_shouldUseEmptyPlaceholder() {
        String rewritten = ResponseFormattingUtils.maybeRewriteExclamationCommandNotFound(
                "!    ",
                "   ",
                "bash: : command not found"
        );

        assertEquals("找不到指令：`(empty)`", rewritten);
    }

    @Test
    void maybeRewriteExclamationCommandNotFound_whenOutputNotStandalone_shouldKeepOriginal() {
        String original = "bash: foobar: command not found\nhint: install foobar first";
        String rewritten = ResponseFormattingUtils.maybeRewriteExclamationCommandNotFound(
                "!foobar",
                "foobar",
                original
        );

        assertEquals(original, rewritten);
    }
}
