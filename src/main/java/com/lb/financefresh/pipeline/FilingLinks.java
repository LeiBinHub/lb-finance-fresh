package com.lb.financefresh.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.market.ExternalHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class FilingLinks {

    private static final Logger log = LoggerFactory.getLogger(FilingLinks.class);
    private static final Pattern TICKER = Pattern.compile("^[A-Za-z0-9.]{1,12}$");
    private static final Duration CACHE = Duration.ofHours(12);
    private static final Map<String, String> SEC_HEADERS = Map.of(
            "User-Agent", "lb-finance-fresh/0.1 (research; github.com/LeiBinHub/lb-finance-fresh)",
            "Accept", "application/json");

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;

    public FilingLinks(FreshSettings settings, ExternalHttp http, ObjectMapper json, StringRedisTemplate redis) {
        this.settings = settings;
        this.http = http;
        this.json = json;
        this.redis = redis;
    }

    public Map<String, Object> lookup(String rawTicker) throws Exception {
        String ticker = normalize(rawTicker);
        String cacheKey = "finance:filing-links:" + LocalDate.now(ZoneId.of(settings.zone())) + ":" + ticker;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = json.readValue(cached, Map.class);
                if (body != null && body.get("groups") instanceof List<?> groups && !groups.isEmpty()) {
                    return body;
                }
            }
        } catch (Exception e) {
            log.warn("filing links cache read failed: {}", e.toString());
        }
        Map<String, Object> body = find(ticker);
        try {
            redis.opsForValue().set(cacheKey, json.writeValueAsString(body), CACHE);
        } catch (Exception e) {
            log.warn("filing links cache write failed: {}", e.toString());
        }
        return body;
    }

    private Map<String, Object> find(String ticker) throws Exception {
        if (!settings.deepseekReady()) {
            throw new IllegalStateException("还没有填写 DEEPSEEK_API_KEY");
        }
        String sec = secContext(ticker);
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekModel());
        payload.put("max_tokens", 2048);
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject().put("role", "user").put("content", userPrompt(ticker, sec));
        String body = http.postJson(
                URI.create(settings.deepseekUrl()),
                json.writeValueAsString(payload),
                Map.of("Authorization", "Bearer " + settings.deepseekKey()),
                Duration.ofSeconds(45));
        String content = json.readTree(body).path("choices").path(0).path("message").path("content").asText("").trim();
        Map<String, Object> parsed = parseModelJson(content, json);
        parsed.put("ticker", ticker);
        return parsed;
    }

    static String systemPrompt() {
        return """
                你帮用户找美股最新一季财报材料的下载/打开地址。只输出 JSON，不要 markdown，不要思考过程。
                JSON 形状：
                {"heading":"公司 季度（截止日期）","groups":[
                  {"title":"10-Q / 业绩稿","hint":"短说明","items":[{"label":"10-Q","url":"https://...","note":"另存为 XXX-10Q.pdf"}]},
                  {"title":"电话会纪要","hint":"网页打开后打印成 PDF","items":[{"label":"Motley Fool 全文（免费）","url":"https://..."}]},
                  {"title":"一致预期表","hint":"没有官方免费单文件时写清怎么抄进 xlsx","items":[{"label":"Yahoo 一致预期","url":"https://..."}]}
                ]}
                规则：
                - 10-Q 和 8-K 业绩稿必须优先用用户给的 SEC 列表里的 Archives 链接，不要编造 accession。
                - 电话会纪要优先 Motley Fool 免费全文页，不要 Seeking Alpha，不要 StockAnalysis 付费墙。
                - 一致预期用 Yahoo Finance /quote/代码/analysis/，可加 Equibles。提示抄进一张 xlsx：收入/EPS/分部/capex/FCF。
                - url 必须是完整 https 地址。每组 1 到 4 条。没把握的链接不要写。
                """;
    }

    private String userPrompt(String ticker, String sec) {
        LocalDate day = LocalDate.now(ZoneId.of(settings.zone()));
        return "今天是 " + day + "（北京时间）。标的 " + ticker + "。\n"
                + "SEC 最近申报：\n" + (sec.isBlank() ? "（没有查到，请仍给出可打开的官方或免费入口）" : sec);
    }

    private String secContext(String ticker) {
        try {
            String cik = cikOf(ticker);
            if (cik.isBlank()) return "";
            String padded = String.format("%010d", Long.parseLong(cik));
            JsonNode root = json.readTree(http.get(
                    URI.create("https://data.sec.gov/submissions/CIK" + padded + ".json"),
                    SEC_HEADERS, false));
            JsonNode recent = root.path("filings").path("recent");
            JsonNode forms = recent.path("form");
            JsonNode accessions = recent.path("accessionNumber");
            JsonNode documents = recent.path("primaryDocument");
            JsonNode filed = recent.path("filingDate");
            JsonNode reports = recent.path("reportDate");
            StringBuilder out = new StringBuilder();
            int kept = 0;
            for (int i = 0; i < forms.size() && kept < 12; i++) {
                String form = forms.path(i).asText("");
                if (!form.startsWith("10-Q") && !form.startsWith("8-K") && !form.startsWith("10-K")) continue;
                String accession = accessions.path(i).asText("").replace("-", "");
                String document = documents.path(i).asText("");
                if (accession.isBlank() || document.isBlank()) continue;
                String url = "https://www.sec.gov/Archives/edgar/data/" + cik + "/" + accession + "/" + document;
                out.append(form).append(" filed ").append(filed.path(i).asText(""))
                        .append(" report ").append(reports.path(i).asText(""))
                        .append(" ").append(url).append('\n');
                kept++;
            }
            return out.toString().strip();
        } catch (Exception e) {
            log.warn("SEC filings for {} failed: {}", ticker, e.toString());
            return "";
        }
    }

    private String cikOf(String ticker) throws Exception {
        JsonNode root;
        try {
            String cached = redis.opsForValue().get("finance:sec-tickers");
            if (cached == null || cached.isBlank()) {
                cached = http.get(URI.create("https://www.sec.gov/files/company_tickers.json"), SEC_HEADERS, false);
                redis.opsForValue().set("finance:sec-tickers", cached, Duration.ofHours(24));
            }
            root = json.readTree(cached);
        } catch (Exception e) {
            log.warn("SEC ticker map failed: {}", e.toString());
            return "";
        }
        String want = ticker.toUpperCase();
        for (JsonNode row : root) {
            if (want.equalsIgnoreCase(row.path("ticker").asText())) {
                return String.valueOf(row.path("cik_str").asLong());
            }
        }
        return "";
    }

    static Map<String, Object> parseModelJson(String content, ObjectMapper json) throws Exception {
        String text = unwrap(content);
        JsonNode root = json.readTree(text);
        if (!root.isObject()) throw new IllegalStateException("模型没有给出链接");
        Map<String, Object> body = new LinkedHashMap<>();
        String heading = root.path("heading").asText("").strip();
        body.put("heading", heading.isBlank() ? "打开后下载或打印成 PDF" : heading);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (JsonNode group : root.path("groups")) {
            if (group == null || !group.isObject()) continue;
            String title = group.path("title").asText("").strip();
            if (title.isBlank()) continue;
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode item : group.path("items")) {
                if (item == null || !item.isObject()) continue;
                String label = item.path("label").asText("").strip();
                String url = item.path("url").asText("").strip();
                if (label.isBlank() || !url.startsWith("https://")) continue;
                if (url.length() > 500) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", label);
                row.put("url", url);
                String note = item.path("note").asText("").strip();
                if (!note.isBlank()) row.put("note", note);
                items.add(row);
                if (items.size() >= 4) break;
            }
            if (items.isEmpty()) continue;
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("title", title);
            String hint = group.path("hint").asText("").strip();
            if (!hint.isBlank()) block.put("hint", hint);
            block.put("items", items);
            groups.add(block);
            if (groups.size() >= 3) break;
        }
        if (groups.isEmpty()) throw new IllegalStateException("模型没有给出可用链接");
        body.put("groups", groups);
        return body;
    }

    static String unwrap(String content) {
        String text = content == null ? "" : content.strip();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) text = text.substring(start + 1, end).trim();
        }
        int from = text.indexOf('{');
        int to = text.lastIndexOf('}');
        if (from >= 0 && to > from) text = text.substring(from, to + 1);
        return text;
    }

    static String normalize(String ticker) {
        String value = ticker == null ? "" : ticker.strip().toUpperCase();
        if (value.isBlank() || !TICKER.matcher(value).matches()) {
            throw new IllegalArgumentException("标的写成 GOOGL 或 MSFT 这种即可");
        }
        return value;
    }
}
