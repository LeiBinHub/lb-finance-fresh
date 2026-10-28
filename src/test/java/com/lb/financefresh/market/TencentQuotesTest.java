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
        assertEquals(0, shanghai.amount(), 0.001);
    }

    @Test
    void parseKeepsAmountTurnoverAndRangeWhenPresent() {
        String[] parts = new String[46];
        Arrays.fill(parts, "0");
        parts[1] = "上证指数";
        parts[2] = "sh000001";
        parts[3] = "3674.44";
        parts[4] = "3648.15";
        parts[30] = "20261028114600";
        parts[31] = "26.29";
        parts[32] = "0.72";
        parts[33] = "3680.11";
        parts[34] = "3640.02";
        parts[36] = "123456";
        parts[37] = "46311053";
        parts[38] = "1.25";
        parts[39] = "14.8";
        parts[43] = "1.10";
        parts[44] = "500000";
        parts[45] = "800000";
        List<ChinaQuote> quotes = TencentQuotes.parse("v_sh000001=\"" + String.join("~", parts) + "\";");
        ChinaQuote shanghai = quotes.getFirst();
        assertEquals(3680.11, shanghai.high(), 0.001);
        assertEquals(3640.02, shanghai.low(), 0.001);
        assertEquals(123456, shanghai.volume(), 0.001);
        assertEquals(46311053, shanghai.amount(), 0.001);
        assertEquals(1.25, shanghai.turnover(), 0.001);
        assertEquals(14.8, shanghai.pe(), 0.001);
        assertEquals(1.10, shanghai.amplitude(), 0.001);
    }

    @Test
    void parseRanksKeepsNamePercentAndLeader() {
        String body = """
                {"data":[{"bd_name":"自动化设备","bd_zxj":"1234.56","bd_zdf":"5.86","nzg_name":"某股","nzg_zdf":"10.02"}]}
                """;
        List<ChinaSector> rows = TencentQuotes.parseRanks("industry", body);
        assertEquals(1, rows.size());
        assertEquals("industry", rows.getFirst().kind());
        assertEquals("自动化设备", rows.getFirst().name());
        assertEquals(1234.56, rows.getFirst().last(), 0.001);
        assertEquals(5.86, rows.getFirst().changePercent(), 0.001);
        assertEquals("某股", rows.getFirst().leader());
        assertEquals(10.02, rows.getFirst().leaderPercent(), 0.001);
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
