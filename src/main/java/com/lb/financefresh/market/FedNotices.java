package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Official St. Louis Fed and Board RSS. New articles are translated once and kept in SQLite. */
@Component
public class FedNotices {

    static final int PER_SOURCE = 6;
    static final int EXCERPT = 1000;
    private static final Duration CACHE = Duration.ofHours(6);
    private static final Map<String, String> HEADERS = Map.of("User-Agent", "curl/8.7.1");
    private static final DateTimeFormatter RFC = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final Pattern HEX = Pattern.compile("&#x([0-9a-fA-F]+);");
    private static final Pattern DECIMAL = Pattern.compile("&#(\\d+);");

    private static final List<Feed> FEEDS = List.of(
            new Feed("FRED Blog", "https://fred.stlouisfed.org/blog/rss"),
            new Feed("圣路易斯联储研究博客", "https://www.stlouisfed.org/on-the-economy/rss"),
            new Feed("货币政策声明", "https://www.federalreserve.gov/feeds/press_monetary.xml"),
            new Feed("官员讲话", "https://www.federalreserve.gov/feeds/speeches.xml"));

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;
    private final FedArticleStore store;
    private volatile Cached cached;

    @Autowired
    public FedNotices(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this(settings, http, json, new FedArticleStore(Path.of("data", "fed-articles.sqlite")));
    }

    FedNotices(FreshSettings settings, ExternalHttp http, ObjectMapper json, FedArticleStore store) {
        this.settings = settings;
        this.http = http;
        this.json = json;
        this.store = store;
    }

    public List<Section> latest() {
        Cached current = cached;
        long now = System.currentTimeMillis();
        if (current != null && now - current.at() < CACHE.toMillis()) return current.sections();
        synchronized (this) {
            current = cached;
            if (current != null && now - current.at() < CACHE.toMillis()) return current.sections();
            List<Section> sections = pull();
            boolean any = sections.stream().anyMatch(section -> !section.items().isEmpty());
            if (any) cached = new Cached(System.currentTimeMillis(), sections);
            return sections;
        }
    }

