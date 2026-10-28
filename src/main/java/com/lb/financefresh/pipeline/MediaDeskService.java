package com.lb.financefresh.pipeline;

import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.llm.MediaArticleWriter;
import com.lb.financefresh.market.ChinaMarket;
import com.lb.financefresh.market.TreeNews;
import com.lb.financefresh.model.MediaDesk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MediaDeskService {

    static final int PER_CHANNEL = 40;
    static final int MAX_FLASHES = 120;

    private static final Logger log = LoggerFactory.getLogger(MediaDeskService.class);
    private final ReentrantLock lock = new ReentrantLock();
    private final FreshSettings settings;
    private final TreeNews treeNews;
    private final MediaArchive media;
    private final MediaArticleWriter writer;
    private final ChinaMarket chinaMarket;

    MediaDeskService(FreshSettings settings, TreeNews treeNews, MediaArchive media, MediaArticleWriter writer) {
        this(settings, treeNews, media, writer, null);
    }

    @Autowired
    public MediaDeskService(FreshSettings settings, TreeNews treeNews, MediaArchive media, MediaArticleWriter writer,
                            ChinaMarket chinaMarket) {
        this.settings = settings;
        this.treeNews = treeNews;
        this.media = media;
        this.writer = writer;
        this.chinaMarket = chinaMarket;
    }

    public MediaArchive.SavedArticle generate(MediaDesk desk, LocalDate day) {
        if (desk == null || desk == MediaDesk.US_MACRO) {
            throw new IllegalArgumentException("美国宏观由获取时报自动生成");
        }
        LocalDate today = LocalDate.now(ZoneId.of(settings.zone()));
        if (day == null) day = today;
        if (day.isAfter(today)) throw new IllegalArgumentException("还不能获取未来的日期");
        if (!lock.tryLock()) throw new IllegalStateException("上一篇媒体文章还在生成");
        try {
            List<TreeNews.Item> flashes = flashes(desk, day);
            if (flashes.isEmpty()) {
                throw new IllegalStateException("这一天还没有可写的" + desk.title() + "监听消息");
            }
            MediaArticleWriter.Article source = desk == MediaDesk.US_MICRO ? latestMacro(day) : null;
            ChinaMarket.Snapshot market = desk == MediaDesk.CN_MICRO
                    ? liveChinaMarket(day, today)
                    : ChinaMarket.Snapshot.empty();
            MediaArticleWriter.Article article = writer.writeDesk(desk, day, source, flashes, market);
            String clock = ZonedDateTime.now(ZoneId.of(settings.zone())).format(DateTimeFormatter.ofPattern("HHmm"));
            return media.saveGenerated(desk, day, clock, article);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null ? "媒体文章失败" : e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    List<TreeNews.Item> flashes(MediaDesk desk, LocalDate day) {
        List<TreeNews.Item> out = new ArrayList<>();
        for (String handle : desk.channels()) {
            List<TreeNews.Item> rows = treeNews.forDay(handle, day);
            out.addAll(rows.subList(0, Math.min(PER_CHANNEL, rows.size())));
        }
        out.sort(Comparator.comparingLong(TreeNews.Item::epoch).reversed());
        if (out.size() > MAX_FLASHES) out = new ArrayList<>(out.subList(0, MAX_FLASHES));
        out.sort(Comparator.comparingLong(TreeNews.Item::epoch));
        return out;
    }

    private ChinaMarket.Snapshot liveChinaMarket(LocalDate day, LocalDate today) {
        if (chinaMarket == null || day == null || !day.equals(today)) return ChinaMarket.Snapshot.empty();
        try {
            return chinaMarket.latest();
        } catch (Exception e) {
            log.warn("China market snapshot failed: {}", e.toString());
            return ChinaMarket.Snapshot.empty();
        }
    }

    private MediaArticleWriter.Article latestMacro(LocalDate day) throws Exception {
        String date = day.toString();
        var saved = media.list(MediaDesk.US_MACRO).stream()
                .filter(item -> date.equals(item.date()))
                .findFirst();
        if (saved.isEmpty()) return null;
        return media.readArticle(saved.get().name());
    }
}
