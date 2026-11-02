package com.lb.financefresh.filing;

import com.lb.financefresh.llm.MediaArticleWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingShotsTest {

    @Test
    void skipsNumberDumpsAndKeepsJudgment() {
        assertEquals(0, FilingShots.score(
                "微软新分部叫 Agents and Infra，收入 76.8B，同比 25%，经营利润率 53%；Cloud 63.9B，增速 30%，Azure 48%，Foundry 翻三倍。M365 17%，E3 偏弱。Devices 15.5B，同比 -3%，利润率 35%；Search +12%，Xbox -2%，Windows OEM -27%。"));
        assertTrue(FilingShots.score("盘后把云的定价打回去了，不是差一两个点的事。") > 0);
        List<Integer> picks = FilingShots.pick(List.of(
                "盘后把云的定价打回去了，不是差一两个点的事。",
                "微软新分部叫 Agents and Infra，收入 76.8B，同比 25%，经营利润率 53%；Cloud 63.9B，增速 30%，Azure 48%。",
                "后面就盯 capex 还敢不敢加，FCF 还剩多少。"));
        assertFalse(picks.isEmpty());
        assertTrue(picks.contains(0));
        assertFalse(picks.contains(1));
    }

    @Test
    void fillsArticleWithReadableShotsWhenNoFilingPictures() {
        var article = new MediaArticleWriter.Article(
                "云涨了股价先摔",
                "盘后把云的定价打回去了，不是差一两个点的事。\n\n"
                        + "微软新分部叫 Agents and Infra，收入 76.8B，同比 25%，经营利润率 53%；Cloud 63.9B，增速 30%，Azure 48%，Foundry 翻三倍。\n\n"
                        + "谷歌云翻倍，但市场先砸的是广告能不能把 AI 账单赚回来。\n\n"
                        + "后面就盯 capex 还敢不敢加，FCF 还剩多少。",
                "Cloud popped, stock dropped",
                "After hours repriced the cloud story.\n\nGoogle cloud doubled, but ads have to pay the bill.");
        FilingShots.Filled filled = FilingShots.fill(article);
        assertFalse(filled.pictures().isEmpty());
        assertTrue(filled.pictures().size() <= 3);
        assertTrue(filled.article().bodyZh().contains("<<<IMG:1>>>"));
        assertTrue(filled.article().bodyEn().contains("<<<IMG:1>>>"));
        FilingExtract.Picture shot = filled.pictures().getFirst();
        assertEquals("image/png", shot.mime());
        assertTrue(shot.bytes().length > 1000);
        assertEquals("正文摘录", shot.place());
    }

    @Test
    void clipsLongQuotes() {
        String longText = "盘后把云的定价打回去了，".repeat(20);
        assertTrue(FilingShots.clip(longText).length() <= 111);
        assertTrue(FilingShots.clip(longText).endsWith("…"));
    }
}
