package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Free delayed/latest prints from {@code qt.gtimg.cn}. GBK payload, no API key.
 * {@code r_hk*} is the delayed HK feed (usually newer than {@code hk*} during the session).
 * Industry/concept ranks come from {@code ifzq.gtimg.cn}.
 */
@Component
public class TencentQuotes {

    static final Charset GBK = Charset.forName("GBK");
    static final List<String> CODES = List.of(
            "sh000001",
            "sz399001",
            "sz399006",
            "sh000300",
            "sh000016",
            "sh000905",
            "sh000688",
            "r_hkHSI",
            "r_hkHSTECH",
            "r_hk00700",
            "r_hk09988",
            "r_hk03690",
            "r_hk01810",
            "r_hk09999");
    static final URI QUOTE_URL = URI.create("https://qt.gtimg.cn/q=" + String.join(",", CODES));
    static final URI INDUSTRY_UP = URI.create("https://ifzq.gtimg.cn/appstock/app/mktHs/rank?l=8&p=1&t=01/averatio&o=0");
    static final URI INDUSTRY_DOWN = URI.create("https://ifzq.gtimg.cn/appstock/app/mktHs/rank?l=8&p=1&t=01/averatio&o=1");
    static final URI CONCEPT_UP = URI.create("https://ifzq.gtimg.cn/appstock/app/mktHs/rank?l=8&p=1&t=02/averatio&o=0");
    private static final Map<String, String> HEADERS = Map.of(
            "Referer", "https://finance.qq.com/",
            "Accept-Language", "zh-CN,zh;q=0.9");

    private final ExternalHttp http;

    public TencentQuotes(ExternalHttp http) {
        this.http = http;
    }

    public List<ChinaQuote> latest() throws Exception {
        if (http == null) return List.of();
        return parse(http.get(QUOTE_URL, HEADERS, GBK, true));
    }

    public List<ChinaSector> industryUp() throws Exception {
        return ranks("industry", INDUSTRY_UP);
    }

    public List<ChinaSector> industryDown() throws Exception {
        return ranks("industry", INDUSTRY_DOWN);
    }

    public List<ChinaSector> conceptUp() throws Exception {
        return ranks("concept", CONCEPT_UP);
    }

    private List<ChinaSector> ranks(String kind, URI uri) throws Exception {
        if (http == null) return List.of();
        return parseRanks(kind, http.get(uri, HEADERS, false));
    }

    static List<ChinaQuote> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<ChinaQuote> out = new ArrayList<>();
        for (String raw : body.split(";")) {
            String line = raw.strip();
            if (line.isBlank() || !line.contains("=")) continue;
            int eq = line.indexOf('=');
            String key = line.substring(0, eq).strip();
            if (key.startsWith("v_")) key = key.substring(2);
            String payload = unquote(line.substring(eq + 1).strip());
            String[] parts = payload.split("~", -1);
            if (parts.length < 33) continue;
            String name = parts[1].strip();
            double last = number(parts, 3);
            double prev = number(parts, 4);
            double change = number(parts, 31);
            double percent = number(parts, 32);
            if (name.isBlank() || last <= 0) continue;
            out.add(new ChinaQuote(
                    key,
                    name,
                    last,
                    prev,
                    change,
                    percent,
                    clock(parts[30]),
                    number(parts, 33),
                    number(parts, 34),
                    number(parts, 36),
                    number(parts, 37),
                    number(parts, 38),
                    number(parts, 39),
                    number(parts, 43),
                    number(parts, 44),
                    number(parts, 45)));
        }
        return List.copyOf(out);
    }

    static List<ChinaSector> parseRanks(String kind, String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode rows = new ObjectMapper().readTree(body).path("data");
            if (!rows.isArray()) return List.of();
            List<ChinaSector> out = new ArrayList<>();
            for (JsonNode row : rows) {
                String name = row.path("bd_name").asText("").strip();
                if (name.isBlank()) continue;
                out.add(new ChinaSector(
                        kind == null || kind.isBlank() ? "industry" : kind,
                        name,
                        number(row.path("bd_zxj").asText("")),
                        number(row.path("bd_zdf").asText("")),
                        row.path("nzg_name").asText("").strip(),
                        number(row.path("nzg_zdf").asText(""))));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String unquote(String raw) {
        String text = raw.strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static double number(String[] parts, int index) {
        if (parts == null || index < 0 || index >= parts.length) return 0;
        return number(parts[index]);
    }

    private static double number(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw.strip())) return 0;
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String clock(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.matches("\\d{14}")) {
            return text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6, 8)
                    + " " + text.substring(8, 10) + ":" + text.substring(10, 12) + ":" + text.substring(12, 14);
        }
        return text.replace('/', '-');
    }

    static String displayCode(String code) {
        if (code == null) return "";
        String value = code.toLowerCase(Locale.ROOT);
        if (value.startsWith("r_")) value = value.substring(2);
        return value;
    }
}
