package com.lb.financefresh.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public channels with a web preview are polled at {@code t.me/s/{handle}}.
 * Channels that hide that page are filled by the Telethon user session (the phone app login
 * is a different session; api_id/hash still need a one-time Telethon login).
 */
@Component
public class TreeNews {

    static final int KEEP_MONTHS = 3;
    static final List<Feed> FEEDS = List.of(
            new Feed("TreeNewsFeed", "Tree News", "宏观与加密快讯", true),
            new Feed("WalterBloomberg", "Walter Bloomberg", "彭博快讯", true),
            new Feed("jin10light", "Jin10", "金十数据", true),
            new Feed("unusual_whales_TG1", "Unusual Whales", "异常期权与资金流", false),
            new Feed("forexlive", "ForexLive", "外汇与宏观直播", true, "investinglivenews"));
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern POST = Pattern.compile("data-post=\"([^\"]+)\"");
    private static final Pattern TIME = Pattern.compile("<time datetime=\"([^\"]+)\"");
    private static final Pattern TEXT = Pattern.compile(
            "class=\"tgme_widget_message_text[^\"]*\"[^>]*>([\\s\\S]*?)</div>");
    private static final Pattern TAG = Pattern.compile("(?i)<br\\s*/?>|</p>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRIP = Pattern.compile("<[^>]+>");
    private static final Pattern HEX = Pattern.compile("&#x([0-9a-fA-F]+);");
    private static final Pattern DECIMAL = Pattern.compile("&#(\\d+);");
    private static final Pattern JUNK = Pattern.compile(
            "(?i)(?:join\\s+us|\\bads?\\b|\\bpromo(?:ted|tion)?s?\\b|\\bsponsor(?:ed|s|ship)?\\b|launch\\s+app"
                    + "|t\\.me/\\+|t\\.me/joinchat/?|t\\.me/boost"
                    + "|discord\\.gg/"
                    + "|(?:bit\\.ly|tinyurl\\.com|t\\.co)/"
                    + "|\\baffiliate\\b|\\breferral\\b"
                    + "|[?&](?:ref|start)="
                    + "|\\bhello\\b"
                    + "|\\bvip\\b"
                    + "|\\bpremium product\\b"
                    + "|\\bpremium (?:group|channel|signal|signals|subscription|plan|room|feed)\\b"
                    + "|delayed news feed"
                    + "|check our premium"
                    + "|if you are new here"
                    + "|i am new here"
                    + "|how can i join"
                    + "|how can i invest"
                    + "|send in your request"
                    + "|invest\\s*&\\s*earn"
                    + "|congratulations on your (?:profit|gain)"
                    + "|satisfied investor"
                    + "|\\btrade result:"
                    + "|(?:^|\\n)\\s*(?:hi|hey|dear)\\s+(?:everyone|traders?|friends|guys)\\b"
                    + "|加微信|欢迎加群|私聊领取)");
    private static final Pattern DELAYED_TAIL = Pattern.compile(
            "(?is)\\s*(?:this is a delayed news feed\\.|please check our premium product).*$");
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "curl/8.7.1");

    private static final Logger log = LoggerFactory.getLogger(TreeNews.class);

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();
    private final LinkedHashMap<String, Item> items = new LinkedHashMap<>();
    private final Map<String, String> channelErrors = new ConcurrentHashMap<>();
    private volatile Instant pulledAt = Instant.EPOCH;
    private long lastWarnAt;

    @Autowired
    public TreeNews(FreshSettings settings, ExternalHttp http) {
        this(settings, http, Path.of("data", "tree-news.json"));
    }

    public TreeNews(FreshSettings settings, ExternalHttp http, Path file) {
        this.settings = settings;
        this.http = http;
        this.file = file;
        List<Item> stored = load();
        int skipped = 0;
        boolean cleaned = false;
        for (Item item : stored) {
            String text = clean(item.text());
            if (text.isBlank() || junk(text) || expired(item)) {
                skipped++;
                continue;
            }
            if (!text.equals(item.text())) {
                item = new Item(item.id(), item.channel(), item.at(), item.epoch(), text, item.zh(), item.url());
                cleaned = true;
            }
            items.put(item.id(), item);
        }
        if (skipped > 0 || cleaned || purge() > 0) save();
    }

    public List<Feed> feeds() {
        return FEEDS;
    }

    public synchronized List<Item> latest() {
        return items.values().stream()
                .sorted(Comparator.comparing(Item::epoch).reversed())
                .toList();
    }

