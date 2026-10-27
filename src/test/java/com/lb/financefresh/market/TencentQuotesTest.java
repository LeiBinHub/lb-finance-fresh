package com.lb.financefresh.market;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TencentQuotesTest {

    @Test
    void parseKeepsIndexAndHongKongLastAndPercent() {
        String body = row("sh000001", "上证指数", "3685.65", "3690.48", "20261026135345", "-4.83", "-0.13")
                + row("r_hkHSI", "恒生指数", "23208.360", "23093.370", "2026/10/26 13:54:24", "114.990", "0.50")
                + row("r_hk00700", "腾讯控股", "387.600", "386.000", "2026/10/26 13:54:11", "1.600", "0.41");
        List<ChinaQuote> quotes = TencentQuotes.parse(body);
        assertEquals(3, quotes.size());
        ChinaQuote shanghai = quotes.getFirst();
        assertEquals("sh000001", shanghai.code());
        assertEquals("上证指数", shanghai.name());
        assertEquals(3685.65, shanghai.last(), 0.001);
        assertEquals(3690.48, shanghai.prevClose(), 0.001);
        assertEquals(-4.83, shanghai.change(), 0.001);
        assertEquals(-0.13, shanghai.changePercent(), 0.001);
        assertEquals("2026-10-26 13:53:45", shanghai.at());
        assertEquals("恒生指数", quotes.get(1).name());
        assertEquals("2026-10-26 13:54:24", quotes.get(1).at());
        assertEquals("腾讯控股", quotes.get(2).name());
        assertEquals(0.41, quotes.get(2).changePercent(), 0.001);
        assertEquals("hk00700", TencentQuotes.displayCode("r_hk00700"));
    }

    @Test
    void parseSkipsBlankAndZeroLast() {
        assertTrue(TencentQuotes.parse("").isEmpty());
        assertTrue(TencentQuotes.parse(row("sh000001", "上证指数", "0.00", "3690.48", "20261026135345", "0", "0")).isEmpty());
    }

    private static String row(String code, String name, String last, String prev, String at, String change, String percent) {
        String[] parts = new String[33];
        Arrays.fill(parts, "0");
        parts[1] = name;
        parts[2] = code;
        parts[3] = last;
        parts[4] = prev;
        parts[30] = at;
        parts[31] = change;
        parts[32] = percent;
        return "v_" + code + "=\"" + String.join("~", parts) + "\";\n";
    }
}
