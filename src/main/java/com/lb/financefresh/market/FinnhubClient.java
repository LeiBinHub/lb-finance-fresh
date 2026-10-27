package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.CalendarEvent;
import com.lb.financefresh.model.NewsItem;
import com.lb.financefresh.model.Quote;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class FinnhubClient {

    private static final String BASE = "https://finnhub.io/api/v1";
    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;
    private volatile boolean sentimentDenied;
    private volatile boolean economicDenied;

    public FinnhubClient(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    public List<NewsItem> companyNews(String symbol, LocalDate from, LocalDate to, Instant since, Instant until) throws Exception {
        if (!settings.finnhubReady()) return List.of();
        String body = http.get(uri("/company-news?symbol=" + enc(symbol) + "&from=" + from + "&to=" + to), java.util.Map.of());
        return newsArray(json.readTree(body), symbol, since, until);
    }

    public List<NewsItem> marketNews(Instant since, Instant until) throws Exception {
        if (!settings.finnhubReady()) return List.of();
        String body = http.get(uri("/news?category=general"), java.util.Map.of());
        return newsArray(json.readTree(body), "", since, until);
    }

    public Quote quote(String symbol, BriefKind kind) throws Exception {
        if (!settings.finnhubReady()) return null;
        JsonNode node = json.readTree(http.get(uri("/quote?symbol=" + enc(symbol)), java.util.Map.of()));
        double price = node.path("c").asDouble(0);
        double change = node.path("dp").asDouble(0);
        if (price <= 0) return null;
        String session = switch (kind) {
            case PREMARKET -> "finnhub 常规报价，不一定是盘前价";
            case NOW -> "最新报价";
            case RECAP -> "常规时段";
        };
        return new Quote(symbol, price, session, new Quote.SessionLeg(price, change), null, null, null);
    }

    public double companySentiment(String symbol) throws Exception {
        if (!settings.finnhubReady() || sentimentDenied) return 0;
        try {
            JsonNode node = json.readTree(http.get(uri("/news-sentiment?symbol=" + enc(symbol)), java.util.Map.of()));
            double score = node.path("companyNewsScore").asDouble(0.5);
            return Math.max(-1, Math.min(1, (score - 0.5) * 2));
        } catch (IOException e) {
            if (!forbidden(e)) throw e;
            sentimentDenied = true;
            return 0;
        }
    }

    public boolean sentimentDenied() {
        return sentimentDenied;
    }

    public List<CalendarEvent> economic(LocalDate from, LocalDate to) throws Exception {
        if (!settings.finnhubReady() || economicDenied) return List.of();
        try {
            JsonNode root = json.readTree(http.get(uri("/calendar/economic?from=" + from + "&to=" + to), java.util.Map.of()));
            List<CalendarEvent> out = new ArrayList<>();
            for (JsonNode node : root.path("economicCalendar")) {
                String country = node.path("country").asText("");
                String impact = node.path("impact").asText("").toLowerCase(Locale.ROOT);
                String event = node.path("event").asText("");
                if (!"US".equalsIgnoreCase(country)) continue;
                if (!"high".equals(impact) && !important(event)) continue;
                String when = node.path("time").asText("") + " 美东";
                String actual = node.path("actual").isNull() ? "" : "实际 " + node.path("actual").asText();
                String estimate = node.path("estimate").isNull() ? "" : "预期 " + node.path("estimate").asText();
                String detail = join(impact, actual, estimate);
                out.add(new CalendarEvent(when, event, detail));
            }
            return out;
        } catch (IOException e) {
            if (!forbidden(e)) throw e;
            economicDenied = true;
            return List.of();
        }
    }

    public boolean economicDenied() {
        return economicDenied;
    }

    public List<CalendarEvent> earnings(List<String> symbols, LocalDate from, LocalDate to) throws Exception {
        if (!settings.finnhubReady()) return List.of();
        JsonNode root = json.readTree(http.get(uri("/calendar/earnings?from=" + from + "&to=" + to), java.util.Map.of()));
        List<CalendarEvent> out = new ArrayList<>();
        for (JsonNode node : root.path("earningsCalendar")) {
            String symbol = node.path("symbol").asText("").toUpperCase(Locale.ROOT);
            if (!symbols.contains(symbol)) continue;
            String hour = switch (node.path("hour").asText("")) {
                case "bmo" -> "盘前";
                case "amc" -> "盘后";
                default -> "时间未标明";
            };
            String estimate = node.path("epsEstimate").isNull() ? "" : "预期 EPS " + node.path("epsEstimate").asText();
            String actual = node.path("epsActual").isNull() ? "" : "实际 EPS " + node.path("epsActual").asText();
            out.add(new CalendarEvent(node.path("date").asText("") + " " + hour, symbol + " 财报", join(estimate, actual)));
        }
        return out;
    }

    private List<NewsItem> newsArray(JsonNode root, String fallbackSymbol, Instant since, Instant until) {
        List<NewsItem> out = new ArrayList<>();
        if (!root.isArray()) return out;
        for (JsonNode node : root) {
            long epoch = node.path("datetime").asLong(0);
            Instant published = epoch > 0 ? Instant.ofEpochSecond(epoch) : Instant.EPOCH;
            if (published.isBefore(since) || !published.isBefore(until)) continue;
            String related = node.path("related").asText("");
            if (related.isBlank()) related = fallbackSymbol;
            List<String> symbols = related.isBlank()
                    ? List.of()
                    : List.of(related.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            out.add(new NewsItem(
                    "finnhub",
                    node.path("headline").asText(""),
                    node.path("summary").asText(""),
                    node.path("url").asText(""),
                    symbols,
                    published,
                    0));
        }
        return out.stream()
                .sorted(Comparator.comparing(NewsItem::publishedAt).reversed())
                .limit(30)
                .toList();
    }

    private URI uri(String pathAndQuery) {
        String joiner = pathAndQuery.contains("?") ? "&" : "?";
        return URI.create(BASE + pathAndQuery + joiner + "token=" + enc(settings.finnhubKey()));
    }

    private static boolean forbidden(IOException error) {
        return error.getMessage() != null && error.getMessage().contains("HTTP 403");
    }

    private static boolean important(String event) {
        String text = event.toLowerCase(Locale.ROOT);
        return text.contains("cpi") || text.contains("ppi") || text.contains("payroll")
                || text.contains("nonfarm") || text.contains("fomc") || text.contains("fed")
                || text.contains("powell") || text.contains("auction") || text.contains("gdp")
                || text.contains("pce") || text.contains("jobless");
    }

    private static String join(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) if (part != null && !part.isBlank()) kept.add(part);
        return String.join("，", kept);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
