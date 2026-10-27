package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class FredClient {

    /** FRED's CDN drops the app's own user agent and never answers. curl's name gets a response. */
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "curl/8.7.1");

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;

    public FredClient(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    public String yieldLine(LocalDate through) throws Exception {
        if (!settings.fredReady()) return "10Y 本次没有取到，2Y 本次没有取到。";
        double[] ten = lastTwo("DGS10", through);
        double[] two = lastTwo("DGS2", through);
        return Yields.line(box(ten, 0), box(ten, 1), box(two, 0), box(two, 1));
    }

    /** Newest observation first. Dates after {@code through} are dropped. Missing "." values are skipped. */
    public List<Point> points(String series, LocalDate through, int limit) throws Exception {
        URI uri = URI.create("https://api.stlouisfed.org/fred/series/observations?series_id=" + series
                + "&api_key=" + settings.fredKey()
                + "&file_type=json&sort_order=desc&limit=" + limit
                + "&observation_end=" + through);
        JsonNode root = parse(http.get(uri, HEADERS));
        List<Point> out = new ArrayList<>();
        for (JsonNode node : root.path("observations")) {
            String raw = node.path("value").asText("");
            if (raw.isBlank() || ".".equals(raw)) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(node.path("date").asText(""));
            } catch (Exception ignored) {
                continue;
            }
            if (date.isAfter(through)) continue;
            out.add(new Point(date, Double.parseDouble(raw)));
        }
        out.sort(Comparator.comparing(Point::date).reversed());
        return out;
    }

    private double[] lastTwo(String series, LocalDate through) throws Exception {
        List<Point> points = points(series, through, 8);
        double[] out = {Double.NaN, Double.NaN};
        for (int i = 0; i < points.size() && i < 2; i++) out[i] = points.get(i).value();
        return out;
    }

    private static Double box(double[] values, int index) {
        return Double.isNaN(values[index]) ? null : values[index];
    }

    /** Release names on or after {@code from}, in date order. The feed's first page covers the coming weeks. */
    public List<Release> releases(LocalDate from) throws Exception {
        if (!settings.fredReady()) throw new IllegalStateException("还没有填写 FRED_API_KEY");
        URI uri = URI.create("https://api.stlouisfed.org/fred/releases/dates?api_key=" + settings.fredKey()
                + "&file_type=json&include_release_dates_with_no_data=true"
                + "&realtime_start=" + from
                + "&order_by=release_date&sort_order=asc&limit=1000");
        return releases(parse(http.get(uri, HEADERS, false)), from);
    }

    static JsonNode parse(String body) throws Exception {
        String text = body == null ? "" : body.strip();
        if (text.isBlank()) throw new IllegalStateException("FRED 返回空内容");
        if (text.startsWith("<") || text.toLowerCase().contains("site maintenance")) {
            throw new IllegalStateException("FRED 正在维护，返回的是网页而不是 JSON，稍后再试");
        }
        if (!text.startsWith("{") && !text.startsWith("[")) {
            throw new IllegalStateException("FRED 返回的不是 JSON");
        }
        return new ObjectMapper().readTree(text);
    }

    static List<Release> releases(JsonNode root, LocalDate from) {
        List<Release> out = new ArrayList<>();
        for (JsonNode node : root.path("release_dates")) {
            String name = node.path("release_name").asText("").strip();
            LocalDate date;
            try {
                date = LocalDate.parse(node.path("date").asText(""));
            } catch (Exception ignored) {
                continue;
            }
            if (name.isBlank() || date.isBefore(from)) continue;
            out.add(new Release(date.toString(), name));
        }
        out.sort(Comparator.comparing(Release::date).thenComparing(Release::name));
        return out;
    }

    public record Point(LocalDate date, double value) {}

    public record Release(String date, String name) {}
}
