package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FedNoticesTest {

    @Test
    void rssKeepsTheNewestItemsAndStripsMarkup() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                <item>
                  <title>Treasury convenience</title>
                  <link>https://fred.stlouisfed.org/blog/one</link>
                  <pubDate>Thu, 15 Oct 2026 08:00:00 -0500</pubDate>
                  <description><![CDATA[<p>AAA &amp; Treasuries</p>]]></description>
                </item>
                <item>
                  <title>Older</title>
                  <link>https://fred.stlouisfed.org/blog/two</link>
                  <description>second</description>
                </item>
                </channel></rss>
                """;
        List<FedNotices.Parsed> items = FedNotices.items(xml, 1);
        assertEquals(1, items.size());
        assertEquals("Treasury convenience", items.get(0).title());
        assertEquals("2026-10-15", items.get(0).published());
        assertEquals("AAA & Treasuries", FedNotices.excerpt(items.get(0).description()));
    }

    @Test
    void articleTextStopsAtOneThousandCharacters() {
        String body = "The spread between Treasuries and corporate bonds has widened. ".repeat(40);
        String html = "<html><div id=\"article\"><p>" + body + "</p></div><footer>nav</footer></html>";
        String excerpt = FedNotices.articleExcerpt(html, "fallback");
        assertEquals(1000, excerpt.length());
        assertTrue(excerpt.startsWith("The spread"));
        assertFalse(excerpt.contains("nav"));
    }

    @Test
    void briefKeepsChineseAndAnalysis() throws Exception {
        FedNotices.Brief brief = FedNotices.briefOf(new ObjectMapper().readTree("""
                {"chinese":"利差走阔。","analysis":"对期限溢价偏有利。"}
                """));
        assertEquals("利差走阔。", brief.chinese());
        assertEquals("对期限溢价偏有利。", brief.analysis());
    }

    @Test
    void sqliteKeepsOneArticlePerUrl(@TempDir Path dir) throws Exception {
        FedArticleStore store = new FedArticleStore(dir.resolve("fed-articles.sqlite"));
        store.save(new FedArticleStore.Saved(
                "FRED Blog",
                "One",
                "https://fred.stlouisfed.org/blog/one",
                "2026-10-16",
                "English excerpt",
                "中文对照",
                "专业解读"));
        store.save(new FedArticleStore.Saved(
                "FRED Blog",
                "One",
                "https://fred.stlouisfed.org/blog/one",
                "2026-10-16",
                "English excerpt updated",
                "中文对照更新",
                "专业解读更新"));
        FedArticleStore.Saved saved = store.find("https://fred.stlouisfed.org/blog/one");
        assertTrue(saved.ready());
        assertEquals("English excerpt updated", saved.english());
        assertEquals("中文对照更新", saved.chinese());
        assertEquals("专业解读更新", saved.analysis());
    }
}
