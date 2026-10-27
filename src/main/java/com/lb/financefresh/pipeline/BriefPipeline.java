package com.lb.financefresh.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.dedup.EventCluster;
import com.lb.financefresh.dedup.ExactDedup;
import com.lb.financefresh.dedup.TitleKey;
import com.lb.financefresh.llm.BriefRenderer;
import com.lb.financefresh.llm.BriefWriter;
import com.lb.financefresh.llm.MediaArticleWriter;
import com.lb.financefresh.market.ApiFailures;
import com.lb.financefresh.market.MarketCollector;
import com.lb.financefresh.market.TreeNews;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.MarketSnapshot;
import com.lb.financefresh.model.NewsItem;
import com.lb.financefresh.model.SourceLink;
import com.lb.financefresh.push.PushFanout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class BriefPipeline {

    private static final Logger log = LoggerFactory.getLogger(BriefPipeline.class);
    private final ReentrantLock lock = new ReentrantLock();

    private final FreshSettings settings;
    private final MarketCollector collector;
    private final ExactDedup dedup;
    private final BriefWriter writer;
    private final BriefRenderer renderer;
    private final MediaArticleWriter mediaWriter;
    private final PushFanout fanout;
    private final BriefArchive archive;
    private final MediaArchive mediaArchive;
    private final TreeNews treeNews;
    private final ApiFailures failures;
    private final RunSources sources;

    public BriefPipeline(FreshSettings settings, MarketCollector collector, ExactDedup dedup,
                         BriefWriter writer, BriefRenderer renderer, MediaArticleWriter mediaWriter,
                         PushFanout fanout, BriefArchive archive, MediaArchive mediaArchive,
                         TreeNews treeNews, ApiFailures failures, RunSources sources) {
        this.settings = settings;
        this.collector = collector;
        this.dedup = dedup;
        this.writer = writer;
        this.renderer = renderer;
        this.mediaWriter = mediaWriter;
        this.fanout = fanout;
        this.archive = archive;
        this.mediaArchive = mediaArchive;
        this.treeNews = treeNews;
        this.failures = failures;
        this.sources = sources;
    }

    public BriefArchive.SavedBrief run(BriefKind kind) {
        return run(kind, LocalDate.now(ZoneId.of(settings.zone())), true);
    }

    public BriefArchive.SavedBrief run(BriefKind kind, LocalDate day) {
        return run(kind, day, false);
    }

    private BriefArchive.SavedBrief run(BriefKind kind, LocalDate day, boolean onlyUnseen) {
        if (!lock.tryLock()) throw new IllegalStateException("上一份简报还在生成");
        try {
            LocalDate today = LocalDate.now(ZoneId.of(settings.zone()));
            if (day.isAfter(today)) throw new IllegalArgumentException("还不能获取未来的日期");
            List<String> missing = settings.blockingMissing();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("还缺配置：" + String.join("，", missing));
            }
            failures.clear();
            sources.clear();
            dedup.ping();
            MarketSnapshot snapshot = collector.collect(kind, day);
            List<SourceLink> links = linksOf(snapshot.news());
            sources.set(links);
            List<NewsItem> fresh = onlyUnseen ? dedup.filterUnseen(snapshot.news()) : snapshot.news();
            List<NewsItem> chosen = EventCluster.shrink(
                    fresh,
                    Set.copyOf(settings.watchlist()),
                    settings.clusterTrigger(),
                    settings.clusterKeep());
            log.info("{} day={} collected={} fresh={} toModel={}", kind, day, snapshot.news().size(), fresh.size(), chosen.size());
            JsonNode model = writer.write(
                    kind,
                    day,
                    snapshot.yieldLine(),
                    snapshot.macroMonitor(),
                    snapshot.quotes(),
                    chosen,
                    snapshot.released());
            String text = renderer.render(
                    kind,
                    day,
                    snapshot.yieldLine(),
                    snapshot.macroMonitor(),
                    snapshot.quotes(),
                    snapshot.released(),
                    model);
            failures.addGaps(snapshot.gaps());
            BriefArchive.SavedBrief saved = archive.save(kind, day, text, links, failures.list());
            try {
                var dayFlashes = treeNews.forDay(day);
                log.info("media treeNews day={} count={}", day, dayFlashes.size());
                mediaArchive.save(saved.name(), mediaWriter.write(
                        kind,
                        day,
                        text,
                        snapshot.yieldLine(),
                        snapshot.macroMonitor(),
                        snapshot.quotes(),
                        chosen,
                        dayFlashes));
            } catch (Exception e) {
                log.warn("media article failed: {}", e.toString());
            }
            if (!fanout.push(kind.title() + " " + day, text)) {
                throw new IllegalStateException("推送都失败了，这次不记去重，下次会重试");
            }
            dedup.remember(fresh);
            return saved;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null ? "简报失败" : e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    private static List<SourceLink> linksOf(List<NewsItem> news) {
        Map<String, SourceLink> unique = new LinkedHashMap<>();
        for (NewsItem item : news) {
            String url = item.url().trim();
            String title = item.title().trim();
            if (title.isBlank() && url.isBlank()) continue;
            String key = url.isBlank() ? title : TitleKey.canonicalUrl(url);
            unique.putIfAbsent(key, new SourceLink(title.isBlank() ? url : title, url));
        }
        return new ArrayList<>(unique.values());
    }
}