    public synchronized List<Item> latest(String handle) {
        if (handle == null || handle.isBlank()) return List.of();
        return items.values().stream()
                .filter(item -> handle.equalsIgnoreCase(item.channel()))
                .sorted(Comparator.comparing(Item::epoch).reversed())
                .toList();
    }

    public synchronized boolean hasChannel(String handle) {
        if (handle == null || handle.isBlank()) return false;
        return items.values().stream().anyMatch(item -> handle.equalsIgnoreCase(item.channel()));
    }

    /** Messages whose timestamp falls on {@code day} in the app timezone. Oldest first. */
    public synchronized List<Item> forDay(LocalDate day) {
        if (day == null) return List.of();
        ZoneId zone = ZoneId.of(settings.zone());
        return items.values().stream()
                .filter(item -> dayOf(item, zone).equals(day))
                .sorted(Comparator.comparingLong(Item::epoch))
                .toList();
    }

    public synchronized List<Item> forDay(String handle, LocalDate day) {
        if (handle == null || handle.isBlank() || day == null) return List.of();
        ZoneId zone = ZoneId.of(settings.zone());
        return items.values().stream()
                .filter(item -> handle.equalsIgnoreCase(item.channel()))
                .filter(item -> dayOf(item, zone).equals(day))
                .sorted(Comparator.comparing(Item::epoch).reversed())
                .toList();
    }

    /** Distinct calendar days that have saved messages, newest first. */
    public synchronized List<String> days() {
        ZoneId zone = ZoneId.of(settings.zone());
        return items.values().stream()
                .map(item -> dayOf(item, zone).toString())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    public String previousDay(LocalDate day) {
        if (day == null) return "";
        String key = day.toString();
        return days().stream().filter(item -> item.compareTo(key) < 0).findFirst().orElse("");
    }

    public String nextDay(LocalDate day) {
        if (day == null) return "";
        String key = day.toString();
        List<String> dates = days();
        String newer = "";
        for (String item : dates) {
            if (item.compareTo(key) > 0) newer = item;
            else break;
        }
        return newer;
    }

    public List<ChannelView> channels() {
        return channels(LocalDate.now(ZoneId.of(settings.zone())));
    }

    public List<ChannelView> channels(LocalDate day) {
        List<ChannelView> out = new ArrayList<>();
        for (Feed feed : FEEDS) {
            out.add(new ChannelView(
                    feed.handle(),
                    feed.title(),
                    feed.titleZh(),
                    channelErrors.getOrDefault(feed.handle(), ""),
                    forDay(feed.handle(), day)));
        }
        return out;
    }

    public static String titleZh(String handle) {
        return feed(handle).titleZh();
    }

    public static Feed feed(String handle) {
        if (handle == null) return new Feed("", "", "", true);
        for (Feed feed : FEEDS) {
            if (feed.handle().equalsIgnoreCase(handle)) return feed;
        }
        return new Feed(handle, handle, handle, true);
    }

    public String pulledAt() {
        if (pulledAt.equals(Instant.EPOCH)) return "";
        return CLOCK.format(pulledAt.atZone(ZoneId.of(settings.zone())));
    }

    public String lastError() {
        List<String> errors = new ArrayList<>();
        for (Feed feed : FEEDS) {
            String error = channelErrors.getOrDefault(feed.handle(), "");
            if (!error.isBlank()) errors.add("@" + feed.handle() + " " + error);
        }
        return String.join("；", errors);
    }

    public boolean enabled() {
        return settings.telegramReady();
    }

    public void pull() {
        if (!enabled()) return;
        ZoneId zone = ZoneId.of(settings.zone());
        boolean added = false;
        synchronized (this) {
            if (purge() > 0) added = true;
        }
        for (Feed feed : FEEDS) {
            if (!feed.webPreview()) continue;
            try {
                String html = http.get(URI.create("https://t.me/s/" + feed.sourceHandle()), HEADERS, false);
                if (html == null || !html.contains("data-post=")) {
                    if (!hasChannel(feed.handle())) {
                        channelErrors.put(feed.handle(), previewClosedHint());
                    }
                    continue;
                }
                List<Item> parsed = parse(html, feed.handle(), zone);
                synchronized (this) {
                    for (Item raw : parsed) {
                        Item item = underFeed(raw, feed);
                        Item previous = items.get(item.id());
                        if (expired(item)) continue;
                        if (previous == null) {
                            items.put(item.id(), item);
                            added = true;
                        } else if (previous.zh().isBlank() && !item.zh().isBlank()) {
                            items.put(item.id(), item);
                            added = true;
                        }
                    }
                    purge();
                }
                channelErrors.remove(feed.handle());
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                channelErrors.put(feed.handle(), message);
                long now = System.currentTimeMillis();
                if (now - lastWarnAt > 300_000) {
                    lastWarnAt = now;
                    log.warn("Telegram poll @{} failed: {}", feed.handle(), message);
                }
            }
        }
        pulledAt = Instant.now();
        if (added) {
            synchronized (this) {
                save();
            }
        }
    }

    public int ingest(List<Flash> flashes) {
        if (flashes == null || flashes.isEmpty()) return 0;
        ZoneId zone = ZoneId.of(settings.zone());
        int added = 0;
        synchronized (this) {
            for (Flash flash : flashes) {
                if (flash == null || flash.channel() == null || flash.channel().isBlank()) continue;
                String handle = canonicalHandle(flash.channel().replace("@", "").strip());
                if (handle.isBlank() || flash.id() <= 0) continue;
                String text = clean(flash.text() == null ? "" : flash.text());
                if (text.isBlank() || junk(text)) continue;
                String id = handle + "/" + flash.id();
                if (items.containsKey(id)) continue;
                long epoch = flash.epoch() > 0 ? flash.epoch() : Instant.now().toEpochMilli();
                if (expired(epoch)) continue;
                Instant at = Instant.ofEpochMilli(epoch);
                Feed feed = feed(handle);
                items.put(id, new Item(
                        id,
                        handle,
                        CLOCK.format(ZonedDateTime.ofInstant(at, zone)),
                        epoch,
                        text,
                        "",
                        "https://t.me/" + feed.sourceHandle() + "/" + flash.id()));
                channelErrors.remove(handle);
                added++;
            }
            int dropped = purge();
            if (added > 0 || dropped > 0) {
                save();
            }
        }
        if (added > 0) pulledAt = Instant.now();
        return added;
    }

    static String previewClosedHint() {
        return "网页公开预览已关。手机 App 已登录、已关注也不够，那是另一套会话。"
                + "要用账号收帖，在本机跑一次 Telethon 登录生成 data/telegram.session。";
    }

    public void fillChinese() {
        if (!enabled() || !settings.deepseekReady()) return;
        List<Item> pending;
        synchronized (this) {
            pending = items.values().stream()
                    .filter(item -> item.zh().isBlank() && english(item.text()))
                    .sorted(Comparator.comparingLong(Item::epoch).reversed())
                    .limit(8)
                    .toList();
        }
        if (pending.isEmpty()) return;
        try {
            Map<String, String> notes = translate(pending);
            synchronized (this) {
                boolean changed = false;
                for (Item item : pending) {
                    String note = notes.getOrDefault(item.id(), "").strip();
                    if (note.isBlank()) continue;
                    Item current = items.get(item.id());
                    if (current == null || !current.zh().isBlank()) continue;
                    items.put(item.id(), new Item(
                            current.id(), current.channel(), current.at(), current.epoch(),
                            current.text(), note, current.url()));
                    changed = true;
                }
                if (changed) save();
            }
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > 300_000) {
                lastWarnAt = now;
                log.warn("Telegram Chinese notes failed: {}", e.toString());
            }
        }
    }

