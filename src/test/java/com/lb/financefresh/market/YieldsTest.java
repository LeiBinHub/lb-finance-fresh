package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YieldsTest {

    @Test
    void bpsIsHundredthsOfAPercentPoint() {
        assertEquals(3.2, Yields.bps(4.212, 4.180), 0.05);
        String line = Yields.line(4.21, 4.18, 3.90, 3.91);
        assertTrue(line.contains("10Y 4.21%"));
        assertTrue(line.contains("+3.0 bps"));
        assertTrue(line.contains("-1.0 bps"));
        assertTrue(line.contains("10Y-2Y 利差 +31.0 bps"));
    }

    @Test
    void treasurySeriesKeepsTheLatestTwoPointsOnOrBeforeTheDay() throws Exception {
        String body = """
                {"data":[
                  {"date":"2026-10-15","value":"9.99"},
                  {"date":"2026-10-14","value":"4.21"},
                  {"date":"2026-10-13","value":"."},
                  {"date":"2026-10-10","value":"4.18"}
                ]}
                """;
        double[] yields = AlphaVantageClient.lastTwo(new ObjectMapper().readTree(body), LocalDate.of(2026, 10, 14));
        assertEquals(4.21, yields[0], 0.001);
        assertEquals(4.18, yields[1], 0.001);
    }
}
