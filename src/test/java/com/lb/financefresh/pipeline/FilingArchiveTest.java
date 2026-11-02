package com.lb.financefresh.pipeline;

import com.lb.financefresh.filing.FilingExtract;
import com.lb.financefresh.llm.MediaArticleWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingArchiveTest {

    @TempDir
    Path dir;

    @Test
    void savesChineseAndEnglishCopiesWithFileName() throws Exception {
        FilingArchive archive = new FilingArchive(dir);
        var saved = archive.save(
                LocalDate.of(2026, 10, 29),
                "1132",
                "GOOGL-10Q.pdf",
                "GOOGL",
                "看云",
                new MediaArticleWriter.Article("谷歌云还在扛", "中文正文。", "Cloud still carrying", "English body."));
        assertEquals("filing-20261029-1132.md", saved.name());
        assertEquals("谷歌云还在扛", saved.title());
        assertEquals("GOOGL-10Q.pdf", saved.originalName());
        assertEquals("GOOGL", saved.ticker());
        MediaArticleWriter.Article loaded = archive.readArticle(saved.name());
        assertEquals("中文正文。", loaded.bodyZh());
        assertTrue(archive.read(saved.name()).contains("<<<EN>>>"));
        assertEquals(1, archive.list().size());
        assertEquals("GOOGL", archive.list().getFirst().ticker());
    }

    @Test
    void savesSelectedPicturesBesideTheArticle() throws Exception {
        FilingArchive archive = new FilingArchive(dir);
        byte[] png = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4 };
        var saved = archive.save(
                LocalDate.of(2026, 10, 29),
                "1148",
                "GOOGL-10Q.pdf",
                "GOOGL",
                "",
                new MediaArticleWriter.Article("云", "正文\n<<<IMG:1>>>", "Cloud", "Body\n<<<IMG:1>>>"),
                List.of(new FilingExtract.Picture(1, "第3页", "Cloud chart", "image/png", png, 220, 140)));
        assertEquals(1, archive.images(saved.name()).size());
        assertEquals("第3页", archive.images(saved.name()).getFirst().place());
        assertEquals(png.length, archive.image(saved.name(), 1).bytes().length);
        assertTrue(Files.isRegularFile(dir.resolve("filing-20261029-1148").resolve("1.png")));
    }
}
