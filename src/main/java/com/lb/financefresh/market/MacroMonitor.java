package com.lb.financefresh.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Five FRED monitors. Numbers and the notes under them are computed here, not by the model. */
@Component
public class MacroMonitor {

    private static final Logger log = LoggerFactory.getLogger(MacroMonitor.class);

    private final FredClient fred;
    private final MacroHistory history;

    public MacroMonitor(FredClient fred, MacroHistory history) {
        this.fred = fred;
        this.history = history;
    }

    public String load(LocalDate day, List<String> gaps) {
        StringBuilder out = new StringBuilder();
        out.append("近期形势（程序按 FRED 计算，模型不改这些数字）\n");
        out.append(section(
                "1. 信用与违约压力",
                "垃圾债相对国债的利差，衡量企业借贷的违约溢价。平时多在 3.0%–3.5%。短期跳过 4.5%–5.0%，往往领先股市下跌和经济放缓一到三个月。",
                credit(take("BAMLH0A0HYM2", day, 30, gaps))));
        out.append(section(
                "2. 债券期限结构",
                "10 年期减去 2 年期、10 年期减去 3 个月期。倒挂时增长数据常常还好看。更近的衰退信号是倒挂结束后重新走正：市场开始定价美联储会因为经济变差而赶紧降息。领先大约三到六个月。",
                curve("10Y-2Y", take("T10Y2Y", day, 140, gaps))
                        + curve("10Y-3M", take("T10Y3M", day, 140, gaps))));
        out.append(section(
                "3. 宏观美元水位",
                "美联储资产、财政部一般账户、隔夜逆回购，是短线市场上美元多少的三个开关。净流动性 = 资产 − 财政部账户 − 逆回购。它和标普 500 的短期涨跌通常同向，领先大约一到四周。",
                liquidity(
                        take("WALCL", day, 8, gaps),
                        take("WTREGEN", day, 8, gaps),
                        take("RRPONTSYD", day, 10, gaps))));
        out.append(section(
                "4. 就业市场高频拐点",
                "初请失业金每周四公布。四周均值连续站上 25 万–27 万人，消费和增长容易转入衰退。萨姆规则看失业率近三个月均值相对前 12 个月低点抬升了多少，到 0.50 即触发，就业变差之后常常还会加快。",
                jobs(take("ICSA", day, 12, gaps), take("SAHMCURRENT", day, 4, gaps))));
        out.append(section(
                "5. 实体增长实时合成",
                "亚特兰大联储把已经公布的零售、工业产出、新屋开工等放进模型，滚动修正当季实际 GDP 环比折年率。比官方季报早一到三个月。",
                growth(take("GDPNOW", day, 8, gaps))));
        return out.toString().strip() + "\n";
    }

    private List<FredClient.Point> take(String series, LocalDate day, int limit, List<String> gaps) {
        try {
            List<FredClient.Point> points = fred.points(series, day, limit);
            history.merge(series, points);
            return points;
        } catch (Exception e) {
            log.warn("fred {} failed: {}", series, e.toString());
            gaps.add("FRED " + series + " 失败");
            return List.of();
        }
    }

    private static String section(String title, String meaning, String result) {
        return "\n" + title + "\n意思：" + meaning + "\n" + result;
    }

