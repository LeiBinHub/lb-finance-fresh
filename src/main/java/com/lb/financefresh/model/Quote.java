package com.lb.financefresh.model;

import java.util.Locale;

/**
 * Four session legs, never blended.
 * <ul>
 *   <li>盘中 {@code regular}: last regular print versus prior close</li>
 *   <li>盘前 {@code premarket}: pre-market print versus last regular close</li>
 *   <li>盘后 {@code afterHours}: after-hours print versus today's regular close</li>
 *   <li>夜盘 {@code overnight}: overnight print versus today's regular close</li>
 * </ul>
 */
public record Quote(
        String symbol,
        double lastPrice,
        String session,
        SessionLeg regular,
        SessionLeg premarket,
        SessionLeg afterHours,
        SessionLeg overnight
) {

    public record SessionLeg(double price, double changePercent) {}

    public Double regularChangePercent() {
        return percent(regular);
    }

    public Double premarketChangePercent() {
        return percent(premarket);
    }

    public Double afterHoursChangePercent() {
        return percent(afterHours);
    }

    public Double overnightChangePercent() {
        return percent(overnight);
    }

    public boolean notable(double threshold) {
        return notable(regular, threshold)
                || notable(premarket, threshold)
                || notable(afterHours, threshold)
                || notable(overnight, threshold);
    }

    /** Compact one-liner used in tests. Missing legs are 暂无. */
    public String sessionBreakdown() {
        return format("盘中", regular)
                + "；" + format("盘前", premarket)
                + "；" + format("盘后", afterHours)
                + "；" + format("夜盘", overnight);
    }

    /** One markdown table: 盘前, 盘中, 盘后, 夜盘. */
    public String sessionTable() {
        return "| 时段 | 价格 | 涨跌 |\n"
                + "| --- | --- | --- |\n"
                + row("盘前", premarket)
                + row("盘中", regular)
                + row("盘后", afterHours)
                + row("夜盘", overnight);
    }

    private static String row(String label, SessionLeg leg) {
        if (leg == null) return "| " + label + " | 暂无 | 暂无 |\n";
        return String.format(Locale.US, "| %s | %.2f | %+.2f%% |\n", label, leg.price(), leg.changePercent());
    }

    private static Double percent(SessionLeg leg) {
        return leg == null ? null : leg.changePercent();
    }

    private static boolean notable(SessionLeg leg, double threshold) {
        return leg != null && Math.abs(leg.changePercent()) >= threshold;
    }

    private static String format(String label, SessionLeg leg) {
        if (leg == null) return label + " 暂无";
        return String.format(Locale.US, "%s %.2f %+.2f%%", label, leg.price(), leg.changePercent());
    }
}
