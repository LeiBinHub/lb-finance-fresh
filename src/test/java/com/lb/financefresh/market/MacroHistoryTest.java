package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroHistoryTest {

    @TempDir
    Path dir;

    private static FredClient.Point point(String date, double value) {
        return new FredClient.Point(LocalDate.parse(date), value);
    }

    @Test
    void laterFetchAddsAPointAndKeepsTheOlderOnes() throws Exception {
        MacroHistory history = new MacroHistory(new ObjectMapper(), dir.resolve("history.json"));
        history.merge("BAMLH0A0HYM2", List.of(point("2026-10-08", 3.10), point("2026-10-09", 3.20)));
        history.merge("BAMLH0A0HYM2", List.of(point("2026-10-09", 3.25), point("2026-10-10", 3.40)));
        List<FredClient.Point> saved = history.charts().get("BAMLH0A0HYM2");
        assertEquals(3, saved.size());
        assertEquals(LocalDate.parse("2026-10-08"), saved.get(0).date());
        assertEquals(3.25, saved.get(1).value(), 0.001);
        assertEquals(LocalDate.parse("2026-10-10"), saved.get(2).date());
    }

    @Test
    void netLiquidityUsesEverySavedAssetDate() {
        List<FredClient.Point> series = MacroHistory.netLiquidity(
                List.of(point("2026-10-01", 6_700_000), point("2026-10-08", 6_800_000)),
                List.of(point("2026-10-01", 650_000), point("2026-10-08", 700_000)),
                List.of(point("2026-10-01", 450), point("2026-10-08", 400)));
        assertEquals(2, series.size());
        assertEquals(5_600, series.get(0).value(), 0.1);
        assertTrue(series.get(1).value() > series.get(0).value());
    }
}
