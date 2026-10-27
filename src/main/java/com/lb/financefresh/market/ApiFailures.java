package com.lb.financefresh.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Failed outbound calls for the current brief, plus a retained log for the 请求日志 page. */
@Component
public class ApiFailures {

    private static final int KEEP = 200;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> SECRET_QUERY = Set.of(
            "token", "apikey", "api_key", "access_token", "secret", "sign");

    private final FreshSettings settings;
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();
    private final List<FailedCall> failures = new ArrayList<>();
    private final List<FailedCall> retained;

    @Autowired
    public ApiFailures(FreshSettings settings) {
        this(settings, Path.of("data", "request-log.json"));
    }

    ApiFailures(FreshSettings settings, Path file) {
        this.settings = settings;
        this.file = file;
        this.retained = load();
    }

    public void clear() {
        failures.clear();
    }

    public void add(URI uri, String detail) {
        String at = CLOCK.format(ZonedDateTime.now(ZoneId.of(settings.zone())));
        String api = apiName(uri);
        if (detail != null && !detail.isBlank()) api = api + " · " + detail;
        keep(new FailedCall(at, api, address(uri)));
    }

    /** Page/API errors that are not tied to a single outbound URL. */
    public void addNote(String api, String address) {
        String at = CLOCK.format(ZonedDateTime.now(ZoneId.of(settings.zone())));
        keep(new FailedCall(at, api == null || api.isBlank() ? "请求失败" : api, address == null ? "" : address));
    }

    /** Soft gaps from this run, shown in 请求日志 instead of the brief body. */
    public void addGaps(List<String> gaps) {
        if (gaps == null || gaps.isEmpty()) return;
        List<String> unique = gaps.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
        if (unique.isEmpty()) return;
        String at = CLOCK.format(ZonedDateTime.now(ZoneId.of(settings.zone())));
        keep(new FailedCall(at, "本次缺口：" + String.join("；", unique), "本轮采集说明"));
    }

    public List<FailedCall> list() {
        return List.copyOf(failures);
    }

    /** Last failures kept on disk so OrbStack restarts still show them in 请求日志. */
    public List<FailedCall> recent() {
        synchronized (this) {
            List<FailedCall> out = new ArrayList<>(retained);
            java.util.Collections.reverse(out);
            return out;
        }
    }

    private void keep(FailedCall call) {
        failures.add(call);
        synchronized (this) {
            retained.add(call);
            while (retained.size() > KEEP) retained.removeFirst();
            save();
        }
    }

    private List<FailedCall> load() {
        if (file == null || !Files.isRegularFile(file)) return new ArrayList<>();
        try {
            List<FailedCall> saved = json.readValue(file.toFile(), new TypeReference<>() {});
            return saved == null ? new ArrayList<>() : new ArrayList<>(saved);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private void save() {
        if (file == null) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), retained);
        } catch (Exception ignored) {
        }
    }

    public String format() {
        if (failures.isEmpty()) return "";
        StringBuilder out = new StringBuilder("失败的接口\n");
        for (FailedCall call : failures) {
            out.append(call.at()).append("  ").append(call.api()).append("\n");
            out.append(call.address()).append("\n");
        }
        return out.toString().strip() + "\n";
    }

    static String apiName(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host.contains("finnhub")) return "Finnhub " + path;
        if (host.contains("alphavantage")) return "Alpha Vantage " + query(uri, "function");
        if (host.contains("stlouisfed")) return "FRED " + query(uri, "series_id");
        if (host.contains("alpaca") && path.contains("/news")) return "Alpaca /v1beta1/news";
        if (host.contains("alpaca")) return "Alpaca " + path;
        if (host.contains("gtimg") || host.contains("finance.qq")) return "腾讯行情";
        if (host.contains("deepseek")) return "DeepSeek /chat/completions";
        if (host.contains("dingtalk")) return "钉钉机器人";
        if (host.contains("pushplus")) return "PushPlus";
        if (host.contains("qyapi.weixin")) return "企业微信机器人";
        return host + path;
    }

    static String address(URI uri) {
        String base = uri.getScheme() + "://" + uri.getHost() + (uri.getRawPath() == null ? "" : uri.getRawPath());
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return base;
        List<String> kept = new ArrayList<>();
        for (String part : query.split("&")) {
            String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (SECRET_QUERY.contains(key)) continue;
            kept.add(part);
        }
        return kept.isEmpty() ? base : base + "?" + String.join("&", kept);
    }

    private static String query(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) return uri.getPath() == null ? "" : uri.getPath();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equalsIgnoreCase(name) && pair.length == 2 && !pair[1].isBlank()) return pair[1];
        }
        return uri.getPath() == null ? "" : uri.getPath();
    }

    public record FailedCall(String at, String api, String address) {
    }
}
