package com.lb.financefresh.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.market.TreeNews;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.MediaDesk;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaArticleWriterTest {

    @Test
    void parseKeepsChineseAndEnglishCopies() {
        MediaArticleWriter.Article article = MediaArticleWriter.parse("""
                <<<ZH>>>
                # 美债回升后的三小时观察
                
                开盘前流动性先收紧，再看科技股。
                
                <<<EN>>>
                # Three Hours After Yields Jump
                
                Liquidity tightened before the open.
                """);
        assertEquals("美债回升后的三小时观察", article.titleZh());
        assertTrue(article.bodyZh().contains("开盘前流动性"));
        assertEquals("Three Hours After Yields Jump", article.titleEn());
        assertTrue(article.bodyEn().contains("Liquidity tightened"));
    }

    @Test
    void parseSkipsRepeatedMarkersAndDoesNotTreatThemAsTitle() {
        MediaArticleWriter.Article article = MediaArticleWriter.parse("""
                <<<ZH>>>
                # <<<ZH>>>

                AI融资把盘面拉回来了

                今天开盘前和开盘后像两套剧本。

                <<<EN>>>
                #


                """);
        assertEquals("AI融资把盘面拉回来了", article.titleZh());
        assertTrue(article.bodyZh().contains("今天开盘前"));
        assertEquals("US Market Watch", article.titleEn());
        assertTrue(article.bodyEn().isBlank());
        assertThrows(IllegalStateException.class, () -> MediaArticleWriter.requireComplete(article));
    }

    @Test
    void parseChineseOnlyDoesNotUseTheMarkerAsTitle() {
        MediaArticleWriter.Article article = MediaArticleWriter.parse("""
                <<<ZH>>>
                AI融资把盘面拉回来了

                今天开盘前和开盘后像两套剧本。
                """);
        assertEquals("AI融资把盘面拉回来了", article.titleZh());
        assertTrue(article.bodyZh().contains("今天开盘前"));
        assertEquals("", article.titleEn());
        assertThrows(IllegalStateException.class, () -> MediaArticleWriter.requireComplete(article));
    }

    @Test
    void userPromptPutsOnlyThePassedTreeNews() throws Exception {
        MediaArticleWriter writer = new MediaArticleWriter(new FreshSettings(), null, new ObjectMapper());
        TreeNews.Item flash = new TreeNews.Item(
                "TreeNewsFeed/1",
                "TreeNewsFeed",
                "2026-10-23 09:00:00",
                1L,
                "U.S. CPI: +3.7%",
                "美国CPI同比+3.7%",
                "https://t.me/TreeNewsFeed/1");
        String payload = writer.userPrompt(
                BriefKind.NOW,
                LocalDate.of(2026, 10, 23),
                "brief",
                "",
                "",
                List.of(),
                List.of(),
                List.of(flash));
        JsonNode root = new ObjectMapper().readTree(payload);
        assertEquals("2026-10-23", root.path("day").asText());
        assertEquals(1, root.path("treeNews").size());
        assertEquals("2026-10-23 09:00:00", root.path("treeNews").get(0).path("at").asText());
        assertEquals("U.S. CPI: +3.7%", root.path("treeNews").get(0).path("text").asText());
        assertEquals("美国CPI同比+3.7%", root.path("treeNews").get(0).path("zh").asText());
        assertEquals("TreeNewsFeed", root.path("treeNews").get(0).path("channel").asText());
        assertEquals("宏观与加密快讯", root.path("treeNews").get(0).path("channelZh").asText());
        assertTrue(root.path("treeNews").get(0).path("url").asText().contains("TreeNewsFeed/1"));
    }

    @Test
    void userPromptLeavesTreeNewsEmptyWhenThatDayHasNone() throws Exception {
        MediaArticleWriter writer = new MediaArticleWriter(new FreshSettings(), null, new ObjectMapper());
        String payload = writer.userPrompt(
                BriefKind.NOW,
                LocalDate.of(2026, 10, 23),
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of());
        JsonNode root = new ObjectMapper().readTree(payload);
        assertTrue(root.path("treeNews").isArray());
        assertEquals(0, root.path("treeNews").size());
    }

    @Test
    void userPromptDeskKeepsMacroArticleAndOnlyPassedFlashes() throws Exception {
        MediaArticleWriter writer = new MediaArticleWriter(new FreshSettings(), null, new ObjectMapper());
        TreeNews.Item flash = new TreeNews.Item(
                "WalterBloomberg/9",
                "WalterBloomberg",
                "2026-10-26 09:00:00",
                1L,
                "SPX futures jump",
                "标普期货跳升",
                "https://t.me/WalterBloomberg/9");
        MediaArticleWriter.Article macro = new MediaArticleWriter.Article(
                "宏观标题", "宏观正文", "Macro title", "Macro body");
        String payload = writer.userPromptDesk(
                com.lb.financefresh.model.MediaDesk.US_MICRO,
                LocalDate.of(2026, 10, 26),
                macro,
                List.of(flash));
        JsonNode root = new ObjectMapper().readTree(payload);
        assertEquals("us-micro", root.path("desk").asText());
        assertEquals("宏观标题", root.path("macroArticle").path("titleZh").asText());
        assertEquals("宏观正文", root.path("macroArticle").path("bodyZh").asText());
        assertEquals(1, root.path("treeNews").size());
        assertEquals("WalterBloomberg", root.path("treeNews").get(0).path("channel").asText());
        assertEquals(0, root.path("quotes").size());
    }

    @Test
    void userPromptDeskPutsTencentQuotesForChinaMicro() throws Exception {
        MediaArticleWriter writer = new MediaArticleWriter(new FreshSettings(), null, new ObjectMapper());
        String payload = writer.userPromptDesk(
                com.lb.financefresh.model.MediaDesk.CN_MICRO,
                LocalDate.of(2026, 10, 26),
                null,
                List.of(),
                List.of(new com.lb.financefresh.market.ChinaQuote(
                        "sh000001", "上证指数", 3685.65, 3690.48, -4.83, -0.13, "2026-10-26 13:53:45")));
        JsonNode root = new ObjectMapper().readTree(payload);
        assertEquals("cn-micro", root.path("desk").asText());
        assertEquals(1, root.path("quotes").size());
        assertEquals("上证指数", root.path("quotes").get(0).path("name").asText());
        assertEquals(3685.65, root.path("quotes").get(0).path("last").asDouble(), 0.001);
        assertEquals(-0.13, root.path("quotes").get(0).path("changePercent").asDouble(), 0.001);
    }

    @Test
    void allDesksShareHumanVoiceRulesAndKeepDeskFacts() {
        String voice = MediaArticleWriter.voiceRules();
        assertTrue(voice.contains("写法人味"));
        assertTrue(voice.contains("微信长文"));
        assertTrue(voice.contains("盘面速览"));
        assertTrue(voice.contains("这不是X而是Y"));
        for (MediaDesk desk : MediaDesk.values()) {
            String prompt = MediaArticleWriter.systemPrompt(desk);
            assertTrue(prompt.contains("写法人味"), desk.id());
            assertTrue(prompt.contains("<<<ZH>>>"), desk.id());
            assertTrue(prompt.contains("<<<EN>>>"), desk.id());
        }
        assertTrue(MediaArticleWriter.systemPrompt(MediaDesk.US_MICRO).contains("Unusual Whales"));
        assertTrue(MediaArticleWriter.systemPrompt(MediaDesk.CN_MICRO).contains("腾讯财经最新价"));
        assertTrue(MediaArticleWriter.systemPrompt(MediaDesk.US_MACRO).contains("premarket"));
        assertTrue(MediaArticleWriter.systemPrompt(MediaDesk.US_MACRO).contains("不要开篇把指数和个股报价念一遍"));
    }
}
