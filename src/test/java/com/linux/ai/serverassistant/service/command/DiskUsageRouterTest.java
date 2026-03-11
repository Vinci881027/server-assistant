package com.linux.ai.serverassistant.service.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiskUsageRouterTest {

    @Test
    void directDu_isIntercepted() {
        DiskUsageRouter router = new DiskUsageRouter();
        DiskUsageRouter.RouteResult rr = router.tryRoute("du -sh /var/log");
        assertTrue(rr.matched());
        assertNull(rr.response());
        assertEquals("du -sh /var/log", rr.command());
    }

    @Test
    void folderSizeWithPath_generatesDuMaxDepthSorted() {
        DiskUsageRouter router = new DiskUsageRouter();
        DiskUsageRouter.RouteResult rr = router.tryRoute("資料夾大小 /var/log");
        assertTrue(rr.matched());
        assertEquals("du -ch --max-depth=1 /var/log | sort -h", rr.command());
    }

    @Test
    void pathSpaceUsage_withoutFolderKeyword_isIntercepted() {
        DiskUsageRouter router = new DiskUsageRouter();
        DiskUsageRouter.RouteResult rr = router.tryRoute("/var/log 佔用空間");
        assertTrue(rr.matched());
        assertEquals("du -ch --max-depth=1 /var/log | sort -h", rr.command());
    }

    @Test
    void breakdownKeyword_generatesMaxDepth() {
        DiskUsageRouter router = new DiskUsageRouter();
        DiskUsageRouter.RouteResult rr = router.tryRoute("列出 /var/log 子資料夾大小");
        assertTrue(rr.matched());
        assertEquals("du -ch --max-depth=1 /var/log | sort -h", rr.command());
    }

    @Test
    void deleteIntent_isNotIntercepted() {
        DiskUsageRouter router = new DiskUsageRouter();
        DiskUsageRouter.RouteResult rr = router.tryRoute("刪除 /var/log");
        assertFalse(rr.matched());
    }

    @Test
    void missingPath_promptsForAbsolutePath() {
        DiskUsageRouter router = new DiskUsageRouter();
        DiskUsageRouter.RouteResult rr = router.tryRoute("資料夾大小是多少");
        assertTrue(rr.matched());
        assertNotNull(rr.response());
        assertNull(rr.command());
        assertTrue(rr.response().contains("絕對路徑"));
    }

    @Test
    void trailingPunctuation_isStrippedFromPath() {
        DiskUsageRouter router = new DiskUsageRouter();
        DiskUsageRouter.RouteResult rr = router.tryRoute("資料夾大小 /var/log。");
        assertTrue(rr.matched());
        assertEquals("du -ch --max-depth=1 /var/log | sort -h", rr.command());
    }
}
