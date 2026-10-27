package com.lb.financefresh.market;

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
 */
@Component
public class TencentQuotes {

    static final Charset GBK = Charset.forName("GBK");
    static final List<String> CODES = List.of(
            "sh000001",
            "sz399001",
            "sz399006",
            "sh000300",
            "r_hkHSI",
            "r_hk00700",
            "r_hk09988",
            "r_hk03690",
            "r_hk01810",
            "r_hk09999");
    private static final URI URL = URI.create("https://qt.gtimg.cn/q=" + String.join(",", CODES));
    private static final Map<String, String> HEADERS = Map.of(
            "Referer", "https://finance.qq.com/",
            "Accept-Language", "zh-CN,zh;q=0.9");

    private final ExternalHttp http;

    public TencentQuotes(ExternalHttp http) {
        this.http = http;
    }

    public List<ChinaQuote> latest() throws Exception {
        if (http == null) return List.of();
        return parse(http.get(URL, HEADERS, GBK, true));
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
            double last = number(parts[3]);
            double prev = number(parts[4]);
            double change = number(parts[31]);
            double percent = number(parts[32]);
            if (name.isBlank() || last <= 0) continue;
            out.add(new ChinaQuote(key, name, last, prev, change, percent, clock(parts[30])));
        }
        return List.copyOf(out);
    }

    private static String unquote(String raw) {
        String text = raw.strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static double number(String raw) {
        if (raw == null || raw.isBlank()) return 0;
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
