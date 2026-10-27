package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** FRED release calendar. The rows live in SQLite; Redis only holds a 64-hour freshness mark. */
@Component
public class ReleaseCalendar {

    static final Duration TTL = Duration.ofHours(64);
    static final int BATCH = 40;
    private static final String KEY = "finance:calendar:fresh";

    private final FreshSettings settings;
    private final FredClient fred;
    private final ExternalHttp http;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final CalendarStore store;
    private final ApiFailures failures;

    @Autowired
    public ReleaseCalendar(FreshSettings settings, FredClient fred, ExternalHttp http, ObjectMapper json,
                           StringRedisTemplate redis, ApiFailures failures) {
        this(settings, fred, http, json, redis, new CalendarStore(Path.of("data", "calendar.sqlite")), failures);
    }

    ReleaseCalendar(FreshSettings settings, FredClient fred, ExternalHttp http, ObjectMapper json,
                    StringRedisTemplate redis, CalendarStore store) {
        this(settings, fred, http, json, redis, store, null);
    }

    ReleaseCalendar(FreshSettings settings, FredClient fred, ExternalHttp http, ObjectMapper json,
                    StringRedisTemplate redis, CalendarStore store, ApiFailures failures) {
        this.settings = settings;
        this.fred = fred;
        this.http = http;
        this.json = json;
        this.redis = redis;
        this.store = store;
        this.failures = failures;
    }

    public List<Item> current() throws Exception {
        if (fresh()) {
            List<Item> saved = store.list();
            if (translated(saved)) return saved;
        }
        synchronized (this) {
            if (fresh()) {
                List<Item> saved = store.list();
                if (translated(saved)) return saved;
            }
            try {
                List<Item> loaded = load();
                if (!translated(loaded)) {
                    throw new IllegalStateException("中文翻译没有完成，这次不写入本地库");
                }
                store.replace(loaded);
                redis.opsForValue().set(KEY, "1", TTL);
                return loaded;
            } catch (Exception e) {
                if (failures != null) {
                    failures.addNote("财经日历 · " + (e.getMessage() == null ? "更新失败" : e.getMessage()),
                            "api.stlouisfed.org/fred/releases/dates");
                }
                List<Item> saved = store.list();
                if (!saved.isEmpty()) return saved;
                throw new IllegalStateException(e.getMessage() == null ? "财经日历这次没有取到" : e.getMessage(), e);
            }
        }
    }

    private boolean fresh() {
        return Boolean.TRUE.equals(redis.hasKey(KEY));
    }

    private List<Item> load() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of(settings.zone()));
        List<FredClient.Release> rows = fred.releases(today);
        Map<String, String> chinese = translate(names(rows));
        List<Item> items = new ArrayList<>();
        for (FredClient.Release row : rows) {
            String translated = chinese.getOrDefault(row.name(), "");
            if (!inChinese(translated) || translated.equals(row.name())) translated = "";
            items.add(new Item(row.date(), row.name(), translated));
        }
        return items;
    }

    private Map<String, String> translate(List<String> names) throws Exception {
        if (names.isEmpty()) return Map.of();
        if (!settings.deepseekReady()) throw new IllegalStateException("还没有填写 DEEPSEEK_API_KEY");
        Map<String, String> chinese = new LinkedHashMap<>();
        for (int offset = 0; offset < names.size(); offset += BATCH) {
            List<String> batch = names.subList(offset, Math.min(names.size(), offset + BATCH));
            chinese.putAll(translations(ask(batch)));
        }
        List<String> missing = names.stream().filter(name -> !chinese.containsKey(name)).toList();
        for (int offset = 0; offset < missing.size(); offset += BATCH) {
            List<String> batch = missing.subList(offset, Math.min(missing.size(), offset + BATCH));
            chinese.putAll(translations(ask(batch)));
        }
        return chinese;
    }

    private JsonNode ask(List<String> names) throws Exception {
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekModel());
        payload.put("temperature", 0.1);
        payload.put("max_tokens", 8192);
        payload.putObject("response_format").put("type", "json_object");
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", """
                你把美国宏观数据发布名称译成简体中文。机构缩写可以保留，例如 FOMC、GDP、CPI。
                只返回一个 JSON 对象，键为 items。items 每项含 name 和 chinese。
                name 必须与输入原文完全一致，不要漏条，不要改写原文。
                """);
        messages.addObject().put("role", "user").put("content", json.writeValueAsString(names));
        String body = http.postJson(
                URI.create(settings.deepseekUrl()),
                json.writeValueAsString(payload),
                Map.of("Authorization", "Bearer " + settings.deepseekKey()));
        String content = json.readTree(body).path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.startsWith("```")) {
            int start = content.indexOf('\n');
            int end = content.lastIndexOf("```");
            if (start >= 0 && end > start) content = content.substring(start + 1, end).trim();
        }
        return json.readTree(content);
    }

    static List<String> names(List<FredClient.Release> rows) {
        Set<String> unique = new LinkedHashSet<>();
        for (FredClient.Release row : rows) unique.add(row.name());
        return List.copyOf(unique);
    }

    static Map<String, String> translations(JsonNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode items = root.path("items");
        if (!items.isArray()) items = root.path("translations");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String name = text(item, "name", "original", "en");
                String chinese = text(item, "chinese", "zh", "translation");
                if (!name.isBlank() && inChinese(chinese) && !chinese.equals(name)) out.put(name, chinese);
            }
        }
        if (root.isObject()) {
            root.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual() || entry.getKey().isBlank()) return;
                if (entry.getKey().equals("items") || entry.getKey().equals("translations")) return;
                String chinese = entry.getValue().asText("").strip();
                if (inChinese(chinese) && !chinese.equals(entry.getKey())) out.put(entry.getKey(), chinese);
            });
        }
        return out;
    }

    static boolean translated(List<Item> items) {
        if (items.isEmpty()) return false;
        long done = items.stream().filter(item -> inChinese(item.chinese()) && !item.chinese().equals(item.name())).count();
        return done * 2 >= items.size();
    }

    static boolean inChinese(String text) {
        if (text == null || text.isBlank()) return false;
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    public record Item(String date, String name, String chinese) {}
}