    static List<Item> parse(String html, String channel, ZoneId zone) {
        if (html == null || html.isBlank()) return List.of();
        List<Item> out = new ArrayList<>();
        Matcher posts = POST.matcher(html);
        List<int[]> spans = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (posts.find()) {
            spans.add(new int[] { posts.start(), 0 });
            ids.add(posts.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : html.length();
            String block = html.substring(start, end);
            Matcher time = TIME.matcher(block);
            Matcher text = TEXT.matcher(block);
            if (!time.find() || !text.find()) continue;
            Instant at;
            try {
                at = Instant.parse(time.group(1));
            } catch (Exception ignored) {
                continue;
            }
            String body = clean(plain(text.group(1)));
            if (body.isBlank()) continue;
            if (junk(body) || junk(clean(plain(block)))) continue;
            String id = ids.get(i);
            String handle = handleFromId(id);
            if (handle.isBlank()) handle = channel;
            out.add(new Item(
                    id,
                    handle,
                    CLOCK.format(ZonedDateTime.ofInstant(at, zone)),
                    at.toEpochMilli(),
                    body,
                    "",
                    "https://t.me/" + id));
        }
        return out;
    }

    static String clean(String text) {
        if (text == null) return "";
        return DELAYED_TAIL.matcher(text.strip()).replaceAll("").strip();
    }

    static boolean junk(String text) {
        String body = clean(text);
        return body.isBlank() || JUNK.matcher(body).find();
    }

    private int purge() {
        int dropped = 0;
        var it = items.entrySet().iterator();
        while (it.hasNext()) {
            Item item = it.next().getValue();
            if (junk(item.text()) || expired(item)) {
                it.remove();
                dropped++;
            }
        }
        return dropped;
    }

    private boolean expired(Item item) {
        return item != null && expired(item.epoch());
    }

    private boolean expired(long epoch) {
        if (epoch <= 0) return true;
        ZoneId zone = ZoneId.of(settings.zone());
        LocalDate cutoff = LocalDate.now(zone).minusMonths(KEEP_MONTHS);
        return LocalDate.ofInstant(Instant.ofEpochMilli(epoch), zone).isBefore(cutoff);
    }

    static boolean english(String text) {
        if (text == null || text.isBlank()) return false;
        int latin = 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) latin++;
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) cjk++;
        }
        return latin >= 12 && cjk * 2 < latin;
    }

    static String plain(String html) {
        String text = TAG.matcher(html).replaceAll("\n");
        text = STRIP.matcher(text).replaceAll("");
        text = HEX.matcher(text).replaceAll(match -> Matcher.quoteReplacement(fromCode(Integer.parseInt(match.group(1), 16))));
        text = DECIMAL.matcher(text).replaceAll(match -> Matcher.quoteReplacement(fromCode(Integer.parseInt(match.group(1)))));
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .strip();
    }

    private static String fromCode(int code) {
        return Character.toString(code);
    }

    static String handleFromId(String id) {
        if (id == null) return "";
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : id;
    }

    static String numericId(String id) {
        if (id == null) return "";
        int slash = id.indexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    static String canonicalHandle(String handle) {
        if (handle == null) return "";
        String value = handle.replace("@", "").strip();
        if (value.equalsIgnoreCase("investinglivenews") || value.equalsIgnoreCase("investinglive")) {
            return "forexlive";
        }
        return value;
    }

    static Item underFeed(Item item, Feed feed) {
        if (item == null || feed == null) return item;
        String numeric = numericId(item.id());
        if (numeric.isBlank()) numeric = item.id();
        String id = feed.handle() + "/" + numeric;
        String url = "https://t.me/" + feed.sourceHandle() + "/" + numeric;
        return new Item(id, feed.handle(), item.at(), item.epoch(), item.text(), item.zh(), url);
    }

    private static LocalDate dayOf(Item item, ZoneId zone) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(item.epoch()), zone);
    }

    private Map<String, String> translate(List<Item> pending) throws Exception {
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekModel());
        payload.put("temperature", 0.1);
        payload.put("max_tokens", 4096);
        payload.putObject("response_format").put("type", "json_object");
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", """
                你给财经 Telegram 快讯写简体中文注释，不是扩写。保留数字、股票代码、机构缩写。
                只返回 JSON 对象，键为 items。items 每项含 id 和 chinese。id 必须与输入完全一致。
                """);
        var input = json.createArrayNode();
        for (Item item : pending) {
            ObjectNode row = input.addObject();
            row.put("id", item.id());
            row.put("text", item.text());
        }
        messages.addObject().put("role", "user").put("content", json.writeValueAsString(input));
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
        JsonNode root = json.readTree(content);
        JsonNode rows = root.has("items") ? root.get("items") : root;
        Map<String, String> out = new LinkedHashMap<>();
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                String id = row.path("id").asText("");
                String chinese = row.path("chinese").asText("").strip();
                if (!id.isBlank() && !chinese.isBlank()) out.put(id, chinese);
            }
        }
        return out;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), latest());
        } catch (Exception e) {
            log.warn("Telegram listen save failed: {}", e.toString());
        }
    }

    private List<Item> load() {
        if (!Files.exists(file)) return List.of();
        try {
            return json.readValue(file.toFile(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public record Feed(String handle, String title, String titleZh, boolean webPreview, String source) {
        public Feed(String handle, String title, String titleZh, boolean webPreview) {
            this(handle, title, titleZh, webPreview, handle);
        }

        public String sourceHandle() {
            return source == null || source.isBlank() ? handle : source;
        }
    }

    public record ChannelView(String handle, String title, String titleZh, String error, List<Item> items) {}

    public record Flash(String channel, long id, long epoch, String text) {}

    public record Item(String id, String channel, String at, long epoch, String text, String zh, String url) {
        public Item {
            if (id == null) id = "";
            if (channel == null || channel.isBlank()) channel = handleFromId(id);
            if (at == null) at = "";
            if (text == null) text = "";
            if (zh == null) zh = "";
            if (url == null) url = "";
        }
    }
}
