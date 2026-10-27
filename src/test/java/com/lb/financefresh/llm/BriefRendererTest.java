package com.lb.financefresh.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.Quote;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BriefRendererTest {

    @Test
    void eachWatchlistNameGetsAFourSessionTable() throws Exception {
        Quote quote = new Quote(
                "USO",
                70.5,
                "夜盘",
                new Quote.SessionLeg(69.0, -1.5),
                null,
                new Quote.SessionLeg(70.0, 1.45),
                new Quote.SessionLeg(70.5, 2.17));
        String text = new BriefRenderer(new FreshSettings()).render(
                BriefKind.NOW,
                LocalDate.parse("2026-10-23"),
                "10Y 4.00%",
                "",
                List.of(quote),
                List.of(),
                new ObjectMapper().readTree("{}"));
        assertTrue(text.contains("🎯 自选股异动归因"));
        assertTrue(text.contains("原油 USO · 夜盘"));
        assertTrue(text.contains("| 盘前 | 暂无 | 暂无 |"));
        assertTrue(text.contains("| 盘中 | 69.00 | -1.50% |"));
        assertTrue(text.contains("| 盘后 | 70.00 | +1.45% |"));
        assertTrue(text.contains("| 夜盘 | 70.50 | +2.17% |"));
    }
}
