package com.lb.financefresh.market;

/** Tencent industry/concept rank row. */
public record ChinaSector(
        String kind,
        String name,
        double last,
        double changePercent,
        String leader,
        double leaderPercent
) {
}
