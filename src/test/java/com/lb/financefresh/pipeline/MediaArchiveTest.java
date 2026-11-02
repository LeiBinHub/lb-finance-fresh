package com.lb.financefresh.pipeline;

import com.lb.financefresh.llm.MediaArticleWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaArchiveTest {

    @TempDir
    Path dir;

    @Test
    void savesChineseAndEnglishCopies() throws Exception {
        MediaArchive archive = new MediaArchive(dir);
        var saved = archive.save(
                "now-20261016-1130.md",
                new MediaArticleWriter.Article("夜盘观察", "中文正文。", "After-Hours Watch", "English body."));
        assertEquals("now-20261016-1130.md", saved.name());
        assertEquals("夜盘观察", saved.title());
        assertEquals("After-Hours Watch", saved.titleEn());
        MediaArticleWriter.Article loaded = archive.readArticle(saved.name());
        assertEquals("中文正文。", loaded.bodyZh());
        assertEquals("English body.", loaded.bodyEn());
        assertTrue(archive.read(saved.name()).contains("<<<EN>>>"));
        assertEquals("us-macro", saved.desk());
        assertEquals(1, archive.list().size());
        assertEquals(1, archive.list(com.lb.financefresh.model.MediaDesk.US_MACRO).size());
    }

    @Test
    void listsGeneratedDesksSeparately() throws Exception {
        MediaArchive archive = new MediaArchive(dir);
        archive.save(
                "now-20261016-1130.md",
                new MediaArticleWriter.Article("宏观稿", "宏观正文。", "Macro", "Macro body."));
        var micro = archive.saveGenerated(
                com.lb.financefresh.model.MediaDesk.US_MICRO,
                java.time.LocalDate.of(2026, 10, 16),
                "1200",
                new MediaArticleWriter.Article("微观稿", "微观正文。", "Micro", "Micro body."));
        var china = archive.saveGenerated(
                com.lb.financefresh.model.MediaDesk.CN_MICRO,
                java.time.LocalDate.of(2026, 10, 16),
                "1205",
                new MediaArticleWriter.Article("金十解读", "国内正文。", "China", "China body."));
        assertEquals("us-micro-20261016-1200.md", micro.name());
        assertEquals("us-micro", micro.desk());
        assertEquals(1, archive.list().size());
        assertEquals(1, archive.list(com.lb.financefresh.model.MediaDesk.US_MICRO).size());
        assertEquals(1, archive.list(com.lb.financefresh.model.MediaDesk.CN_MICRO).size());
        assertEquals("微观稿", archive.list(com.lb.financefresh.model.MediaDesk.US_MICRO).getFirst().title());
        assertEquals("金十解读", china.title());
    }

    @Test
    void pagesTitlesBySelectedDay() throws Exception {
        MediaArchive archive = new MediaArchive(dir);
        archive.save(
                "now-20261016-1130.md",
                new MediaArticleWriter.Article("十六号上午", "上午。", "Morning", "Morning body."));
        archive.save(
                "recap-20261016-1600.md",
                new MediaArticleWriter.Article("十六号下午", "下午。", "Afternoon", "Afternoon body."));
        archive.save(
                "now-20261017-0900.md",
                new MediaArticleWriter.Article("十七号", "次日。", "Next", "Next body."));
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 18);
        MediaArchive.DayPage sixteenth = archive.page(
                com.lb.financefresh.model.MediaDesk.US_MACRO,
                java.time.LocalDate.of(2026, 10, 16),
                today);
        assertEquals("2026-10-16", sixteenth.day());
        assertEquals(List.of("2026-10-17", "2026-10-16"), sixteenth.days());
        assertEquals(2, sixteenth.items().size());
        assertEquals("十六号下午", sixteenth.items().getFirst().title());
        assertEquals("2026-10-17", sixteenth.nextDay());
        assertEquals("", sixteenth.prevDay());
        MediaArchive.DayPage empty = archive.page(
                com.lb.financefresh.model.MediaDesk.US_MACRO,
                today,
                today);
        assertEquals("2026-10-18", empty.day());
        assertTrue(empty.items().isEmpty());
        assertEquals("2026-10-17", empty.prevDay());
        assertEquals("", empty.nextDay());
        assertEquals(1, archive.list(com.lb.financefresh.model.MediaDesk.US_MACRO, java.time.LocalDate.of(2026, 10, 17)).size());
    }

    @Test
    void listsRealTitleWhenFileRepeatsZhMarker() throws Exception {
        MediaArchive archive = new MediaArchive(dir);
        Files.writeString(dir.resolve("now-20261026-2356.md"), """
                <<<ZH>>>
                # <<<ZH>>>

                AI融资把盘面拉回来了

                今天开盘前和开盘后像两套剧本。

                <<<EN>>>
                #


                """);
        assertEquals("AI融资把盘面拉回来了", archive.list().getFirst().title());
        assertTrue(archive.readArticle("now-20261026-2356.md").bodyZh().contains("今天开盘前"));
    }
}
