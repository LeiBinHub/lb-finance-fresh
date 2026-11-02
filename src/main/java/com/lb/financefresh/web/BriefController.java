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
import com.lb.financefresh.pipeline.FilingArchive;
import com.lb.financefresh.pipeline.FilingLinks;
import com.lb.financefresh.pipeline.FilingService;
import com.lb.financefresh.pipeline.MediaArchive;
import com.lb.financefresh.pipeline.MediaDeskService;
import com.lb.financefresh.pipeline.RunSources;
import com.lb.financefresh.push.PushFanout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
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
import java.util.ArrayList;
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
    private final FilingArchive filings;
    private final FilingService filingService;
    private final FilingLinks filingLinks;
    private final ChinaMarket chinaMarket;
    private final TreeNews treeNews;

    public BriefController(FreshSettings settings, BriefPipeline pipeline, PushFanout fanout, ExactDedup dedup,
                           BriefArchive archive, ApiFailures failures, RunSources sources, MacroHistory history,
                           FedNotices notices, ReleaseCalendar calendar, MediaArchive media, MediaDeskService mediaDesk,
                           FilingArchive filings, FilingService filingService, FilingLinks filingLinks,
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
        this.filings = filings;
        this.filingService = filingService;
        this.filingLinks = filingLinks;
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
    public MediaArchive.DayPage media(@RequestParam(required = false) String desk,
                                      @RequestParam(required = false) String day) throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of(settings.zone()));
        LocalDate selected = (day == null || day.isBlank()) ? today : parseDate(day);
        return media.page(MediaDesk.fromPath(desk), selected, today);
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

    @GetMapping("/api/filings")
    public List<FilingArchive.SavedFiling> filings() throws Exception {
        return filings.list();
    }

    @GetMapping("/api/filings/{name}")
    public Map<String, Object> filingOne(@PathVariable String name) throws Exception {
        return filingBody(name);
    }

    @GetMapping("/api/filings/{name}/images/{id}")
    public ResponseEntity<byte[]> filingImage(@PathVariable String name, @PathVariable int id) throws Exception {
        FilingArchive.StoredImage image = filings.image(name, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mime()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(image.bytes());
    }

    @GetMapping("/api/filing-links")
    public Map<String, Object> filingLinks(@RequestParam String ticker) throws Exception {
        return filingLinks.lookup(ticker);
    }

    @PostMapping("/api/filings")
    public Map<String, Object> generateFiling(@RequestParam("ticker") String[] tickers,
                                              @RequestParam("tenq") MultipartFile[] tenqs,
                                              @RequestParam("transcript") MultipartFile[] transcripts,
                                              @RequestParam("consensus") MultipartFile[] consensuses,
                                              @RequestParam(required = false) String note)
            throws Exception {
        FilingArchive.SavedFiling saved = filingService.generate(
                companyUploads(tickers, tenqs, transcripts, consensuses), note);
        return filingBody(saved.name());
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

    private Map<String, Object> filingBody(String name) throws Exception {
        FilingArchive.SavedFiling saved = filings.get(name);
        var article = filings.readArticle(name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", saved.name());
        body.put("date", saved.date());
        body.put("time", saved.time());
        body.put("title", saved.title());
        body.put("titleEn", saved.titleEn());
        body.put("titleZh", article.titleZh());
        body.put("bodyZh", article.bodyZh());
        body.put("bodyEn", article.bodyEn());
        body.put("originalName", saved.originalName());
        body.put("ticker", saved.ticker());
        body.put("article", filings.read(name));
        List<Map<String, Object>> images = new ArrayList<>();
        for (FilingArchive.StoredImage image : filings.images(name)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", image.id());
            row.put("place", image.place());
            row.put("caption", image.caption());
            row.put("url", "/api/filings/" + name + "/images/" + image.id());
            images.add(row);
        }
        body.put("images", images);
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

    static List<FilingService.CompanyUpload> companyUploads(String[] tickers, MultipartFile[] tenqs,
                                                            MultipartFile[] transcripts, MultipartFile[] consensuses) {
        int n = tickers == null ? 0 : tickers.length;
        if (n == 0) {
            throw new IllegalArgumentException("请至少填一家公司的标的，并上传 10-Q、电话会纪要、一致预期表");
        }
        if (n > FilingService.MAX_COMPANIES) {
            throw new IllegalArgumentException("一次最多对照 " + FilingService.MAX_COMPANIES + " 家公司");
        }
        if (!sameLen(n, tenqs) || !sameLen(n, transcripts) || !sameLen(n, consensuses)) {
            throw new IllegalArgumentException("每家公司都要上传 10-Q、电话会纪要、一致预期表");
        }
        List<FilingService.CompanyUpload> companies = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            companies.add(new FilingService.CompanyUpload(
                    tickers[i], tenqs[i], transcripts[i], consensuses[i]));
        }
        return companies;
    }

    private static boolean sameLen(int n, MultipartFile[] files) {
        return files != null && files.length == n;
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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(errorBody(new IllegalArgumentException("文件太大，请压缩到 20MB 以内")));
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
