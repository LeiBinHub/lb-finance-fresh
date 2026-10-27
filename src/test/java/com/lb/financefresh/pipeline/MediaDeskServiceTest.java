package com.lb.financefresh.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.llm.MediaArticleWriter;
import com.lb.financefresh.market.TreeNews;
import com.lb.financefresh.model.MediaDesk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaDeskServiceTest {

    @TempDir
    Path dir;

    @Test
    void generateRejectsUsMacroAndEmptyDay() {
        TreeNews news = new TreeNews(new FreshSettings(), null, dir.resolve("tree-news.json"));
        MediaArchive archive = new MediaArchive(dir.resolve("media"));
        MediaDeskService service = new MediaDeskService(
                new FreshSettings(), news, archive, new MediaArticleWriter(new FreshSettings(), null, new ObjectMapper()));
        assertThrows(IllegalArgumentException.class, () -> service.generate(MediaDesk.US_MACRO, LocalDate.of(2026, 10, 26)));
        IllegalStateException empty = assertThrows(
                IllegalStateException.class,
                () -> service.generate(MediaDesk.US_MICRO, LocalDate.of(2026, 10, 26)));
        assertTrue(empty.getMessage().contains("监听消息"));
    }

    @Test
    void flashesKeepOnlyDeskChannels(@TempDir Path root) throws Exception {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Path file = root.resolve("tree-news.json");
        List<TreeNews.Item> stored = List.of(
                item("TreeNewsFeed/1", "TreeNewsFeed", ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, zone), "CPI"),
                item("jin10light/2", "jin10light", ZonedDateTime.of(2026, 10, 26, 9, 1, 0, 0, zone), "国内新闻"),
                item("forexlive/3", "forexlive", ZonedDateTime.of(2026, 10, 26, 9, 2, 0, 0, zone), "USDJPY"));
        new ObjectMapper().writeValue(file.toFile(), stored);
        TreeNews news = new TreeNews(new FreshSettings(), null, file);
        MediaDeskService service = new MediaDeskService(
                new FreshSettings(), news, new MediaArchive(root.resolve("media")),
                new MediaArticleWriter(new FreshSettings(), null, new ObjectMapper()));
        List<TreeNews.Item> us = service.flashes(MediaDesk.US_MICRO, LocalDate.of(2026, 10, 26));
        List<TreeNews.Item> cn = service.flashes(MediaDesk.CN_MICRO, LocalDate.of(2026, 10, 26));
        assertEquals(List.of("TreeNewsFeed/1", "forexlive/3"), us.stream().map(TreeNews.Item::id).toList());
        assertEquals(List.of("jin10light/2"), cn.stream().map(TreeNews.Item::id).toList());
    }

    private static TreeNews.Item item(String id, String channel, ZonedDateTime at, String text) {
        return new TreeNews.Item(
                id,
                channel,
                at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                at.toInstant().toEpochMilli(),
                text,
                "",
                "https://t.me/" + id);
    }
}