    static String credit(List<FredClient.Point> newestFirst) {
        if (newestFirst.isEmpty()) return "判断：本次没有取到。\n";
        FredClient.Point latest = newestFirst.get(0);
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.US, "数值：%s 为 %.2f%%", latest.date(), latest.value()));
        if (newestFirst.size() > 1) {
            double delta = (latest.value() - newestFirst.get(1).value()) * 100.0;
            out.append(String.format(Locale.US, "，较上一发布 %+.0f bps", delta));
        }
        int ago = Math.min(20, newestFirst.size() - 1);
        double jump = ago >= 15 ? latest.value() - newestFirst.get(ago).value() : 0;
        if (ago >= 15 && jump >= 0.50) {
            out.append(String.format(Locale.US, "，近一个月上升 %.2f 个百分点", jump));
        }
        out.append("。\n判断：");
        double level = latest.value();
        if (level < 3.5) out.append("还在平时的 3.0%–3.5% 附近，违约溢价没有明显抬升。");
        else if (level < 4.5) out.append("已经高于平时的 3.5%，但还没有突破 4.5% 的紧张线。");
        else if (level < 5.0) out.append("已经突破 4.5%。企业借钱变贵，资金链偏紧。");
        else out.append("已经到 5% 以上，违约溢价处于危险区。");
        if (ago >= 15 && jump >= 0.50) out.append("近一个月的升幅算短期快速跳升。");
        return out.append("\n").toString();
    }

    static String curve(String name, List<FredClient.Point> newestFirst) {
        if (newestFirst.isEmpty()) return name + "：本次没有取到。\n";
        FredClient.Point latest = newestFirst.get(0);
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.US, "数值：%s 为 %+.2f 个百分点（%+.0f bps），日期 %s",
                name, latest.value(), latest.value() * 100.0, latest.date()));
        if (newestFirst.size() > 1) {
            double delta = (latest.value() - newestFirst.get(1).value()) * 100.0;
            out.append(String.format(Locale.US, "，较上一交易日 %+.0f bps", delta));
        }
        out.append("。\n判断：").append(curveVerdict(newestFirst)).append("\n");
        return out.toString();
    }

    static String curveVerdict(List<FredClient.Point> newestFirst) {
        double latest = newestFirst.get(0).value();
        boolean olderNegative = false;
        for (int i = 1; i < newestFirst.size(); i++) {
            if (newestFirst.get(i).value() < 0) {
                olderNegative = true;
                break;
            }
        }
        boolean previousNegative = newestFirst.size() > 1 && newestFirst.get(1).value() < 0;
        if (latest < 0) return "仍在倒挂。倒挂期间经济数据往往还坚挺，先不要把它当成衰退已经发生。";
        if (previousNegative) return "刚刚从倒挂走回正值。这是比倒挂本身更近的衰退信号，市场在定价美联储将因经济变差而降息。";
        if (olderNegative) return "倒挂已经结束，曲线回到正值。回正之后的几个月，比倒挂当时更要防增长转弱。";
        return "曲线为正，这段窗口里没有倒挂。";
    }

    static String liquidity(List<FredClient.Point> assets, List<FredClient.Point> account, List<FredClient.Point> reverseRepo) {
        if (assets.isEmpty() || account.isEmpty() || reverseRepo.isEmpty()) {
            return "判断：资产、财政部账户、逆回购没有齐，净流动性本次不算。\n";
        }
        double now = netLiquidity(assets.get(0), account.get(0), reverseRepo.get(0));
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.US,
                "数值：净流动性 %.0f 十亿美元 = 资产 %.0f（%s）− 财政部账户 %.0f（%s）− 逆回购 %.0f（%s）。资产和财政部账户的原单位是百万美元，这里已除以 1000。",
                now,
                assets.get(0).value() / 1000.0, assets.get(0).date(),
                account.get(0).value() / 1000.0, account.get(0).date(),
                reverseRepo.get(0).value(), reverseRepo.get(0).date()));
        if (assets.size() > 1 && account.size() > 1 && reverseRepo.size() > 1) {
            double before = netLiquidity(assets.get(1), account.get(1), reverseRepo.get(1));
            double delta = now - before;
            out.append(String.format(Locale.US, "较各自上一期合计 %+.0f 十亿美元。", delta));
            out.append("\n判断：");
            if (delta > 0) out.append("净流动性增加，短线美元变多。");
            else if (delta < 0) out.append("净流动性减少，短线美元变少。");
            else out.append("净流动性与上一期持平。");
        } else {
            out.append("\n判断：只有一期数据，看不出增减。");
        }
        return out.append("\n").toString();
    }

    static double netLiquidity(FredClient.Point assets, FredClient.Point account, FredClient.Point reverseRepo) {
        return assets.value() / 1000.0 - account.value() / 1000.0 - reverseRepo.value();
    }

    static String jobs(List<FredClient.Point> claims, List<FredClient.Point> sahm) {
        StringBuilder out = new StringBuilder();
        if (claims.size() < 4) {
            out.append("初请：本次不足四周，四周均值不算。\n");
        } else {
            double latestAverage = average(claims, 0, 4);
            out.append(String.format(Locale.US, "数值：初请四周均值为 %.1f 万人（截至 %s）。",
                    latestAverage / 10000.0, claims.get(0).date()));
            int above = 0;
            int samples = 0;
            for (int end = 0; end + 4 <= claims.size() && samples < 4; end++) {
                samples++;
                if (average(claims, end, 4) >= 250_000) above++;
            }
            out.append("\n判断：");
            if (latestAverage >= 270_000) out.append("四周均值已高于 27 万。");
            else if (latestAverage >= 250_000) out.append("四周均值进入 25 万–27 万的观察区。");
            else out.append("四周均值还在 25 万以下。");
            if (samples == 4 && above == 4) out.append("最近四个四周均值都在 25 万以上。");
            out.append("\n");
        }
        if (sahm.isEmpty()) {
            out.append("萨姆规则：本次没有取到。\n");
        } else {
            FredClient.Point latest = sahm.get(0);
            out.append(String.format(Locale.US, "数值：萨姆规则 %s 为 %.2f。\n判断：", latest.date(), latest.value()));
            if (latest.value() >= 0.50) out.append("已达到 0.50 的触发线，失业率相对前一年低点的抬升已够衰退经验门槛。");
            else out.append("还低于 0.50 的触发线。");
            out.append("\n");
        }
        return out.toString();
    }

    static String growth(List<FredClient.Point> nowcasts) {
        if (nowcasts.isEmpty()) return "判断：本次没有取到。\n";
        FredClient.Point latest = nowcasts.get(0);
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.US, "数值：GDPNow %s 为 %+.2f%%（当季环比折年率）。", latest.date(), latest.value()));
        out.append("\n判断：");
        if (nowcasts.size() > 1) {
            double delta = latest.value() - nowcasts.get(1).value();
            out.append(String.format(Locale.US, "较上一期预测 %+.2f 个百分点，属于%s。",
                    delta, delta > 0.05 ? "上修" : delta < -0.05 ? "下修" : "基本持平"));
        } else {
            out.append("只有一期预测。");
        }
        if (latest.value() < 0) out.append("预测值本身为负，当季增长被看成在收缩。");
        else if (latest.value() < 1.0) out.append("预测值低于 1%，当季增长偏慢。");
        else out.append("预测值还在 1% 以上。");
        return out.append("\n").toString();
    }

    private static double average(List<FredClient.Point> points, int from, int count) {
        double sum = 0;
        for (int i = from; i < from + count; i++) sum += points.get(i).value();
        return sum / count;
    }
}
