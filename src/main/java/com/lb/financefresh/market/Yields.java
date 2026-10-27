package com.lb.financefresh.market;

import java.util.Locale;

public final class Yields {

    private Yields() {}

    public static String line(Double tenYear, Double previousTen, Double twoYear, Double previousTwo) {
        String ten = series("10Y", tenYear, previousTen);
        String two = series("2Y", twoYear, previousTwo);
        if (tenYear != null && twoYear != null) {
            double spread = (tenYear - twoYear) * 100.0;
            return ten + "，" + two + "，10Y-2Y 利差 " + signed(spread) + " bps。";
        }
        return ten + "，" + two + "。";
    }

    public static double bps(double latest, double previous) {
        return (latest - previous) * 100.0;
    }

    private static String series(String name, Double latest, Double previous) {
        if (latest == null || previous == null) return name + " 本次没有取到";
        return String.format(Locale.US, "%s %.2f%%（较前一交易日 %s bps）", name, latest, signed(bps(latest, previous)));
    }

    static String signed(double value) {
        return String.format(Locale.US, "%+.1f", value);
    }
}
