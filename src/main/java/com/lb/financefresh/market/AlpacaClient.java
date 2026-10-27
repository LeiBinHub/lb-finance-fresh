package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.NewsItem;
import com.lb.financefresh.model.Quote;
import com.lb.financefresh.model.WatchLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Alpaca market data. Key id and secret go in request headers to data.alpaca.markets.
 * This is not Alpha Vantage, and it is not the Broker OAuth client-credentials flow.
 * Regular / extended hours come from {@code ALPACA_FEED} (default iex). Overnight
 * (20:00–04:00 ET) needs a second call with {@code feed=overnight}.
 */
@Component
public class AlpacaClient {

    private static final Logger log = LoggerFactory.getLogger(AlpacaClient.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;

    public AlpacaClient(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    public List<NewsItem> news(List<String> symbols, Instant since, Instant until) throws Exception {
        if (!settings.alpacaReady() || symbols.isEmpty()) return List.of();
        URI uri = URI.create("https://data.alpaca.markets/v1beta1/news?sort=desc&limit=50&symbols="
                + symbols(symbols)
                + "&start=" + URLEncoder.encode(since.toString(), StandardCharsets.UTF_8)
                + "&end=" + URLEncoder.encode(until.toString(), StandardCharsets.UTF_8));
        JsonNode root = json.readTree(http.get(uri, auth()));
        return newsFrom(root, since, until);
    }

    List<NewsItem> newsFrom(JsonNode root, Instant since, Instant until) {
        List<NewsItem> out = new ArrayList<>();
        for (JsonNode node : root.path("news")) {
            List<String> tags = new ArrayList<>();
            for (JsonNode symbol : node.path("symbols")) tags.add(symbol.asText("").toUpperCase());
            Instant published = parseTime(node.path("created_at").asText(""));
            if (published.isBefore(since) || !published.isBefore(until)) continue;
            out.add(new NewsItem(
                    "alpaca",
                    node.path("headline").asText(""),
                    node.path("summary").asText(""),
                    node.path("url").asText(""),
                    tags,
                    published,
                    0));
        }
        return out;
    }

    public List<Quote> snapshots(List<String> symbols, BriefKind kind) throws Exception {
        List<String> tradeable = symbols.stream()
                .filter(symbol -> !WatchLabels.indexOnly(symbol))
                .toList();
        if (!settings.alpacaReady() || tradeable.isEmpty()) return List.of();
        JsonNode iex = fetchOrEmpty(tradeable, settings.alpacaFeed(), "Alpaca " + settings.alpacaFeed());
        JsonNode overnight = kind == BriefKind.RECAP
                ? json.createObjectNode()
                : fetchOrEmpty(tradeable, "overnight", "Alpaca overnight");
        return quotesFrom(iex, overnight, tradeable, kind);
    }

    private JsonNode fetchOrEmpty(List<String> symbols, String feed, String label) {
        try {
            return fetchSnapshots(symbols, feed);
        } catch (Exception e) {
            log.warn("{} snapshots failed: {}", label, e.toString());
            return json.createObjectNode();
        }
    }

    private JsonNode fetchSnapshots(List<String> symbols, String feed) throws Exception {
        URI uri = URI.create("https://data.alpaca.markets/v2/stocks/snapshots?feed="
                + URLEncoder.encode(feed, StandardCharsets.UTF_8)
                + "&symbols=" + symbols(symbols));
        return json.readTree(http.get(uri, auth()));
    }

    /**
     * Regular/extended hours (iex/sip) plus overnight (BOATS). Each symbol keeps the snapshot
     * whose latest print — trade or indicative quote — is newer.
     */
    JsonNode splice(JsonNode iex, JsonNode overnight, List<String> symbols) {
        ObjectNode out = json.createObjectNode();
        ObjectNode dayRoot = unwrapOrEmpty(iex);
        ObjectNode nightRoot = unwrapOrEmpty(overnight);
        for (String symbol : symbols) {
            JsonNode day = dayRoot.path(symbol);
            JsonNode night = nightRoot.path(symbol);
            boolean dayOk = !day.isMissingNode() && !day.isNull();
            boolean nightOk = !night.isMissingNode() && !night.isNull();
            if (dayOk && nightOk) {
                Instant dayAt = latestPrint(day).at();
                Instant nightAt = latestPrint(night).at();
                out.set(symbol, nightAt.isAfter(dayAt) ? night.deepCopy() : day.deepCopy());
            } else if (nightOk) {
                out.set(symbol, night.deepCopy());
            } else if (dayOk) {
                out.set(symbol, day.deepCopy());
            }
        }
        return out;
    }

    JsonNode mergeFresherTrades(JsonNode primary, JsonNode overnight, List<String> symbols) {
        return splice(primary, overnight, symbols);
    }

    private ObjectNode unwrapOrEmpty(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) return json.createObjectNode();
        if (root instanceof ObjectNode object && object.has("snapshots") && object.get("snapshots").isObject()) {
            return (ObjectNode) object.get("snapshots");
        }
        if (root instanceof ObjectNode object) return object;
        return json.createObjectNode();
    }

    List<Quote> quotesFrom(JsonNode root, List<String> symbols, BriefKind kind) {
        return quotesFrom(root, json.createObjectNode(), symbols, kind);
    }

    /**
     * IEX/SIP and overnight snapshots stay separate so 盘后 is not overwritten by 夜盘.
     * Each leg is that session's own return: 盘中 vs 前收, 盘前 vs 最近收盘, 盘后/夜盘 vs 当日收盘.
     */
    List<Quote> quotesFrom(JsonNode iex, JsonNode overnight, List<String> symbols, BriefKind kind) {
        ObjectNode dayRoot = unwrapOrEmpty(iex);
        ObjectNode nightRoot = unwrapOrEmpty(overnight);
        List<Quote> out = new ArrayList<>();
        for (String symbol : symbols) {
            JsonNode day = dayRoot.path(symbol);
            JsonNode night = nightRoot.path(symbol);
            boolean dayOk = present(day);
            boolean nightOk = present(night);
            if (!dayOk && !nightOk) continue;
            JsonNode bars = dayOk ? day : night;
            double prev = bars.path("prevDailyBar").path("c").asDouble(0);
            double close = bars.path("dailyBar").path("c").asDouble(0);
            Print iexPrint = dayOk ? latestTrade(day) : Print.NONE;
            Print nightPrint = nightOk ? latestPrint(night) : Print.NONE;

            Quote.SessionLeg regular = leg(close, pct(close, prev));
            Quote.SessionLeg premarket = null;
            Quote.SessionLeg afterHours = null;
            Quote.SessionLeg overnightLeg = null;
            SessionFill fill = new SessionFill(regular, premarket, afterHours, overnightLeg);
            fill = apply(fill, iexPrint, prev, close);
            fill = apply(fill, nightPrint, prev, close);

            Print latest = newer(iexPrint, nightPrint);
            double price;
            String session;
            if (kind == BriefKind.RECAP && close > 0) {
                price = close;
                session = "收盘";
            } else if (latest.present()) {
                price = latest.price();
                session = sessionOf(latest.at());
            } else if (close > 0) {
                price = close;
                session = "收盘";
            } else {
                continue;
            }
            if (price <= 0) continue;
            out.add(new Quote(symbol, price, session, fill.regular, fill.premarket, fill.afterHours, fill.overnight));
        }
        return out;
    }

    private static SessionFill apply(SessionFill fill, Print print, double prev, double close) {
        if (!print.present()) return fill;
        return switch (sessionOf(print.at())) {
            case "盘前" -> new SessionFill(fill.regular, leg(print.price(), pct(print.price(), close > 0 ? close : prev)),
                    fill.afterHours, fill.overnight);
            case "盘后" -> new SessionFill(fill.regular, fill.premarket, leg(print.price(), pct(print.price(), close)),
                    fill.overnight);
            case "夜盘" -> new SessionFill(fill.regular, fill.premarket, fill.afterHours,
                    leg(print.price(), pct(print.price(), close)));
            case "盘中" -> new SessionFill(leg(print.price(), pct(print.price(), prev)), fill.premarket,
                    fill.afterHours, fill.overnight);
            default -> fill;
        };
    }

    private record SessionFill(
            Quote.SessionLeg regular,
            Quote.SessionLeg premarket,
            Quote.SessionLeg afterHours,
            Quote.SessionLeg overnight
    ) {}

    static Quote.SessionLeg leg(double price, Double changePercent) {
        if (price <= 0 || changePercent == null) return null;
        return new Quote.SessionLeg(price, changePercent);
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull();
    }

    /** Percent change versus {@code base}. Null when either side is missing. */
    static Double pct(double now, double base) {
        if (base <= 0 || now <= 0) return null;
        return (now - base) / base * 100.0;
    }

    /** Last sale. Never a quote, never a percent applied to another session. */
    static Print latestTrade(JsonNode node) {
        Instant tradeAt = parseTime(node.path("latestTrade").path("t").asText(""));
        double tradePrice = node.path("latestTrade").path("p").asDouble(0);
        if (tradePrice > 0 && tradeAt.isAfter(Instant.EPOCH)) return new Print(tradeAt, tradePrice);
        return Print.NONE;
    }

    /** Overnight free tier: quotes are live, trades lag about 15 minutes. Use whichever print is newer. */
    static Print latestPrint(JsonNode node) {
        Print trade = latestTrade(node);
        Instant quoteAt = parseTime(node.path("latestQuote").path("t").asText(""));
        double bid = node.path("latestQuote").path("bp").asDouble(0);
        double ask = node.path("latestQuote").path("ap").asDouble(0);
        if (!(bid > 0 && ask > 0 && quoteAt.isAfter(Instant.EPOCH))) return trade;
        return newer(trade, new Print(quoteAt, (bid + ask) / 2.0));
    }

    static Print newer(Print left, Print right) {
        if (right == null || !right.present()) return left == null ? Print.NONE : left;
        if (left == null || !left.present()) return right;
        return right.at().isAfter(left.at()) ? right : left;
    }

    record Print(Instant at, double price) {
        static final Print NONE = new Print(Instant.EPOCH, 0);

        boolean present() {
            return price > 0 && at != null && at.isAfter(Instant.EPOCH);
        }
    }

    /**
     * US equity sessions in America/New_York:
     * overnight Sun–Thu 20:00–24:00 and Mon–Fri 00:00–04:00,
     * pre-market 04:00–09:30, regular 09:30–16:00, after-hours 16:00–20:00.
     */
    static String sessionOf(Instant tradedAt) {
        if (tradedAt == null || !tradedAt.isAfter(Instant.EPOCH)) return "成交时间不明";
        ZonedDateTime ny = tradedAt.atZone(NEW_YORK);
        if (inOvernightWindow(tradedAt)) return "夜盘";
        DayOfWeek day = ny.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return "上一交易日收盘";
        LocalTime time = ny.toLocalTime();
        if (!time.isBefore(LocalTime.of(4, 0)) && time.isBefore(LocalTime.of(9, 30))) return "盘前";
        if (!time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(16, 0))) return "盘中";
        if (!time.isBefore(LocalTime.of(16, 0)) && time.isBefore(LocalTime.of(20, 0))) return "盘后";
        return "上一时段收盘";
    }

    /** True during the BOATS overnight window: Sun 20:00 through Fri 04:00 ET. */
    static boolean inOvernightWindow(Instant when) {
        if (when == null) return false;
        ZonedDateTime ny = when.atZone(NEW_YORK);
        DayOfWeek day = ny.getDayOfWeek();
        LocalTime time = ny.toLocalTime();
        if (!time.isBefore(LocalTime.of(20, 0))) {
            return day == DayOfWeek.SUNDAY || day.getValue() <= 4;
        }
        if (time.isBefore(LocalTime.of(4, 0))) {
            return day.getValue() >= 1 && day.getValue() <= 5;
        }
        return false;
    }

    private static String symbols(List<String> symbols) {
        return symbols.stream()
                .map(symbol -> URLEncoder.encode(symbol, StandardCharsets.UTF_8))
                .collect(Collectors.joining(","));
    }

    private static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    private Map<String, String> auth() {
        return Map.of(
                "APCA-API-KEY-ID", settings.alpacaKey(),
                "APCA-API-SECRET-KEY", settings.alpacaSecret());
    }
}
