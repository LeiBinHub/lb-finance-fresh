package com.lb.financefresh.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComPusherTest {

    @Test
    void longBriefStaysWithinTheChannelLimit() {
        String text = "数值：利差 3.40%。".repeat(400);
        String clipped = WeComPusher.clip(text);
        assertTrue(clipped.getBytes(StandardCharsets.UTF_8).length <= 4000);
        assertTrue(clipped.endsWith("…后面在页面上。"));
        assertTrue(clipped.startsWith("数值："));
    }
}
