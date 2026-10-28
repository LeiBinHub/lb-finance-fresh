package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Live China board: Tencent quotes + Tencent industry/concept ranks + Eastmoney main-force flow.
 * Tencent {@code ff_*} fund-flow endpoints currently return {@code v_pv_none_match}.
 */
@Component
public class ChinaMarket {

    static final URI INDEX_FLOW = URI.create(
            "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2"
                    + "&secids=1.000001,0.399001,0.399006,1.000300,1.000016,1.000905,1.000688"
                    + "&fields=f12,f14,f2,f3,f62,f184");
    /** Eastmoney {@code clist} currently drops HTTP/1.1; industry boards go through {@code ulist}. */
    static final URI INDUSTRY_FLOW = URI.create(
            "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2&secids="
                    + String.join(",", List.of(
                    "90.BK0428", "90.BK0433", "90.BK0437", "90.BK0438", "90.BK0447", "90.BK0448",
                    "90.BK0451", "90.BK0456", "90.BK0457", "90.BK0459", "90.BK0464", "90.BK0465",
                    "90.BK0478", "90.BK0479", "90.BK0480", "90.BK0481", "90.BK0486", "90.BK0490",
                    "90.BK0538", "90.BK0545", "90.BK0727", "90.BK0728", "90.BK0732", "90.BK0735",
                    "90.BK0736", "90.BK0737", "90.BK0739", "90.BK1029", "90.BK1033", "90.BK1036",
                    "90.BK1037"))
                    + "&fields=f12,f14,f2,f3,f62,f184");
    private static final Map<String, String> EASTMONEY = Map.of(
            "Referer", "https://data.eastmoney.com/bkzj/hy.html",
            "Accept", "application/json,text/plain,*/*");

    private final TencentQuotes quotes;
    private final ExternalHttp http;

    public ChinaMarket(TencentQuotes quotes, ExternalHttp http) {
        this.quotes = quotes;
        this.http = http;
    }

    public Snapshot latest() {
        List<String> errors = new ArrayList<>();
        List<ChinaQuote> prints = fetch("腾讯行情", errors, () -> quotes == null ? List.of() : quotes.latest());
        List<ChinaSector> industriesUp = fetch("腾讯行业涨幅榜", errors, quotes == null ? List::of : quotes::industryUp);
        List<ChinaSector> industriesDown = fetch("腾讯行业跌幅榜", errors, quotes == null ? List::of : quotes::industryDown);
        List<ChinaSector> conceptsUp = fetch("腾讯概念涨幅榜", errors, quotes == null ? List::of : quotes::conceptUp);
        List<ChinaFundFlow> indexFlows = http == null ? List.of()
                : fetch("东财指数资金", errors, () -> parseFlows(http.get(INDEX_FLOW, EASTMONEY, true)));
        List<ChinaFundFlow> industryFlows = http == null ? List.of()
                : fetch("东财行业资金", errors, () -> parseFlows(http.get(INDUSTRY_FLOW, EASTMONEY, true)));
        List<ChinaFundFlow> industryIn = topFlows(industryFlows, true, 8);
        List<ChinaFundFlow> industryOut = topFlows(industryFlows, false, 8);
        return new Snapshot(
                prints,
                indexFlows,
                industryIn,
                industryOut,
                industriesUp,
                industriesDown,
                conceptsUp,
                String.join("；", errors));
    }

    static List<ChinaFundFlow> parseFlows(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode rows = new ObjectMapper().readTree(body).path("data").path("diff");
            if (!rows.isArray()) return List.of();
            List<ChinaFundFlow> out = new ArrayList<>();
            for (JsonNode row : rows) {
                String name = row.path("f14").asText("").strip();
                if (name.isBlank()) continue;
                out.add(new ChinaFundFlow(
                        row.path("f12").asText("").strip(),
                        name,
                        number(row.path("f3")),
                        number(row.path("f62")),
                        number(row.path("f184"))));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<ChinaFundFlow> topFlows(List<ChinaFundFlow> rows, boolean inflow, int limit) {
        if (rows == null || rows.isEmpty() || limit <= 0) return List.of();
        return rows.stream()
                .filter(row -> row != null && (inflow ? row.mainNet() > 0 : row.mainNet() < 0))
                .sorted((a, b) -> inflow
                        ? Double.compare(b.mainNet(), a.mainNet())
                        : Double.compare(a.mainNet(), b.mainNet()))
                .limit(limit)
                .toList();
    }

    private static <T> List<T> fetch(String label, List<String> errors, Fetch<List<T>> call) {
        try {
            List<T> value = call.get();
            return value == null ? List.of() : value;
        } catch (Exception e) {
            errors.add(label + "：" + friendly(e));
            return List.of();
        }
    }

    static String friendly(Exception error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        String lower = message.toLowerCase();
        if (message.isBlank()
                || lower.contains("header parser")
                || lower.contains("connection")
                || lower.contains("eof")
                || message.contains("连接")) {
            return "暂时连不上";
        }
        return message;
    }

    private static double number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return 0;
        if (node.isNumber()) return node.asDouble();
        String raw = node.asText("").strip();
        if (raw.isBlank() || "-".equals(raw) || "-".equals(raw.replace("%", ""))) return 0;
        try {
            return Double.parseDouble(raw.replace("%", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @FunctionalInterface
    private interface Fetch<T> {
        T get() throws Exception;
    }

    public record Snapshot(
            List<ChinaQuote> quotes,
            List<ChinaFundFlow> indexFlows,
            List<ChinaFundFlow> industryIn,
            List<ChinaFundFlow> industryOut,
            List<ChinaSector> industriesUp,
            List<ChinaSector> industriesDown,
            List<ChinaSector> conceptsUp,
            String error
    ) {
        public Snapshot {
            quotes = quotes == null ? List.of() : List.copyOf(quotes);
            indexFlows = indexFlows == null ? List.of() : List.copyOf(indexFlows);
            industryIn = industryIn == null ? List.of() : List.copyOf(industryIn);
            industryOut = industryOut == null ? List.of() : List.copyOf(industryOut);
            industriesUp = industriesUp == null ? List.of() : List.copyOf(industriesUp);
            industriesDown = industriesDown == null ? List.of() : List.copyOf(industriesDown);
            conceptsUp = conceptsUp == null ? List.of() : List.copyOf(conceptsUp);
            error = error == null ? "" : error;
        }

        public static Snapshot empty() {
            return ofQuotes(List.of());
        }

        public static Snapshot ofQuotes(List<ChinaQuote> quotes) {
            return new Snapshot(quotes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "");
        }

        public boolean hasQuotes() {
            return !quotes.isEmpty();
        }
    }
}
