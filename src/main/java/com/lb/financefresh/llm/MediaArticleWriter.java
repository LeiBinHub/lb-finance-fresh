package com.lb.financefresh.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.market.ChinaQuote;
import com.lb.financefresh.market.ExternalHttp;
import com.lb.financefresh.market.TreeNews;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.MediaDesk;
import com.lb.financefresh.model.NewsItem;
import com.lb.financefresh.model.Quote;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one fetch into publishable Chinese and English market posts.
 * Uses DeepSeek thinking mode (Chain-of-Thought) instead of plain chat JSON mode.
 */
@Component
public class MediaArticleWriter {

    public static final String ZH_MARK = "<<<ZH>>>";
    public static final String EN_MARK = "<<<EN>>>";
    private static final Pattern BLOCK = Pattern.compile(
            "(?s)" + Pattern.quote(ZH_MARK) + "\\s*(.*?)\\s*" + Pattern.quote(EN_MARK) + "\\s*(.*)\\s*");

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;

    public MediaArticleWriter(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    public Article write(BriefKind kind, LocalDate day, String briefText, String yieldLine, String macroMonitor,
                         List<Quote> quotes, List<NewsItem> news, List<TreeNews.Item> treeNews) throws Exception {
        if (!settings.deepseekReady()) {
            throw new IllegalStateException("还没有填写 DEEPSEEK_API_KEY");
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekThinkModel());
        payload.put("reasoning_effort", settings.deepseekReasoningEffort());
        payload.putObject("thinking").put("type", "enabled");
        payload.put("max_tokens", 24576);
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt(MediaDesk.US_MACRO));
        messages.addObject().put("role", "user").put("content",
                userPrompt(kind, day, briefText, yieldLine, macroMonitor, quotes, news, treeNews));
        String body = http.postJson(
                URI.create(settings.deepseekUrl()),
                json.writeValueAsString(payload),
                Map.of("Authorization", "Bearer " + settings.deepseekKey()),
                Duration.ofSeconds(180));
        String content = json.readTree(body).path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.startsWith("```")) {
            int start = content.indexOf('\n');
            int end = content.lastIndexOf("```");
            if (start >= 0 && end > start) content = content.substring(start + 1, end).trim();
        }
        return requireComplete(parse(content));
    }

    public Article writeDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews)
            throws Exception {
        return writeDesk(desk, day, macroArticle, treeNews, List.of());
    }

    public Article writeDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews,
                             List<ChinaQuote> quotes) throws Exception {
        if (desk == null || desk == MediaDesk.US_MACRO) {
            throw new IllegalArgumentException("美国宏观由获取时报自动生成");
        }
        if (!settings.deepseekReady()) {
            throw new IllegalStateException("还没有填写 DEEPSEEK_API_KEY");
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekThinkModel());
        payload.put("reasoning_effort", settings.deepseekReasoningEffort());
        payload.putObject("thinking").put("type", "enabled");
        payload.put("max_tokens", 24576);
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt(desk));
        messages.addObject().put("role", "user").put("content",
                userPromptDesk(desk, day, macroArticle, treeNews, quotes));
        String body = http.postJson(
                URI.create(settings.deepseekUrl()),
                json.writeValueAsString(payload),
                Map.of("Authorization", "Bearer " + settings.deepseekKey()),
                Duration.ofSeconds(180));
        String content = json.readTree(body).path("choices").path(0).path("message").path("content").asText("").trim();
        if (content.startsWith("```")) {
            int start = content.indexOf('\n');
            int end = content.lastIndexOf("```");
            if (start >= 0 && end > start) content = content.substring(start + 1, end).trim();
        }
        return requireComplete(parse(content));
    }

    public static Article parse(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isBlank()) throw new IllegalStateException("媒体文章是空的");
        Matcher matcher = BLOCK.matcher(text);
        if (matcher.find()) {
            Piece zh = piece(matcher.group(1), "美股观察");
            Piece en = piece(matcher.group(2), "US Market Watch");
            return new Article(zh.title(), zh.body(), en.title(), en.body());
        }
        Piece zh = piece(text, "美股观察");
        return new Article(zh.title(), zh.body(), "", "");
    }

    static Article requireComplete(Article article) {
        if (article == null || article.bodyZh().isBlank() || article.bodyEn().isBlank()) {
            throw new IllegalStateException("中英文媒体文章不完整");
        }
        if (isOutputMark(article.titleZh()) || isOutputMark(article.titleEn())) {
            throw new IllegalStateException("中英文媒体文章不完整");
        }
        return article;
    }

    private static Piece piece(String raw, String fallbackTitle) {
        String text = raw == null ? "" : raw.strip();
        if (text.isBlank()) return new Piece(fallbackTitle, "");
        String[] lines = text.split("\\R", -1);
        int i = 0;
        while (i < lines.length && skippableHeading(lines[i])) i++;
        if (i >= lines.length) return new Piece(fallbackTitle, "");
        String first = cleanTitle(lines[i]);
        if (first.isBlank() || isOutputMark(first)) first = fallbackTitle;
        StringBuilder rest = new StringBuilder();
        for (int j = i + 1; j < lines.length; j++) {
            if (rest.isEmpty() && lines[j].isBlank()) continue;
            if (!rest.isEmpty()) rest.append('\n');
            rest.append(lines[j]);
        }
        String body = rest.toString().strip();
        if (body.isBlank()) body = text;
        return new Piece(first, body);
    }

    private static String cleanTitle(String line) {
        String first = line == null ? "" : line.replaceFirst("^#+\\s*", "").strip();
        if (first.startsWith("标题：") || first.startsWith("标题:")) first = first.substring(3).strip();
        if (first.regionMatches(true, 0, "Title:", 0, 6)) first = first.substring(6).strip();
        return first;
    }

    private static boolean skippableHeading(String line) {
        String stripped = line == null ? "" : line.strip();
        if (stripped.isBlank()) return true;
        String title = cleanTitle(stripped);
        return title.isBlank() || isOutputMark(title);
    }

    private static boolean isOutputMark(String text) {
        return ZH_MARK.equals(text) || EN_MARK.equals(text);
    }

    static String systemPrompt(MediaDesk desk) {
        return deskBrief(desk) + "\n" + voiceRules() + "\n" + outputFormat();
    }

    private static String deskBrief(MediaDesk desk) {
        if (desk == MediaDesk.US_MICRO) {
            return """
                    你盯美股，中英文都能写。这是美国微观稿：在已有美国宏观稿之上，用当天 Telegram 快讯写出可直接发出去的文章。
                    频道只有 Tree News、Walter Bloomberg、Unusual Whales、ForexLive。宏观稿已有的判断不要整段复述，补上宏观稿没写到的交易线索、资金流、异常期权、外汇与公司事件。
                    只根据用户给的材料写，不编造数字、公司、财报或讲话。没给的日期不要编。
                    treeNews 只包含 day 这一天（北京时间）上述频道的消息。每条有 channel、原文 text 和中文注释 zh。能对上宏观稿的要写进判断；对不上的不要硬凑。数组为空就当当天没有监听消息。
                    macroArticle 是当天已写好的美国宏观稿，可能为空；为空就只根据快讯写微观稿。
                    """;
        }
        if (desk == MediaDesk.CN_MICRO) {
            return """
                    你盯A股和港股，中英文都能写。这是中国微观稿：用腾讯财经最新价看盘，再用当天金十（jin10light）快讯解读政策、数据、商品与股市。
                    只根据用户给的材料写，不编造，不把美股频道快讯写进来。没给的日期不要编。
                    quotes 来自腾讯财经最新价（last、prevClose、change、changePercent）。上证、深成、创业板、沪深300、恒指和港股必须用这些数字，不要用金十里可能过时的开盘价覆盖最新价。没有 quotes 就只根据金十写，不要编指数。
                    A股没有美股盘前盘后夜盘，写成开盘、午盘、收盘或最新价即可。
                    treeNews 只包含 day 这一天（北京时间）金十消息。每条有原文 text 和中文注释 zh。能对上行情的要写进判断；对不上的不要硬凑。数组为空就当当天没有监听消息。
                    """;
        }
        return """
                你盯美股和宏观，中英文都能写。把用户给的材料写成可直接发出去的文章。
                只根据用户给的材料写，不编造数字、公司、财报或讲话。
                股价必须把盘中、盘前、盘后、夜盘四段分开写，用 quotes.regular / premarket / afterHours / overnight 里的 price 和 changePercent。价格用字段里的数，不要用盘中价乘涨跌幅去推盘后价。null 就是没有这段，不要编。
                USO 是原油，GLD 是黄金，VIX 是恐慌指数。
                treeNews 只包含 day 这一天（北京时间）从 Telegram 各监听频道拿到的实时消息，不是历史全部。每条有 channel、原文 text 和中文注释 zh。把这些当当天最底层消息面来参考分析，能对上盘面和新闻的要写进判断；对不上的不要硬凑。没给的日期不要编。数组为空就当当天没有监听消息。
                """;
    }

    static String voiceRules() {
        return """
                写法人味，不要像模型在交差。
                你是每天盯盘的人，跟熟人讲今天发生了什么、你怎么看。有判断，有脾气，但不装权威、不喊单、不鸡汤。
                中文像微信长文：句子长短不一，偶尔口语，少用书面套话。英文像交易员写给同事的 note，不要社论腔，不要逐句硬译中文。
                标题像人起的，短一点。不要用冒号把两个半句焊在一起，不要「A与B，透露同一件事」这种模板。
                数字只在撑住判断时出现。不要开篇把指数和个股报价念一遍，不要把自选股列成盘面速览大表。
                按当天真正要紧的事写，不要硬凑三条主线，不要每个段落都「现象+解释+启示」。
                禁止套话：总分总；先看/其次/最后；第一条线第二条线；结论：；我的判断是；组合起来是；这不是X而是Y连用；透露同一件事；最诚实的信号；值得细看；更有信息量；盘面速览；对仗排比；空话金句；盯住N个水位。
                两份观点一致，但各自按母语习惯措辞。有话则长，无话则短。中文大约 600 到 1100 字，写不满不要注水；英文 350 到 700 词。
                不要写“本次缺口”，不要罗列接口失败或数据缺失说明。
                """;
    }

    private static String outputFormat() {
        return """
                <<<ZH>>> 和 <<<EN>>> 各只出现一次，不要把标记写成标题。必须先写完中文，再写英文；英文可以短，但不能省略标记和正文。
                严格按下面格式输出，不要省略标记，不要输出 JSON，不要输出思考过程：

                <<<ZH>>>
                中文标题一行
                中文正文

                <<<EN>>>
                English headline on one line
                English body
                """;
    }

    String userPrompt(BriefKind kind, LocalDate day, String briefText, String yieldLine, String macroMonitor,
                      List<Quote> quotes, List<NewsItem> news, List<TreeNews.Item> treeNews) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("day", day.toString());
        root.put("kind", kind.title());
        root.put("briefText", briefText == null ? "" : briefText);
        root.put("yieldLine", yieldLine == null ? "" : yieldLine);
        root.put("macroMonitor", macroMonitor == null ? "" : macroMonitor);
        root.set("quotes", json.valueToTree(quotes));
        var rows = root.putArray("news");
        if (news != null) {
            for (NewsItem item : news) {
                ObjectNode row = rows.addObject();
                row.put("source", item.source());
                row.put("title", item.title());
                row.put("summary", item.summary() == null ? "" : item.summary());
                row.set("symbols", json.valueToTree(item.symbols()));
            }
        }
        putFlashes(root, treeNews);
        return json.writeValueAsString(root);
    }

    String userPromptDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews)
            throws Exception {
        return userPromptDesk(desk, day, macroArticle, treeNews, List.of());
    }

    String userPromptDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews,
                          List<ChinaQuote> quotes) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("day", day.toString());
        root.put("desk", desk.id());
        ObjectNode macro = root.putObject("macroArticle");
        if (macroArticle != null) {
            macro.put("titleZh", macroArticle.titleZh() == null ? "" : macroArticle.titleZh());
            macro.put("bodyZh", macroArticle.bodyZh() == null ? "" : macroArticle.bodyZh());
            macro.put("titleEn", macroArticle.titleEn() == null ? "" : macroArticle.titleEn());
            macro.put("bodyEn", macroArticle.bodyEn() == null ? "" : macroArticle.bodyEn());
        }
        var quoteRows = root.putArray("quotes");
        if (quotes != null) {
            for (ChinaQuote quote : quotes) {
                if (quote == null) continue;
                ObjectNode row = quoteRows.addObject();
                row.put("code", quote.code() == null ? "" : quote.code());
                row.put("name", quote.name() == null ? "" : quote.name());
                row.put("last", quote.last());
                row.put("prevClose", quote.prevClose());
                row.put("change", quote.change());
                row.put("changePercent", quote.changePercent());
                row.put("at", quote.at() == null ? "" : quote.at());
            }
        }
        putFlashes(root, treeNews);
        return json.writeValueAsString(root);
    }

    private void putFlashes(ObjectNode root, List<TreeNews.Item> treeNews) {
        var flashes = root.putArray("treeNews");
        if (treeNews == null) return;
        for (TreeNews.Item item : treeNews) {
            ObjectNode row = flashes.addObject();
            String handle = item.channel() == null ? "" : item.channel();
            row.put("channel", handle);
            row.put("channelZh", TreeNews.titleZh(handle));
            row.put("at", item.at() == null ? "" : item.at());
            row.put("text", item.text() == null ? "" : item.text());
            row.put("zh", item.zh() == null ? "" : item.zh());
            row.put("url", item.url() == null ? "" : item.url());
        }
    }

    private record Piece(String title, String body) {
    }

    public record Article(String titleZh, String bodyZh, String titleEn, String bodyEn) {
        public String title() {
            return titleZh == null || titleZh.isBlank() ? titleEn : titleZh;
        }
    }
}