    private List<Section> pull() {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Section>> tasks = FEEDS.stream()
                    .map(feed -> pool.submit(() -> one(feed)))
                    .toList();
            List<Section> sections = new ArrayList<>();
            for (int i = 0; i < FEEDS.size(); i++) {
                try {
                    sections.add(tasks.get(i).get());
                } catch (Exception e) {
                    sections.add(new Section(FEEDS.get(i).name(), List.of(), "这次没有取到"));
                }
            }
            return sections;
        }
    }

    private Section one(Feed feed) {
        try {
            String xml = http.get(URI.create(feed.url()), HEADERS, false);
            List<Parsed> parsed = items(xml, PER_SOURCE);
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Notice>> tasks = parsed.stream()
                        .map(item -> pool.submit(() -> notice(feed.name(), item)))
                        .toList();
                List<Notice> notices = new ArrayList<>();
                for (Future<Notice> task : tasks) notices.add(task.get());
                return new Section(feed.name(), notices, "");
            }
        } catch (Exception e) {
            return new Section(feed.name(), List.of(), "这次没有取到");
        }
    }

    private Notice notice(String source, Parsed item) {
        try {
            FedArticleStore.Saved saved = store.find(item.url());
            if (saved != null && saved.ready()) {
                return new Notice(source, item.title(), item.url(), item.published(),
                        saved.english(), saved.chinese(), saved.analysis());
            }
            String english = englishOf(item);
            Brief brief = translate(item.title(), english);
            store.save(new FedArticleStore.Saved(
                    source, item.title(), item.url(), item.published(), english, brief.chinese(), brief.analysis()));
            return new Notice(source, item.title(), item.url(), item.published(),
                    english, brief.chinese(), brief.analysis());
        } catch (Exception e) {
            String english = englishOf(item);
            return new Notice(source, item.title(), item.url(), item.published(), english, "", "");
        }
    }

    private String englishOf(Parsed item) {
        if (!item.url().isBlank()) {
            try {
                String html = http.get(URI.create(item.url()), HEADERS, false);
                return articleExcerpt(html, item.description());
            } catch (Exception ignored) {
                return excerpt(item.description());
            }
        }
        return excerpt(item.description());
    }

    private Brief translate(String title, String english) throws Exception {
        if (!settings.deepseekReady()) throw new IllegalStateException("还没有填写 DEEPSEEK_API_KEY");
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekModel());
        payload.put("temperature", 0.2);
        payload.put("max_tokens", 4096);
        payload.putObject("response_format").put("type", "json_object");
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", """
                你是买方宏观研究员。用户给你一篇美联储或圣路易斯联储官方文章的英文节选。
                只返回一个 JSON 对象，键为 chinese 和 analysis。
                chinese 是这段英文的简体中文对照翻译，保留专有名词和缩写。
                analysis 是面向交易者的专业解读，3 到 6 句，点明对利率、流动性或风险资产的含义，不要复述全文。
                """);
        ObjectNode user = json.createObjectNode();
        user.put("title", title == null ? "" : title);
        user.put("english", english == null ? "" : english);
        messages.addObject().put("role", "user").put("content", json.writeValueAsString(user));
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
        return briefOf(json.readTree(content));
    }

    static Brief briefOf(JsonNode root) {
        String chinese = root.path("chinese").asText("").strip();
        if (chinese.isBlank()) chinese = root.path("zh").asText("").strip();
        String analysis = root.path("analysis").asText("").strip();
        if (analysis.isBlank()) analysis = root.path("解读").asText("").strip();
        return new Brief(chinese, analysis);
    }

    static List<Parsed> items(String xml, int limit) throws Exception {
        String body = xml == null ? "" : xml;
        if (body.startsWith("\uFEFF")) body = body.substring(1);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        NodeList nodes = document.getElementsByTagName("item");
        List<Parsed> items = new ArrayList<>();
        for (int i = 0; i < nodes.getLength() && items.size() < limit; i++) {
            if (!(nodes.item(i) instanceof Element item)) continue;
            String title = child(item, "title");
            String url = child(item, "link");
            if (!url.startsWith("http")) url = child(item, "guid");
            if (!url.startsWith("http") || title.isBlank()) continue;
            String description = child(item, "encoded");
            if (description.isBlank()) description = child(item, "description");
            items.add(new Parsed(title, url, published(child(item, "pubDate")), description));
        }
        return items;
    }

    static String articleExcerpt(String html, String fallback) {
        int start = marker(html);
        if (start < 0) return excerpt(fallback);
        int tag = html.lastIndexOf('<', start);
        if (tag >= 0) start = tag;
        int end = html.length();
        for (String token : List.of("id=\"footer\"", "<footer", "class=\"footer")) {
            int found = indexOf(html, token, start + token.length());
            if (found > start) end = Math.min(end, found);
        }
        if (end - start > 80_000) end = start + 80_000;
        String text = excerpt(html.substring(start, end));
        if (text.length() < 80) return excerpt(fallback);
        return text;
    }

    static String excerpt(String raw) {
        String text = plain(raw == null ? "" : raw);
        if (text.length() <= EXCERPT) return text;
        return text.substring(0, EXCERPT);
    }

    private static int marker(String html) {
        for (String token : List.of("id=\"article\"", "class=\"post-content", "class=\"field-content")) {
            int from = 0;
            while (from < html.length()) {
                int at = indexOf(html, token, from);
                if (at < 0) break;
                int sampleEnd = Math.min(html.length(), at + 4000);
                if (plain(html.substring(at, sampleEnd)).length() >= 80) return at;
                from = at + token.length();
            }
        }
        return -1;
    }

    static String plain(String html) {
        String text = html.replaceAll("(?is)<(script|style|noscript|iframe|svg)\\b.*?>.*?</\\1>", " ");
        text = text.replaceAll("(?is)<[^>]+>", " ");
        text = decode(text);
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String decode(String text) {
        text = replace(HEX, text, 16);
        text = replace(DECIMAL, text, 10);
        return text.replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String replace(Pattern pattern, String text, int radix) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int code = Integer.parseInt(matcher.group(1), radix);
            String replacement = Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : "";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static int indexOf(String text, String token, int from) {
        return text.toLowerCase().indexOf(token.toLowerCase(), Math.max(0, from));
    }

    private static String published(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return ZonedDateTime.parse(raw, RFC).toLocalDate().toString();
        } catch (Exception e) {
            return raw.strip();
        }
    }

    private static String child(Element item, String local) {
        NodeList nodes = item.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element child)) continue;
            String name = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if (local.equals(name)) return child.getTextContent().replaceAll("\\s+", " ").strip();
        }
        return "";
    }

    private record Cached(long at, List<Section> sections) {
    }

    private record Feed(String name, String url) {
    }

    record Parsed(String title, String url, String published, String description) {
    }

    record Brief(String chinese, String analysis) {
    }

    public record Notice(String source, String title, String url, String published,
                         String excerpt, String chinese, String analysis) {
    }

    public record Section(String name, List<Notice> items, String error) {
    }
}
