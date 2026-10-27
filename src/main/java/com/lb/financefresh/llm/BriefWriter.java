package com.lb.financefresh.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.market.ExternalHttp;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.CalendarEvent;
import com.lb.financefresh.model.NewsItem;
import com.lb.financefresh.model.Quote;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class BriefWriter {

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;

    public BriefWriter(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    public JsonNode write(BriefKind kind, java.time.LocalDate day, String yieldLine, String macroMonitor, List<Quote> quotes,
                           List<NewsItem> news, List<CalendarEvent> released) throws Exception {
        if (!settings.deepseekReady()) {
            throw new IllegalStateException("还没有填写 DEEPSEEK_API_KEY");
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekModel());
        payload.put("temperature", 0.2);
        payload.putObject("response_format").put("type", "json_object");
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject().put("role", "user").put("content", userPrompt(kind, day, yieldLine, macroMonitor, quotes, news, released));
        String body = http.postJson(
                URI.create(settings.deepseekUrl()),
                json.writeValueAsString(payload),
                Map.of("Authorization", "Bearer " + settings.deepseekKey()));
        String content = json.readTree(body).path("choices").path(0).path("message").path("content").asText("");
        content = content.trim();
        if (content.startsWith("```")) {
            int start = content.indexOf('\n');
            int end = content.lastIndexOf("```");
            if (start >= 0 && end > start) content = content.substring(start + 1, end).trim();
        }
        return json.readTree(content);
    }

    private static String systemPrompt() {
        return """
                你是买方投研助理。只根据用户给的 JSON 写简报，不编造输入里没有的数字、公司、财报或讲话。
                收益率句子已经算好，不要改写其中的基点。
                涨跌幅和价格必须按 quotes 里四条腿原样使用，不要自己计算，不要用盘中价乘涨跌幅去推盘后或夜盘价，也不要把任何两段合成一个相对前收的数字。
                regular 是盘中（价和涨跌相对前收），premarket 是盘前（相对最近一次正规盘收盘），afterHours 是盘后成交价（没有成交就是 null），overnight 是夜盘。字段为 null 就写暂无，不要编。
                USO 是原油 ETF，GLD 是黄金 ETF，VIX 是恐慌指数；写归因时用中文名，数字仍用 quotes 里的四段。
                quotes.session 只说明最新成交属于哪一时段。夜盘是美东 20:00 到次日 04:00，不要写成盘后；也不要把上一时段说成今天的盘前。
                只返回一个 JSON 对象，键为 macroComment, movers, narratives。
                movers 每项含 symbol, sentiment（-1 到 1）, driver（一句话，点明是财报、目标价、行业政策还是其他已出现的原因；看不出来就写“输入里没有明确原因”）。
                narratives 最多 3 项，每项含 title, detail。这是这一天跨来源最突出的 3 条宏观或板块主线。
                macroMonitor 是程序已经算好的五项指标和判断。macroComment 不要改写、不要重算这些数字。
                macroComment 是一两句流动性评论，不要重复收益率数字。
                """;
    }

    private String userPrompt(BriefKind kind, java.time.LocalDate day, String yieldLine, String macroMonitor, List<Quote> quotes,
                              List<NewsItem> news, List<CalendarEvent> released) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("day", day.toString());
        root.put("brief", switch (kind) {
            case PREMARKET -> "盘前：聚焦当晚数据和盘前异动";
            case RECAP -> "复盘：聚焦收盘、美债变化和盘后财报";
            case NOW -> "当前时报：聚焦此刻最新行情、新闻和宏观水位";
        });
        root.put("yieldLine", yieldLine);
        root.put("macroMonitor", macroMonitor == null ? "" : macroMonitor);
        root.set("quotes", json.valueToTree(quotes));
        var rows = root.putArray("news");
        for (NewsItem item : news) {
            ObjectNode row = rows.addObject();
            row.put("source", item.source());
            row.put("title", item.title());
            row.put("summary", clip(item.summary()));
            row.set("symbols", json.valueToTree(item.symbols()));
            row.put("publishedAt", item.publishedAt() == null ? "" : item.publishedAt().toString());
            row.put("sourceSentiment", item.sentiment());
        }
        root.set("released", json.valueToTree(released));
        return json.writeValueAsString(root);
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= 400 ? text : text.substring(0, 400);
    }
}
