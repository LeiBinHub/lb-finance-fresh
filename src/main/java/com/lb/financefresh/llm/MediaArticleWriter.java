package com.lb.financefresh.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.filing.FilingExtract;
import com.lb.financefresh.market.ChinaFundFlow;
import com.lb.financefresh.market.ChinaMarket;
import com.lb.financefresh.market.ChinaQuote;
import com.lb.financefresh.market.ChinaSector;
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
 * Media desks use plain chat. Filings keep thinking mode.
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
        payload.put("model", settings.deepseekModel());
        payload.put("temperature", 0.9);
        payload.put("max_tokens", 4096);
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt(MediaDesk.US_MACRO));
        messages.addObject().put("role", "user").put("content",
                userPrompt(kind, day, briefText, yieldLine, macroMonitor, quotes, news, treeNews));
        return completeFromDeepSeek(payload);
    }

    public Article writeDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews)
            throws Exception {
        return writeDesk(desk, day, macroArticle, treeNews, ChinaMarket.Snapshot.empty());
    }

    public Article writeDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews,
                             List<ChinaQuote> quotes) throws Exception {
        return writeDesk(desk, day, macroArticle, treeNews, ChinaMarket.Snapshot.ofQuotes(quotes));
    }

    public Article writeDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews,
                             ChinaMarket.Snapshot market) throws Exception {
        if (desk == null || desk == MediaDesk.US_MACRO) {
            throw new IllegalArgumentException("美国宏观由获取时报自动生成");
        }
        if (!settings.deepseekReady()) {
            throw new IllegalStateException("还没有填写 DEEPSEEK_API_KEY");
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekModel());
        payload.put("temperature", 0.9);
        payload.put("max_tokens", 4096);
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt(desk));
        messages.addObject().put("role", "user").put("content",
                userPromptDesk(desk, day, macroArticle, treeNews, market));
        return completeFromDeepSeek(payload);
    }

    public Article writeFiling(LocalDate day, String originalName, String ticker, String note, String filingText,
                               List<Quote> quotes) throws Exception {
        return writeFiling(day, originalName, ticker, note, filingText, quotes, List.of());
    }

    public Article writeFiling(LocalDate day, String originalName, String ticker, String note, String filingText,
                               List<Quote> quotes, List<FilingExtract.Picture> pictures)
            throws Exception {
        if (!settings.deepseekReady()) {
            throw new IllegalStateException("还没有填写 DEEPSEEK_API_KEY");
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("model", settings.deepseekThinkModel());
        payload.put("reasoning_effort", settings.deepseekReasoningEffort());
        payload.putObject("thinking").put("type", "enabled");
        payload.put("temperature", 0.8);
        payload.put("max_tokens", 32768);
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPromptFiling());
        messages.addObject().put("role", "user").put("content",
                userPromptFiling(day, originalName, ticker, note, filingText, quotes, pictures));
        return completeFromDeepSeek(payload, Duration.ofSeconds(360));
    }

    private Article completeFromDeepSeek(ObjectNode payload) throws Exception {
        return completeFromDeepSeek(payload, Duration.ofSeconds(180));
    }

    private Article completeFromDeepSeek(ObjectNode payload, Duration timeout) throws Exception {
        String body = http.postJson(
                URI.create(settings.deepseekUrl()),
                json.writeValueAsString(payload),
                Map.of("Authorization", "Bearer " + settings.deepseekKey()),
                timeout);
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
        return title.isBlank() || isOutputMark(title) || title.matches("<<<IMG:\\d+>>>");
    }

    private static boolean isOutputMark(String text) {
        return ZH_MARK.equals(text) || EN_MARK.equals(text);
    }

    static String systemPrompt(MediaDesk desk) {
        return deskBrief(desk) + "\n" + voiceRules() + "\n" + outputFormat();
    }

    static String systemPromptFiling() {
        return """
                你盯美股，中英文都能写。这是美股财报研读：每家公司固定三类材料——10-Q / 业绩稿、电话会纪要、一致预期表。filingText 按「===== 公司 n/N：TICKER =====」分开，下面再按「----- 10-Q / 业绩稿 -----」「----- 电话会纪要 -----」「----- 一致预期 -----」分开。结合此刻美股行情 quotes 写可直接发出去的长研报。
                只根据用户给的材料写。不编造财报数字、指引、分部、管理层原话或一致预期。某份文件没有的数字不要从记忆里补。没给的日期不要编。
                ticker 是本次提交的公司，逗号分隔，最多三家。只有一家时：只研读这一家，不要硬拉别的公司来对照，也不要编第二家的数字。有两家或三家时：先把每家自己的三类材料读完，再做对照。
                quotes 是此刻美股自选（含用户 ticker）盘中/盘前/盘后/夜盘。股价必须把四段分开写，用 quotes.regular / premarket / afterHours / overnight 里的 price 和 changePercent。null 就是没有这段，不要编。
                USO 是原油，GLD 是黄金，VIX 是恐慌指数。
                不要写A股、港股、腾讯行情、主力资金或板块榜。
                topic（和 note 相同）是用户提交前可选填写的研究话题，可能为空。originalName 是文件名列表。
                topic 为空：按原来的财报研读来写。这些事要写到，但顺着你读材料时真正卡住的地方写，不要按清单走：盘面改没改定价；超没超预期；真正养活或拖后腿的那条业务；电话会里一句有用的话（是谁说的）；capex/FCF 在买什么。不要自己另立一个话题当主线。
                topic 非空：整篇围绕这个话题讨论，用各家 10-Q、电话会纪要、一致预期和 quotes 当证据。不要先写一篇标准财报综述再捎带提一句。材料里跟话题无关的分部和数字少写或不写。材料没覆盖这个话题时明说没覆盖，不要用记忆补。标题也要落在这个话题上。
                按盯盘笔记来写，不要写成卖方研报，也不要写成盘面快讯。对不上行情的不要硬凑。读者是盯盘的普通人：先讲人话，再给证据。
                标题短、像人随手起的。一段只讲一件事，段与段空一行。不要编号小标题，不要「一、二、三」，不要 markdown 表格。
                只有一家就只写这家。两家或三家才对照，一家不要硬拉第二家。
                材料里的数字是证据，不是正文。一段里最多四个关键数字。能说「翻倍」「还在亏」就不要把 76.8B / +25% / 53% 全念上。不要开篇把指数和个股报价念一遍。不要把业绩稿、脚注、会议纪要抄进正文。
                images 只是可选插图。先把文章写完，再看有没有图刚好能给那段判断当证据。有就在该段落后单独一行写 <<<IMG:id>>> ，id 只能来自 images。不要为了塞图去写一段，不要因为有图就复述图里的数字。不够契合宁可不插。没有 images 就不要写这个标记。不要把标记写成标题，不要编造不存在的 id。
                """ + "\n" + filingVoiceRules() + "\n" + outputFormat();
    }

    private static String deskBrief(MediaDesk desk) {
        if (desk == MediaDesk.US_MICRO) {
            return """
                    你盯美股，中英文都能写。这是美国微观稿：在已有美国宏观稿之上，用当天 Telegram 快讯写一篇盯盘随笔，不是行情综述。
                    频道只有 Tree News、Walter Bloomberg、Unusual Whales、ForexLive。宏观稿已有的判断不要整段复述，只补宏观稿没写到、而且今天真正扎眼的那一两件事。
                    只根据用户给的材料写，不编造数字、公司、财报或讲话。没给的日期不要编。
                    treeNews 只包含 day 这一天（北京时间）上述频道的消息。每条有 channel、原文 text 和中文注释 zh。能对上宏观稿的要写进判断；对不上的不要硬凑。数组为空就当当天没有监听消息。
                    macroArticle 是当天已写好的美国宏观稿，可能为空；为空就只根据快讯写微观稿。
                    """;
        }
        if (desk == MediaDesk.CN_MICRO) {
            return """
                    你盯A股和港股，中英文都能写。这是中国微观稿：根据中国市场看板（腾讯行情、东财主力资金、腾讯板块榜）和当天金十（jin10light）快讯写一篇盯盘随笔，不是收盘综述。
                    只根据用户给的材料写，不编造，不把美股频道快讯写进来。没给的日期不要编。
                    quotes 来自腾讯财经最新价：last、prevClose、change、changePercent、high、low、amount（成交额，单位万）、turnover、pe、amplitude。指数、个股价格只能用这些字段，不要用金十里可能过时的开盘价覆盖最新价。不要把上证、深成、创业板、沪深300、恒指和港股轮着念一遍，只写今天真正离谱或撑住判断的那几个。
                    indexFlows / industryIn / industryOut 是东财主力净流入（mainNet 单位元，mainPct 是占比）。industriesUp / industriesDown / conceptsUp 是腾讯行业和概念涨跌榜。资金和板块有就用、没有就别硬写；有也不要把整张流入流出榜抄进来。
                    没有 quotes 就只根据金十写，不要编指数。
                    A股没有美股盘前盘后夜盘，写成开盘、午盘、收盘或最新价即可。
                    treeNews 只包含 day 这一天（北京时间）金十消息。每条有原文 text 和中文注释 zh。能对上行情、资金、板块的要写进判断；对不上的不要硬凑。数组为空就当当天没有监听消息。
                    """;
        }
        return """
                你盯美股和宏观，中英文都能写。把用户给的材料写成一篇盯盘随笔，可直接发出去，不是盘面速览。
                只根据用户给的材料写，不编造数字、公司、财报或讲话。
                股价要把盘中、盘前、盘后、夜盘分开，用 quotes.regular / premarket / afterHours / overnight 里的 price 和 changePercent。价格用字段里的数，不要用盘中价乘涨跌幅去推盘后价。null 就是没有这段，不要编。只写这次判断用到的标的和时段，不要把自选股四条腿轮一遍。
                USO 是原油，GLD 是黄金，VIX 是恐慌指数。
                treeNews 只包含 day 这一天（北京时间）从 Telegram 各监听频道拿到的实时消息，不是历史全部。每条有 channel、原文 text 和中文注释 zh。把这些当当天最底层消息面来参考分析，能对上盘面和新闻的要写进判断；对不上的不要硬凑。没给的日期不要编。数组为空就当当天没有监听消息。
                """;
    }

    static String voiceRules() {
        return humanVoice() + """
                数字只在撑住判断时出现。不要开篇把指数和个股报价念一遍，不要把自选股列成盘面速览大表，不要把资金和板块榜轮着念。
                按当天真正扎眼的一两件事写，不要硬凑三条主线，不要给全市场做体检。
                开篇不要「今天这盘…」，不要「表面…真正…」，不要「同一个市场，两个温度」。直接从那件扎眼的事写起。
                每段不要「先堆数据，最后给一句判断」。判断先说，数字最多两三个跟在后面。
                禁止再用对比句当骨架：问题在A不在B；看的是A不是B；涨的那边很清楚；消息面并不配合；说白了。
                不要用「明天看什么」收尾，除非你整天真的只盯那一件。
                有话则长，无话则短。中文大约 450 到 800 字，写到 1100 多半是在念数据；英文 250 到 500 词，可以比中文少写一两段，不要逐段翻译。
                """;
    }

    static String filingVoiceRules() {
        return humanVoice() + """
                这是一篇对照笔记，不是卖方研报，也不是盘面快讯。
                写完一段，问自己：没看过 10-Q 的人能不能懂你在说什么。不要炒原文。分析要比数字多。
                中文大约 2500 到 4500 字，篇幅长在你卡住的地方，不长在抄数字；写不满不要注水，更不要用数字填空白。英文 1200 到 2200 词，可以少写几段，不要跟中文一一对应。
                """;
    }

    static String humanVoice() {
        return """
                写法人味，不要像模型在交差，也不要像在完成一份结构完整的作业。
                你是每天盯盘的人，跟熟人讲你刚看完的东西。有判断，有脾气，但不装权威、不喊单、不鸡汤。
                中文像微信长文：句子长短差一截，偶尔口语，少用书面套话。可以有一句很短的话单独成段。拿不准就写拿不准，不要为了完整把材料里每项都交代一遍。
                英文像交易员写给同事的 note，不要社论腔，不要逐句硬译中文。
                标题像人随手起的，短一点。不要问句，不要对仗，不要用冒号把两个半句焊在一起，不要「A，B」两截，不要「A与B，透露同一件事」。
                不要每个段落都「现象+解释+启示」，不要每段字数差不多，不要段段以判断句收尾。
                禁止套话：总分总；先看/其次/最后；第一条线第二条线；结论：；我的判断是；组合起来是；这不是X而是Y连用；透露同一件事；最诚实的信号；值得细看；更有信息量；盘面速览；对仗排比；空话金句；盯住N个水位；值得注意的是；需要指出的是；综上所述；与此同时；不仅…而且；一方面…另一方面；换句话说；这意味着；不难发现；总的来看；从…来看；对照来看；同一晚把两家劈成两边。
                不要 markdown 表格，不要编号小标题，不要「一、二、三」。
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
        return userPromptDesk(desk, day, macroArticle, treeNews, ChinaMarket.Snapshot.empty());
    }

    String userPromptDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews,
                          List<ChinaQuote> quotes) throws Exception {
        return userPromptDesk(desk, day, macroArticle, treeNews, ChinaMarket.Snapshot.ofQuotes(quotes));
    }

    String userPromptDesk(MediaDesk desk, LocalDate day, Article macroArticle, List<TreeNews.Item> treeNews,
                          ChinaMarket.Snapshot market) throws Exception {
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
        putChinaBoard(root, "quotes", market);
        putFlashes(root, treeNews);
        return json.writeValueAsString(root);
    }

    String userPromptFiling(LocalDate day, String originalName, String ticker, String note, String filingText,
                            List<Quote> quotes) throws Exception {
        return userPromptFiling(day, originalName, ticker, note, filingText, quotes, List.of());
    }

    String userPromptFiling(LocalDate day, String originalName, String ticker, String note, String filingText,
                            List<Quote> quotes, List<FilingExtract.Picture> pictures)
            throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("day", day == null ? "" : day.toString());
        root.put("originalName", originalName == null ? "" : originalName);
        var fileNames = root.putArray("files");
        for (String name : originalName == null ? new String[0] : originalName.split(" · ")) {
            if (name != null && !name.isBlank()) fileNames.add(name.strip());
        }
        root.put("ticker", ticker == null ? "" : ticker);
        String topic = note == null ? "" : note.strip();
        root.put("note", topic);
        root.put("topic", topic);
        root.put("filingText", filingText == null ? "" : filingText);
        root.set("quotes", json.valueToTree(quotes == null ? List.of() : quotes));
        var rows = root.putArray("images");
        if (pictures != null) {
            for (var picture : pictures) {
                if (picture == null) continue;
                ObjectNode row = rows.addObject();
                row.put("id", picture.id());
                row.put("place", picture.place() == null ? "" : picture.place());
                row.put("caption", picture.caption() == null ? "" : picture.caption());
            }
        }
        return json.writeValueAsString(root);
    }

    private static void putChinaBoard(ObjectNode root, String quoteField, ChinaMarket.Snapshot market) {
        ChinaMarket.Snapshot board = market == null ? ChinaMarket.Snapshot.empty() : market;
        var quoteRows = root.putArray(quoteField);
        for (ChinaQuote quote : board.quotes()) {
            if (quote == null) continue;
            ObjectNode row = quoteRows.addObject();
            row.put("code", quote.code() == null ? "" : quote.code());
            row.put("name", quote.name() == null ? "" : quote.name());
            row.put("last", quote.last());
            row.put("prevClose", quote.prevClose());
            row.put("change", quote.change());
            row.put("changePercent", quote.changePercent());
            row.put("at", quote.at() == null ? "" : quote.at());
            row.put("high", quote.high());
            row.put("low", quote.low());
            row.put("volume", quote.volume());
            row.put("amount", quote.amount());
            row.put("turnover", quote.turnover());
            row.put("pe", quote.pe());
            row.put("amplitude", quote.amplitude());
        }
        putFlows(root.putArray("indexFlows"), board.indexFlows());
        putFlows(root.putArray("industryIn"), board.industryIn());
        putFlows(root.putArray("industryOut"), board.industryOut());
        putSectors(root.putArray("industriesUp"), board.industriesUp());
        putSectors(root.putArray("industriesDown"), board.industriesDown());
        putSectors(root.putArray("conceptsUp"), board.conceptsUp());
    }

    private static void putFlows(ArrayNode rows, List<ChinaFundFlow> flows) {
        if (flows == null) return;
        for (ChinaFundFlow flow : flows) {
            if (flow == null) continue;
            ObjectNode row = rows.addObject();
            row.put("code", flow.code() == null ? "" : flow.code());
            row.put("name", flow.name() == null ? "" : flow.name());
            row.put("changePercent", flow.changePercent());
            row.put("mainNet", flow.mainNet());
            row.put("mainPct", flow.mainPct());
        }
    }

    private static void putSectors(ArrayNode rows, List<ChinaSector> sectors) {
        if (sectors == null) return;
        for (ChinaSector sector : sectors) {
            if (sector == null) continue;
            ObjectNode row = rows.addObject();
            row.put("kind", sector.kind() == null ? "" : sector.kind());
            row.put("name", sector.name() == null ? "" : sector.name());
            row.put("last", sector.last());
            row.put("changePercent", sector.changePercent());
            row.put("leader", sector.leader() == null ? "" : sector.leader());
            row.put("leaderPercent", sector.leaderPercent());
        }
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
