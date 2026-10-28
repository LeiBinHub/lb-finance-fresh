package com.lb.financefresh.market;

/** Latest print from Tencent Finance. A-shares and HK have no US-style overnight leg. */
public record ChinaQuote(
        String code,
        String name,
        double last,
        double prevClose,
        double change,
        double changePercent,
        String at,
        double high,
        double low,
        double volume,
        double amount,
        double turnover,
        double pe,
        double amplitude,
        double circCap,
        double totalCap
) {
    public ChinaQuote(String code, String name, double last, double prevClose, double change, double changePercent,
                      String at) {
        this(code, name, last, prevClose, change, changePercent, at, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
