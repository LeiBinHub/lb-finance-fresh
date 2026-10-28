package com.lb.financefresh.market;

/** Index or industry main-force flow. Amounts are yuan. */
public record ChinaFundFlow(
        String code,
        String name,
        double changePercent,
        double mainNet,
        double mainPct
) {
}
