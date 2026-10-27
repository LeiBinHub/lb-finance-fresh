package com.lb.financefresh.market;

import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.CalendarEvent;
import com.lb.financefresh.model.MarketSnapshot;
import com.lb.financefresh.model.NewsItem;
import com.lb.financefresh.model.Quote;
import com.lb.financefresh.model.WatchLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MarketCollector {

    private static final Logger log = LoggerFactory.getLogger(MarketCollector.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final FreshSettings settings;
    private final FinnhubClient finnhub;
    private final AlpacaClient alpaca;
    private final AlphaVantageClient alphaVantage;
    private final FredClient fred;
    private final MacroMonitor macroMonitor;

    public MarketCollector(FreshSettings settings, FinnhubClient finnhub, AlpacaClient alpaca,
                           AlphaVantageClient alphaVantage, FredClient fred, MacroMonitor macroMonitor) {
        this.settings = settings;
        this.finnhub = finnhub;
        this.alpaca = alpaca;
        this.alphaVantage = alphaVantage;
        this.fred = fred;
        this.macroMonitor = macroMonitor;
    }

    public MarketSnapshot collect(BriefKind kind, LocalDate day) {
        ZoneId zone = ZoneId.of(settings.zone());
        LocalDate today = LocalDate.now(zone);
        if (day.isAfter(today)) throw new IllegalArgumentException("还不能获取未来的日期");
        boolean live = day.equals(today);
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant until = live ? Instant.now().plusSeconds(1) : day.plusDays(1).atStartOfDay(zone).toInstant();
        LocalDate newsFrom = from.atZone(NEW_YORK).toLocalDate();
        LocalDate newsTo = until.atZone(NEW_YORK).toLocalDate();
        LocalDate calendarFrom = live ? LocalDate.now(NEW_YORK).minusDays(1) : day.minusDays(1);
        LocalDate calendarTo = live ? LocalDate.now(NEW_YORK).plusDays(1) : day;
        List<String> symbols = settings.watchlist();
        List<String> gaps = new ArrayList<>();
        List<NewsItem> news = new ArrayList<>();

        for (String symbol : symbols) {
            if (WatchLabels.indexOnly(symbol)) continue;
            List<NewsItem> items;
            try {
                items = finnhub.companyNews(symbol, newsFrom, newsTo, from, until);
            } catch (Exception e) {
                log.warn("finnhub news {} failed: {}", symbol, e.toString());
                gaps.add("Finnhub 公司新闻 " + symbol + " 失败");
                continue;
            }
            double sentiment = 0;
            try {
                sentiment = finnhub.companySentiment(symbol);
            } catch (Exception e) {
                log.warn("finnhub sentiment {} failed: {}", symbol, e.toString());
            }
            for (NewsItem item : items) news.add(withSentiment(item, sentiment));
        }
        if (finnhub.sentimentDenied()) gaps.add("Finnhub 免费档没有公司情绪分，新闻仍会拉取");
        try {
            news.addAll(finnhub.marketNews(from, until));
        } catch (Exception e) {
            log.warn("finnhub market news failed: {}", e.toString());
            gaps.add("Finnhub 市场新闻失败");
        }
        try {
            news.addAll(alpaca.news(symbols, from, until));
        } catch (Exception e) {
            log.warn("alpaca news failed: {}", e.toString());
            gaps.add("Alpaca 新闻失败");
        }
        try {
            news.addAll(alphaVantage.news(symbols, from, until));
        } catch (Exception e) {
            log.warn("alphavantage news failed: {}", e.toString());
            gaps.add("Alpha Vantage 新闻失败");
        }
        if (!settings.finnhubReady()) gaps.add("未配置 FINNHUB_API_KEY，财经日历和财报会空着");
        if (live && !settings.alpacaReady() && kind == BriefKind.PREMARKET) {
            gaps.add("未配置 Alpaca。免费的 Finnhub / Alpha Vantage 报价是常规价，不是盘前成交价");
        }
        if (!live) gaps.add("选的不是今天，涨跌用的是新闻和日历，不是当时的成交价");

        Map<String, Quote> quotes = new LinkedHashMap<>();
        if (live) {
            boolean sawPremarket = false;
            try {
                for (Quote quote : alpaca.snapshots(symbols, kind)) {
                    quotes.put(quote.symbol(), quote);
                    if ("盘前".equals(quote.session())) sawPremarket = true;
                }
            } catch (Exception e) {
                log.warn("alpaca snapshots failed: {}", e.toString());
                gaps.add("Alpaca 报价失败");
            }
            if (settings.alpacaReady() && kind == BriefKind.PREMARKET && !sawPremarket) {
                gaps.add("Alpaca 行情还没有今天的盘前成交，涨跌用的是上一笔成交价");
            }
            boolean sawOvernight = quotes.values().stream().anyMatch(quote -> "夜盘".equals(quote.session()));
            if (settings.alpacaReady() && kind == BriefKind.NOW && AlpacaClient.inOvernightWindow(Instant.now()) && !sawOvernight) {
                gaps.add("Alpaca 夜盘 feed 还没有更新成交，涨跌用的是盘后或更早的价格");
            }
            for (String symbol : symbols) {
                if (quotes.containsKey(symbol)) continue;
                try {
                    Quote quote = finnhub.quote(symbol, kind);
                    if (quote != null) quotes.put(symbol, quote);
                } catch (Exception e) {
                    log.warn("finnhub quote {} failed: {}", symbol, e.toString());
                }
            }
        }

        String yieldLine = null;
        if (settings.fredReady()) {
            try {
                yieldLine = fred.yieldLine(day);
            } catch (Exception e) {
                log.warn("fred failed: {}", e.toString());
                gaps.add("FRED 收益率失败");
            }
        }
        if (yieldLine == null && settings.alphaVantageReady()) {
            try {
                yieldLine = alphaVantage.yieldLine(day);
            } catch (Exception e) {
                log.warn("alphavantage yields failed: {}", e.toString());
                gaps.add("Alpha Vantage 收益率失败");
            }
        }
        if (yieldLine == null) {
            yieldLine = "10Y 本次没有取到，2Y 本次没有取到。";
            if (!settings.alphaVantageReady() && !settings.fredReady()) gaps.add("未配置 FRED_API_KEY");
        }
        String monitor = settings.fredReady()
                ? macroMonitor.load(day, gaps)
                : "近期形势：未配置 FRED_API_KEY，五项指标这次不算。\n";

        List<CalendarEvent> calendar = new ArrayList<>();
        try {
            calendar.addAll(finnhub.economic(calendarFrom, calendarTo));
        } catch (Exception e) {
            log.warn("finnhub economic calendar failed: {}", e.toString());
            gaps.add("财经日历失败");
        }
        if (finnhub.economicDenied()) gaps.add("Finnhub 免费档没有经济日历，财报日历仍会拉取");
        try {
            calendar.addAll(finnhub.earnings(symbols, calendarFrom, calendarTo));
        } catch (Exception e) {
            log.warn("finnhub earnings calendar failed: {}", e.toString());
            gaps.add("财报日历失败");
        }
        List<CalendarEvent> released = new ArrayList<>();
        List<CalendarEvent> upcoming = new ArrayList<>();
        for (CalendarEvent event : calendar) {
            if (event.detail() != null && event.detail().contains("实际")) released.add(event);
            else upcoming.add(event);
        }
        return new MarketSnapshot(news, List.copyOf(quotes.values()), yieldLine, monitor, released, upcoming, gaps);
    }

    private static NewsItem withSentiment(NewsItem item, double sentiment) {
        if (item.sentiment() != 0 || sentiment == 0) return item;
        return new NewsItem(item.source(), item.title(), item.summary(), item.url(), item.symbols(), item.publishedAt(), sentiment);
    }
}
