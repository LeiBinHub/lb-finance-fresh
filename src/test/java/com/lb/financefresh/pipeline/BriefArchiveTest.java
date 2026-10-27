package com.lb.financefresh.pipeline;

import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.model.BriefKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BriefArchiveTest {

    @TempDir
    Path dir;

    @Test
    void savesUnderBeijingDateAndReadsItBack() throws Exception {
        FreshSettings settings = new FreshSettings();
        BriefArchive archive = new BriefArchive(settings, dir);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        archive.save(BriefKind.PREMARKET, today, "盘前正文");
        assertEquals(1, archive.dates().size());
        assertEquals(today.toString(), archive.dates().getFirst());
        assertEquals(1, archive.on(today).size());
        assertTrue(archive.on(today).getFirst().title().startsWith("盘前简报"));
        assertEquals("盘前正文", archive.read(archive.on(today).getFirst().name()));
        assertTrue(archive.on(today.minusDays(1)).isEmpty());
    }

    @Test
    void filesAPastDayUnderThatDay() throws Exception {
        BriefArchive archive = new BriefArchive(new FreshSettings(), dir);
        LocalDate day = LocalDate.of(2026, 10, 10);
        archive.save(BriefKind.RECAP, day, "复盘正文");
        assertEquals(List.of("2026-10-10"), archive.dates());
        assertEquals("复盘正文", archive.read(archive.on(day).getFirst().name()));
    }

    @Test
    void storesNumberedSourcesBesideTheBrief() throws Exception {
        BriefArchive archive = new BriefArchive(new FreshSettings(), dir);
        LocalDate day = LocalDate.of(2026, 10, 12);
        var saved = archive.save(
                BriefKind.PREMARKET,
                day,
                "正文",
                List.of(new com.lb.financefresh.model.SourceLink("英伟达上调指引", "https://news.example/nvda")),
                List.of());
        assertTrue(Files.isRegularFile(dir.resolve(saved.name().replace(".md", ".sources.json"))));
        assertEquals("英伟达上调指引", archive.sources(saved.name()).getFirst().title());
        assertEquals("https://news.example/nvda", archive.sources(saved.name()).getFirst().url());
        assertTrue(archive.failures(saved.name()).isEmpty());
    }

    @Test
    void rejectsNamesOutsideTheArchive() {
        assertThrows(IllegalArgumentException.class, () -> BriefArchive.describe("../secret.md"));
        assertThrows(IllegalArgumentException.class, () -> BriefArchive.describe("premarket-20261015.md"));
    }

    @Test
    void savesALiveBriefAsNow() throws Exception {
        BriefArchive archive = new BriefArchive(new FreshSettings(), dir);
        LocalDate day = LocalDate.of(2026, 10, 16);
        var saved = archive.save(BriefKind.NOW, day, "时报正文");
        assertTrue(saved.name().startsWith("now-"));
        assertTrue(saved.title().startsWith("当前时报"));
        assertEquals("时报正文", archive.read(saved.name()));
    }
}
