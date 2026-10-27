package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.Quote;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlpacaClientTest {

    private final AlpacaClient client = new AlpacaClient(null, null, new ObjectMapper());
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void premarketPrintUsesTheLatestTradeAgainstThePriorClose() throws Exception {
        String body = """
                {"snapshots":{"NVDA":{
                  "latestTrade":{"p":101.0,"t":"2026-10-15T12:30:00Z"},
                  "dailyBar":{"c":99.0},
                  "prevDailyBar":{"c":100.0}
                }}}
                """;
        Quote quote = client.quotesFrom(json.readTree(body), List.of("NVDA"), BriefKind.PREMARKET).getFirst();
        assertEquals("盘前", quote.session());
        assertEquals(101.0, quote.lastPrice());
        assertEquals(2.0202, quote.premarketChangePercent(), 0.01);
        assertEquals(-1.0, quote.regularChangePercent(), 0.001);
        assertNull(quote.afterHoursChangePercent());
        assertNull(quote.overnightChangePercent());
        assertEquals("盘中 99.00 -1.00%；盘前 101.00 +2.02%；盘后 暂无；夜盘 暂无", quote.sessionBreakdown());
    }

    @Test
    void aTradeAfterTheCloseIsNotLabeledPremarket() throws Exception {
        String body = """
                {"SPY":{
                  "latestTrade":{"p":768.84,"t":"2026-10-14T20:47:18Z"},
                  "dailyBar":{"c":769.65},
                  "prevDailyBar":{"c":778.03}
                }}
                """;
        Quote quote = client.quotesFrom(json.readTree(body), List.of("SPY"), BriefKind.PREMARKET).getFirst();
        assertEquals("盘后", quote.session());
        assertEquals(768.84, quote.lastPrice());
    }

    @Test
    void recapUsesTheSessionCloseAndKeepsAnAfterHoursMove() throws Exception {
        String body = """
                {"snapshots":{"AAPL":{
                  "latestTrade":{"p":105.0,"t":"2026-10-14T20:30:00Z"},
                  "dailyBar":{"c":100.0},
                  "prevDailyBar":{"c":80.0}
                }}}
                """;
        Quote quote = client.quotesFrom(json.readTree(body), List.of("AAPL"), BriefKind.RECAP).getFirst();
        assertEquals("收盘", quote.session());
        assertEquals(100.0, quote.lastPrice());
        assertEquals(25.0, quote.regularChangePercent(), 0.001);
        assertEquals(5.0, quote.afterHoursChangePercent(), 0.001);
        assertNull(quote.premarketChangePercent());
        assertNull(quote.overnightChangePercent());
        assertEquals("盘中 100.00 +25.00%；盘前 暂无；盘后 105.00 +5.00%；夜盘 暂无", quote.sessionBreakdown());
    }

    @Test
    void newsKeepsHeadlinesInsideTheWindow() throws Exception {
        String body = """
                {"news":[
                  {"headline":"英伟达上调指引","summary":"","url":"https://news.example/nvda","symbols":["NVDA"],"created_at":"2026-10-15T12:00:00Z"},
                  {"headline":"过期","summary":"","url":"https://news.example/old","symbols":["AAPL"],"created_at":"2026-10-14T12:00:00Z"}
                ]}
                """;
        var items = client.newsFrom(
                json.readTree(body),
                Instant.parse("2026-10-15T00:00:00Z"),
                Instant.parse("2026-10-16T00:00:00Z"));
        assertEquals(1, items.size());
        assertEquals("英伟达上调指引", items.getFirst().title());
        assertEquals("https://news.example/nvda", items.getFirst().url());
        assertEquals(List.of("NVDA"), items.getFirst().symbols());
        assertEquals("alpaca", items.getFirst().source());
    }

    @Test
    void weekendPrintIsThePriorSession() {
        assertEquals("上一交易日收盘", AlpacaClient.sessionOf(Instant.parse("2026-10-17T14:00:00Z")));
        assertTrue(AlpacaClient.sessionOf(Instant.parse("2026-10-15T13:30:00Z")).equals("盘中"));
    }

    @Test
    void overnightWindowIsLabeledNightSession() {
        // Monday 21:30 ET = Tuesday 01:30 UTC during EDT
        assertEquals("夜盘", AlpacaClient.sessionOf(Instant.parse("2026-10-21T01:30:00Z")));
        // Tuesday 02:00 ET = Tuesday 06:00 UTC
        assertEquals("夜盘", AlpacaClient.sessionOf(Instant.parse("2026-10-21T06:00:00Z")));
        assertTrue(AlpacaClient.inOvernightWindow(Instant.parse("2026-10-21T01:30:00Z")));
        assertTrue(!AlpacaClient.inOvernightWindow(Instant.parse("2026-10-21T20:00:00Z")));
    }

    @Test
    void currentBriefPrefersAFresherOvernightTrade() throws Exception {
        String iex = """
                {"snapshots":{"NVDA":{
                  "latestTrade":{"p":100.0,"t":"2026-10-21T23:30:00Z"},
                  "dailyBar":{"c":99.0},
                  "prevDailyBar":{"c":100.0}
                }}}
                """;
        String overnight = """
                {"snapshots":{"NVDA":{
                  "latestTrade":{"p":102.0,"t":"2026-10-22T02:15:00Z"},
                  "dailyBar":{"c":99.0},
                  "prevDailyBar":{"c":100.0}
                }}}
                """;
        Quote quote = client.quotesFrom(
                json.readTree(iex), json.readTree(overnight), List.of("NVDA"), BriefKind.NOW).getFirst();
        assertEquals("夜盘", quote.session());
        assertEquals(102.0, quote.lastPrice());
        assertEquals(-1.0, quote.regularChangePercent(), 0.001);
        assertEquals(1.0101, quote.afterHoursChangePercent(), 0.01);
        assertEquals(3.0303, quote.overnightChangePercent(), 0.01);
        assertNull(quote.premarketChangePercent());
        assertEquals("盘中 99.00 -1.00%；盘前 暂无；盘后 100.00 +1.01%；夜盘 102.00 +3.03%", quote.sessionBreakdown());
    }

    @Test
    void nowBriefKeepsRegularSessionAndOvernightLegsSeparate() throws Exception {
        String body = """
                {"snapshots":{"NVDA":{
                  "latestTrade":{"p":98.3,"t":"2026-10-23T01:01:00Z"},
                  "dailyBar":{"c":95.6},
                  "prevDailyBar":{"c":100.0}
                }}}
                """;
        Quote quote = client.quotesFrom(json.readTree(body), List.of("NVDA"), BriefKind.NOW).getFirst();
        assertEquals("夜盘", quote.session());
        assertEquals(98.3, quote.lastPrice());
        assertEquals(-4.40, quote.regularChangePercent(), 0.001);
        assertEquals(2.8243, quote.overnightChangePercent(), 0.01);
        assertNull(quote.afterHoursChangePercent());
        assertEquals("盘中 95.60 -4.40%；盘前 暂无；盘后 暂无；夜盘 98.30 +2.82%", quote.sessionBreakdown());
    }

    @Test
    void aOneSidedCloseQuoteIsNotAfterHours() throws Exception {
        String iex = """
                {"snapshots":{"NVDA":{
                  "latestTrade":{"p":212.27,"t":"2026-10-22T19:59:59Z"},
                  "latestQuote":{"bp":201.9,"ap":0,"t":"2026-10-22T20:00:02Z"},
                  "dailyBar":{"c":212.27},
                  "prevDailyBar":{"c":221.9}
                }}}
                """;
        String overnight = """
                {"snapshots":{"NVDA":{
                  "latestTrade":{"p":213.61,"t":"2026-10-23T01:43:53Z"},
                  "dailyBar":{"c":212.27},
                  "prevDailyBar":{"c":221.9}
                }}}
                """;
        Quote quote = client.quotesFrom(
                json.readTree(iex), json.readTree(overnight), List.of("NVDA"), BriefKind.NOW).getFirst();
        assertEquals("夜盘", quote.session());
        assertEquals(-4.34, quote.regularChangePercent(), 0.01);
        assertEquals(212.27, quote.regular().price(), 0.001);
        assertNull(quote.afterHoursChangePercent());
        assertEquals(213.61, quote.lastPrice(), 0.001);
        assertEquals(0.63, quote.overnightChangePercent(), 0.01);
        assertEquals("盘中 212.27 -4.34%；盘前 暂无；盘后 暂无；夜盘 213.61 +0.63%", quote.sessionBreakdown());
    }

    @Test
    void overnightPrefersALiveQuoteWhenTheTradeIsStillTheAfterHoursPrint() throws Exception {
        String iex = """
                {"NVDA":{
                  "latestTrade":{"p":100.0,"t":"2026-10-21T23:47:00Z"},
                  "dailyBar":{"c":99.0},
                  "prevDailyBar":{"c":100.0}
                }}
                """;
        String overnight = """
                {"NVDA":{
                  "latestTrade":{"p":100.2,"t":"2026-10-21T23:50:00Z"},
                  "latestQuote":{"bp":103.0,"ap":103.4,"t":"2026-10-22T02:20:00Z"},
                  "dailyBar":{"c":99.0},
                  "prevDailyBar":{"c":100.0}
                }}
                """;
        Quote quote = client.quotesFrom(
                json.readTree(iex), json.readTree(overnight), List.of("NVDA"), BriefKind.NOW).getFirst();
        assertEquals("夜盘", quote.session());
        assertEquals(103.2, quote.lastPrice(), 0.001);
        assertEquals(-1.0, quote.regularChangePercent(), 0.001);
        assertEquals(1.0101, quote.afterHoursChangePercent(), 0.01);
        assertEquals(4.2424, quote.overnightChangePercent(), 0.01);
    }

    @Test
    void allFourSessionsStayOnTheirOwnLegs() throws Exception {
        String iex = """
                {"snapshots":{"NVDA":{
                  "latestTrade":{"p":101.0,"t":"2026-10-15T12:30:00Z"},
                  "dailyBar":{"c":99.0},
                  "prevDailyBar":{"c":100.0}
                }}}
                """;
        String overnight = """
                {"snapshots":{"NVDA":{
                  "latestTrade":{"p":102.0,"t":"2026-10-15T04:15:00Z"},
                  "dailyBar":{"c":99.0},
                  "prevDailyBar":{"c":100.0}
                }}}
                """;
        Quote quote = client.quotesFrom(
                json.readTree(iex), json.readTree(overnight), List.of("NVDA"), BriefKind.PREMARKET).getFirst();
        assertEquals("盘前", quote.session());
        assertEquals(-1.0, quote.regularChangePercent(), 0.001);
        assertEquals(2.0202, quote.premarketChangePercent(), 0.01);
        assertNull(quote.afterHoursChangePercent());
        assertEquals(3.0303, quote.overnightChangePercent(), 0.01);
        assertEquals("盘中 99.00 -1.00%；盘前 101.00 +2.02%；盘后 暂无；夜盘 102.00 +3.03%", quote.sessionBreakdown());
    }
}
