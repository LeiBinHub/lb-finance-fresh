package com.lb.financefresh.filing;

import com.lb.financefresh.llm.MediaArticleWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingPicturesTest {

    @Test
    void keepsAtMostThreeMatchingPictures() {
        List<FilingExtract.Picture> pictures = List.of(
                pic(1, "Cloud revenue"),
                pic(2, "Capex"),
                pic(3, "Ads"),
                pic(4, "Other"));
        var article = new MediaArticleWriter.Article(
                "云还在涨",
                "云收入还在加速。\n<<<IMG:1>>>\n\n资本开支太猛。\n<<<IMG:2>>>\n\n广告也不差。\n<<<IMG:3>>>\n\n多余的图。\n<<<IMG:4>>>",
                "Cloud still up",
                "Cloud is still accelerating.");
        var placed = FilingPictures.place(article, pictures);
        assertTrue(placed.bodyZh().contains("<<<IMG:1>>>"));
        assertTrue(placed.bodyZh().contains("<<<IMG:2>>>"));
        assertTrue(placed.bodyZh().contains("<<<IMG:3>>>"));
        assertFalse(placed.bodyZh().contains("<<<IMG:4>>>"));
        assertEquals(3, FilingPictures.used(placed, pictures).size());
        assertTrue(placed.bodyEn().contains("<<<IMG:1>>>"));
    }

    @Test
    void doesNotInsertPicturesTheModelDidNotChoose() {
        List<FilingExtract.Picture> pictures = List.of(
                pic(1, "Figure Cloud revenue 34%"),
                pic(2, "Exhibit capital expenditure"));
        var article = new MediaArticleWriter.Article(
                "看云",
                "云收入还在 34%。\n\n资本开支这个季度很猛。",
                "Cloud",
                "Cloud revenue still at 34%.\n\nCapex was heavy this quarter.");
        var placed = FilingPictures.place(article, pictures);
        assertFalse(placed.bodyZh().contains("<<<IMG:"));
        assertTrue(FilingPictures.used(placed, pictures).isEmpty());
    }

    private static FilingExtract.Picture pic(int id, String caption) {
        return new FilingExtract.Picture(id, "第" + id + "页", caption, "image/png", new byte[]{1}, 200, 120);
    }
}
