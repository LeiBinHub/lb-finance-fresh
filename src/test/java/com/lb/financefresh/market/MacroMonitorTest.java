package com.lb.financefresh.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroMonitorTest {

    private static FredClient.Point point(String date, double value) {
        return new FredClient.Point(LocalDate.parse(date), value);
    }

    @Test
    void creditSpreadNamesTheBand() {
        String calm = MacroMonitor.credit(List.of(point("2026-10-09", 3.20), point("2026-10-08", 3.10)));
        assertTrue(calm.contains("3.20%"));
        assertTrue(calm.contains("没有明显抬升"));

        String stressed = MacroMonitor.credit(List.of(point("2026-10-09", 5.20)));
        assertTrue(stressed.contains("5% 以上"));
    }

    @Test
    void uninversionIsTheNearerRecessionSignal() {
        String still = MacroMonitor.curveVerdict(List.of(point("2026-10-09", -0.12), point("2026-10-08", -0.20)));
        assertTrue(still.contains("仍在倒挂"));

        String just = MacroMonitor.curveVerdict(List.of(point("2026-10-09", 0.08), point("2026-10-08", -0.04)));
        assertTrue(just.contains("刚刚从倒挂走回正值"));
    }

    @Test
    void netLiquidityConvertsMillionsAndSubtractsTheReverseRepo() {
        double billions = MacroMonitor.netLiquidity(
                point("2026-10-08", 6_800_000),
                point("2026-10-08", 700_000),
                point("2026-10-09", 400));
        assertEquals(5_700, billions, 0.1);

        String text = MacroMonitor.liquidity(
                List.of(point("2026-10-08", 6_800_000), point("2026-10-01", 6_700_000)),
                List.of(point("2026-10-08", 700_000), point("2026-10-01", 650_000)),
                List.of(point("2026-10-09", 400), point("2026-10-08", 450)));
        assertTrue(text.contains("5700"));
        assertTrue(text.contains("净流动性增加"));
    }

    @Test
    void claimsAndSahmUseThePublishedThresholds() {
        List<FredClient.Point> claims = List.of(
                point("2026-10-09", 280_000),
                point("2026-10-02", 275_000),
                point("2026-09-25", 272_000),
                point("2026-09-18", 271_000));
        String text = MacroMonitor.jobs(claims, List.of(point("2026-09-01", 0.62)));
        assertTrue(text.contains("高于 27 万"));
        assertTrue(text.contains("0.62"));
        assertTrue(text.contains("已达到 0.50"));
    }

    @Test
    void gdpNowRecordsADownwardRevision() {
        String text = MacroMonitor.growth(List.of(point("2026-10-09", 1.20), point("2026-10-07", 1.80)));
        assertTrue(text.contains("1.20%"));
        assertTrue(text.contains("下修"));
    }
}
