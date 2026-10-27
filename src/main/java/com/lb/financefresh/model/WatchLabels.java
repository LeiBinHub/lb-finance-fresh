package com.lb.financefresh.model;

import java.util.Locale;
import java.util.Set;

/** Display names for watchlist symbols that are not ordinary company tickers. */
public final class WatchLabels {

    private static final Set<String> INDEX_ONLY = Set.of("VIX");

    private WatchLabels() {}

    public static String display(String symbol) {
        if (symbol == null || symbol.isBlank()) return "";
        String code = symbol.toUpperCase(Locale.ROOT);
        return switch (code) {
            case "USO" -> "原油 USO";
            case "GLD" -> "黄金 GLD";
            case "VIX" -> "恐慌指数 VIX";
            default -> code;
        };
    }

    /** CBOE VIX is an index. Alpaca stock snapshots and Finnhub company news do not cover it. */
    public static boolean indexOnly(String symbol) {
        return symbol != null && INDEX_ONLY.contains(symbol.toUpperCase(Locale.ROOT));
    }
}
