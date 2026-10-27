package com.lb.financefresh.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchLabelsTest {

    @Test
    void commodityAndFearIndexKeepChineseNames() {
        assertEquals("原油 USO", WatchLabels.display("USO"));
        assertEquals("黄金 GLD", WatchLabels.display("GLD"));
        assertEquals("恐慌指数 VIX", WatchLabels.display("vix"));
        assertEquals("NVDA", WatchLabels.display("NVDA"));
        assertTrue(WatchLabels.indexOnly("VIX"));
        assertFalse(WatchLabels.indexOnly("USO"));
    }
}
