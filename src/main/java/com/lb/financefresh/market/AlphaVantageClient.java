package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.model.NewsItem;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Alpha Vantage uses a single API key. There is no secret. */
@Component
public class AlphaVantageClient {

    private static final DateTimeFormatter PUBLISHED = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;
    private final ApiFailures failures;

    public AlphaVantageClient(FreshSettings settings, ExternalHttp http, ObjectMapper json, ApiFailures failures) {
        this.settings = settings;
        this.http = http;
        this.json = json;
        this.failures = failures;
    }

    public List<NewsItem> news(List<String> symbols, Instant since, Instant until) throws Exception {
        if (!settings.alphaVantageReady() || symbols.isEmpty()) return List.of();
        String tickers = String.join(",", symbols);
        URI uri = URI.create("https://www.alphavantage.co/query?function=NEWS_SENTIMENT&limit=50&tickers="
                + URLEncoder.encode(tickers, StandardCharsets.UTF_8)
                + "&apikey=" + URLEncoder.encode(settings.alphaVantageKey(), StandardCharsets.UTF_8));
        JsonNode root = json.readTree(http.get(uri, java.util.Map.of()));
        if (root.has("Note") || root.has("Information")) {
            String note = root.path("Note").asText(root.path("Information").asText(""));
            String detail = note.toLowerCase(Locale.ROOT).contains("premium")
                    ? "免费档不提供"
                    : "拒绝了这次请求";
            failures.add(uri, detail);
            if (note.toLowerCase(Locale.ROOT).contains("premium")) {
                throw new IllegalStateException("Alpha Vantage 免费档这次不提供新闻接口");
            }
            throw new IllegalStateException(note.isBlank() ? "Alpha Vantage 拒绝了这次请求" : note);
        }
        List<NewsItem> out = new ArrayList<>();
        for (JsonNode node : root.path("feed")) {
            Instant published = parseTime(node.path("time_published").asText(""));
            if (published.isBefore(since) || !published.isBefore(until)) continue;
            List<String> tags = new ArrayList<>();
            double sentiment = node.path("overall_sentiment_score").asDouble(0);
            for (JsonNode ticker : node.path("ticker_sentiment")) {
                String symbol = ticker.path("ticker").asText("").toUpperCase(Locale.ROOT);
                if (!symbol.isBlank()) tags.add(symbol);
                if (symbols.contains(symbol)) {
                    sentiment = ticker.path("ticker_sentiment_score").asDouble(sentiment);
                }
            }
            out.add(new NewsItem(
                    "alphavantage",
                    node.path("title").asText(""),
                    node.path("summary").asText(""),
                    node.path("url").asText(""),
                    tags,
                    published,
                    sentiment));
        }
        return out;
    }

    /** Daily 10Y and 2Y from TREASURY_YIELD. Two requests. FRED is not used here. */
    public String yieldLine(LocalDate through) throws Exception {
        if (!settings.alphaVantageReady()) throw new IllegalStateException("未配置 ALPHAVANTAGE_API_KEY");
        double[] ten = treasury("10year", through);
        double[] two = treasury("2year", through);
        return Yields.line(box(ten, 0), box(ten, 1), box(two, 0), box(two, 1));
    }

    private double[] treasury(String maturity, LocalDate through) throws Exception {
        URI uri = URI.create("https://www.alphavantage.co/query?function=TREASURY_YIELD&interval=daily&maturity="
                + maturity
                + "&apikey=" + URLEncoder.encode(settings.alphaVantageKey(), StandardCharsets.UTF_8));
        JsonNode root = json.readTree(http.get(uri, java.util.Map.of()));
        if (root.has("Note") || root.has("Information") || root.has("Error Message")) {
            String note = root.path("Note").asText(root.path("Information").asText(root.path("Error Message").asText("")));
            failures.add(uri, note.toLowerCase(Locale.ROOT).contains("premium") ? "免费档不提供" : "拒绝了这次请求");
            throw new IllegalStateException(note.isBlank() ? "Alpha Vantage 没有返回国债收益率" : note);
        }
        return lastTwo(root, through);
    }

    static double[] lastTwo(JsonNode root, LocalDate through) {
        record Point(LocalDate date, double value) {}
        List<Point> points = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            String rawDate = node.path("date").asText("");
            String rawValue = node.path("value").asText("");
            if (rawDate.isBlank() || rawValue.isBlank() || ".".equals(rawValue)) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(rawDate);
            } catch (Exception ignored) {
                continue;
            }
            if (date.isAfter(through)) continue;
            points.add(new Point(date, Double.parseDouble(rawValue)));
        }
        points.sort(Comparator.comparing(Point::date).reversed());
        double[] out = {Double.NaN, Double.NaN};
        for (int i = 0; i < points.size() && i < 2; i++) out[i] = points.get(i).value();
        return out;
    }

    private static Double box(double[] values, int index) {
        return Double.isNaN(values[index]) ? null : values[index];
    }

    private static Instant parseTime(String raw) {
        if (raw == null || raw.length() < 15) return Instant.EPOCH;
        LocalDateTime local = LocalDateTime.parse(raw.substring(0, 15), PUBLISHED);
        return local.atZone(NEW_YORK).toInstant();
    }
}
