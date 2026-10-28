package com.lb.financefresh.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChinaMarketTest {

    @Test
    void parseFlowsKeepsMainNetInYuan() {
        String body = """
                {"data":{"diff":[{"f12":"000001","f14":"上证指数","f2":3674.44,"f3":0.72,"f62":4841234567,"f184":12.3}]}}
                """;
        List<ChinaFundFlow> rows = ChinaMarket.parseFlows(body);
        assertEquals(1, rows.size());
        assertEquals("000001", rows.getFirst().code());
        assertEquals("上证指数", rows.getFirst().name());
        assertEquals(0.72, rows.getFirst().changePercent(), 0.001);
        assertEquals(4841234567d, rows.getFirst().mainNet(), 1);
        assertEquals(12.3, rows.getFirst().mainPct(), 0.001);
    }

    @Test
    void parseFlowsSkipsBlankAndBrokenJson() {
        assertTrue(ChinaMarket.parseFlows("").isEmpty());
        assertTrue(ChinaMarket.parseFlows("{").isEmpty());
        assertTrue(ChinaMarket.parseFlows("{\"data\":{\"diff\":[{\"f12\":\"1\",\"f14\":\"\"}]}}").isEmpty());
    }

    @Test
    void topFlowsSplitsInflowAndOutflow() {
        List<ChinaFundFlow> rows = List.of(
                new ChinaFundFlow("1", "电子", 3, 200, 1),
                new ChinaFundFlow("2", "白酒", -1, -80, -1),
                new ChinaFundFlow("3", "半导体", 4, 500, 2),
                new ChinaFundFlow("4", "银行", 0, 0, 0));
        assertEquals(List.of("半导体", "电子"), ChinaMarket.topFlows(rows, true, 8).stream()
                .map(ChinaFundFlow::name).toList());
        assertEquals(List.of("白酒"), ChinaMarket.topFlows(rows, false, 8).stream()
                .map(ChinaFundFlow::name).toList());
    }

    @Test
    void friendlyHidesRawHttpParserNoise() {
        assertEquals("暂时连不上", ChinaMarket.friendly(new java.io.IOException(
                "HTTP/1.1 header parser received no bytes")));
        assertEquals("HTTP 502", ChinaMarket.friendly(new java.io.IOException("HTTP 502")));
    }
}
