package com.lb.financefresh.web;

import com.lb.financefresh.config.DotEnv;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.dedup.ExactDedup;
import com.lb.financefresh.market.ApiFailures;
import com.lb.financefresh.market.FedNotices;
import com.lb.financefresh.market.MacroHistory;
import com.lb.financefresh.market.ReleaseCalendar;
import com.lb.financefresh.market.ChinaMarket;
import com.lb.financefresh.market.TreeNews;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.MediaDesk;
import com.lb.financefresh.pipeline.BriefArchive;
import com.lb.financefresh.pipeline.BriefPipeline;
import com.lb.financefresh.pipeline.MediaArchive;
import com.lb.financefresh.pipeline.MediaDeskService;
import com.lb.financefresh.pipeline.RunSources;
import com.lb.financefresh.push.PushFanout;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class BriefController {

    private final FreshSettings settings;
    private final BriefPipeline pipeline;
    private final PushFanout fanout;
    private final ExactDedup dedup;
    private final BriefArchive archive;
    private final ApiFailures failures;
    private final RunSources sources;
    private final MacroHistory history;
    private final FedNotices notices;
    private final ReleaseCalendar calendar;
    private final MediaArchive media;
    private final MediaDeskService mediaDesk;
    private final ChinaMarket chinaMarket;
    private final TreeNews treeNews;

    public BriefController(FreshSettings settings, BriefPipeline pipeline, PushFanout fanout, ExactDedup dedup,
                           BriefArchive archive, ApiFailures failures, RunSources sources, MacroHistory history,
                           FedNotices notices, ReleaseCalendar calendar, MediaArchive media, MediaDeskService mediaDesk,
                           ChinaMarket chinaMarket, TreeNews treeNews) {
        this.settings = settings;
        this.pipeline = pipeline;
        this.fanout = fanout;
        this.dedup = dedup;
        this.archive = archive;
        this.failures = failures;
        this.sources = sources;
        this.history = history;
        this.notices = notices;
        this.calendar = calendar;
        this.media = media;
        this.mediaDesk = mediaDesk;
        this.chinaMarket = chinaMarket;
        this.treeNews = treeNews;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("watchlist", settings.watchlist());
        body.put("push", fanout.enabled());
        body.put("requiredMissing", settings.requiredMissing());
        body.put("finnhub", settings.finnhubReady());
        body.put("alphavantage", settings.alphaVantageReady());
        body.put("alpaca", settings.alpacaReady());
        body.put("fred", settings.fredReady());
        body.put("deepseek", settings.deepseekReady());
        body.put("treeNews", settings.telegramReady());
        body.put("treeNewsChannels", treeNews.feeds().stream().map(TreeNews.Feed::handle).toList());
        body.put("redisHost", DotEnv.get("REDIS_HOST").isBlank() ? "127.0.0.1" : DotEnv.get("REDIS_HOST"));
        try {
            dedup.ping();
            body.put("redis", "up");
        } catch (Exception e) {
            body.put("redis", "down");
        }
        return body;
    }

    @GetMapping("/api/macro/history")
    public Map<String, List<MacroHistory.Reading>> history() throws Exception {
        Map<String, List<MacroHistory.Reading>> body = new LinkedHashMap<>();
        history.charts().forEach((series, points) -> body.put(series, points.stream()
                .map(point -> new MacroHistory.Reading(point.date().toString(), point.value()))
                .toList()));
        return body;
    }

    @GetMapping("/api/fred/releases")
    public List<ReleaseCalendar.Item> releases() throws Exception {
        return calendar.current();
    }

    @GetMapping("/api/logs")
    public List<ApiFailures.FailedCall> logs() {
        return failures.recent();
    }

    @GetMapping("/api/fed/notices")
    public List<FedNotices.Section> fed() {
        return notices.latest();
    }

    @GetMapping("/api/tree-news")
    public Map<String, Object> treeNews(@RequestParam(required = false) String day) {
        LocalDate today = LocalDate.now(ZoneId.of(settings.zone()));
        LocalDate selected = (day == null || day.isBlank()) ? today : parseDate(day);
        if (selected.isAfter(today)) selected = today;
        List<String> days = treeNews.days();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", treeNews.enabled());
        body.put("pulledAt", treeNews.pulledAt());
        body.put("error", treeNews.lastError());
        body.put("today", today.toString());
        body.put("day", selected.toString());
        body.put("days", days);
        body.put("prevDay", treeNews.previousDay(selected));
        body.put("nextDay", treeNews.nextDay(selected));
        body.put("page", days.isEmpty() ? 0 : days.indexOf(selected.toString()) + 1);
        body.put("pages", days.size());
        body.put("channels", treeNews.channels(selected));
        return body;
    }

    @PostMapping("/api/tree-news/ingest")
    public Map<String, Object> ingest(@RequestBody List<TreeNews.Flash> flashes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("added", treeNews.ingest(flashes == null ? List.of() : flashes));
        return body;
    }

    @GetMapping("/api/china-quotes")
    public Map<String, Object> chinaQuotes() {
        return chinaMarketBody();
    }

    @GetMapping("/api/china-market")
    public Map<String, Object> chinaMarket() {
        return chinaMarketBody();
    }

    private Map<String, Object> chinaMarketBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            var snapshot = chinaMarket.latest();
            putSnapshot(body, snapshot);
            if (snapshot.quotes().isEmpty() && snapshot.error().isBlank()) {
                body.put("error", "腾讯行情还没有返回数字");
            }
        } catch (Exception e) {
            putSnapshot(body, ChinaMarket.Snapshot.empty());
            body.put("error", e.getMessage() == null ? "中国市场失败" : e.getMessage());
        }
        body.put("pulledAt", ZonedDateTime.now(ZoneId.of(settings.zone()))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return body;
    }

    private static void putSnapshot(Map<String, Object> body, ChinaMarket.Snapshot snapshot) {
        ChinaMarket.Snapshot board = snapshot == null ? ChinaMarket.Snapshot.empty() : snapshot;
        body.put("quotes", board.quotes());
        body.put("indexFlows", board.indexFlows());
        body.put("industryIn", board.industryIn());
        body.put("industryOut", board.industryOut());
        body.put("industriesUp", board.industriesUp());
        body.put("industriesDown", board.industriesDown());
        body.put("conceptsUp", board.conceptsUp());
        body.put("error", board.error());
    }

    @GetMapping("/api/media")
    public List<MediaArchive.SavedArticle> media(@RequestParam(required = false) String desk) throws Exception {
        return media.list(MediaDesk.fromPath(desk));
    }

    @GetMapping("/api/media/{name}")
    public Map<String, Object> mediaOne(@PathVariable String name) throws Exception {
        return mediaBody(name);
    }

    @PostMapping("/api/media/desks/{desk}")
    public Map<String, Object> generateMedia(@PathVariable String desk,
                                             @RequestParam(required = false) String day,
                                             @RequestParam(required = false) String date)
            throws Exception {
        String raw = (day == null || day.isBlank()) ? date : day;
        LocalDate selected = raw == null || raw.isBlank()
                ? LocalDate.now(ZoneId.of(settings.zone()))
                : parseDate(raw);
        MediaArchive.SavedArticle saved = mediaDesk.generate(MediaDesk.fromPath(desk), selected);
        return mediaBody(saved.name());
    }

    @GetMapping("/api/archive/dates")
    public List<String> dates() throws Exception {
        return archive.dates();
    }

    @GetMapping("/api/archive")
    public List<BriefArchive.SavedBrief> onDate(@RequestParam String date) throws Exception {
        return archive.on(parseDate(date));
    }

    @GetMapping("/api/archive/{name}")
    public Map<String, Object> read(@PathVariable String name) throws Exception {
        BriefArchive.SavedBrief saved = BriefArchive.describe(name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", saved.name());
        body.put("date", saved.date());
        body.put("title", saved.title());
        body.put("brief", archive.read(name));
        body.put("sources", archive.sources(name));
        body.put("failures", archive.failures(name));
        return body;
    }

    @PostMapping("/api/briefs/{kind}")
    public Map<String, Object> run(@PathVariable String kind, @RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank()
                ? LocalDate.now(ZoneId.of(settings.zone()))
                : parseDate(date);
        BriefArchive.SavedBrief saved = pipeline.run(BriefKind.fromPath(kind), day);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", saved.name());
        body.put("date", saved.date());
        body.put("title", saved.title());
        body.put("brief", archiveText(saved.name()));
        body.put("sources", sources.list());
        body.put("failures", failures.list());
        return body;
    }

    private Map<String, Object> mediaBody(String name) throws Exception {
        MediaArchive.SavedArticle saved = media.get(name);
        var article = media.readArticle(name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", saved.name());
        body.put("desk", saved.desk());
        body.put("date", saved.date());
        body.put("time", saved.time());
        body.put("title", saved.title());
        body.put("titleEn", saved.titleEn());
        body.put("titleZh", article.titleZh());
        body.put("bodyZh", article.bodyZh());
        body.put("bodyEn", article.bodyEn());
        body.put("article", media.read(name));
        return body;
    }

    private String archiveText(String name) {
        try {
            return archive.read(name);
        } catch (Exception e) {
            throw new IllegalStateException("简报已生成，但读回失败");
        }
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期要写成 2026-10-15");
        }
    }
}

@RestControllerAdvice
class ApiErrors {

    private final ApiFailures failures;
    private final RunSources sources;

    ApiErrors(ApiFailures failures, RunSources sources) {
        this.failures = failures;
        this.sources = sources;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(errorBody(e));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(503).body(errorBody(e));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> failed(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        failures.addNote(message, "本机请求");
        return ResponseEntity.status(503).body(errorBody(e));
    }

    private Map<String, Object> errorBody(Exception e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage() == null ? "请求失败" : e.getMessage());
        body.put("sources", sources.list());
        body.put("failures", failures.recent());
        return body;
    }
}
