package com.lb.financefresh.market;

/** Latest print from Tencent Finance. A-shares and HK have no US-style overnight leg. */
public record ChinaQuote(
        String code,
        String name,
        double last,
        double prevClose,
        double change,
        double changePercent,
        String at
) {
}
