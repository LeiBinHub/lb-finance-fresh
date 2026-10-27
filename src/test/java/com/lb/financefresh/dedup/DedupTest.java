package com.lb.financefresh.dedup;

import com.lb.financefresh.model.NewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupTest {

    @Test
    void titleHashIgnoresCaseAndPunctuation() {
        String left = TitleKey.sha256(TitleKey.normalize("NVDA: Shares Surge!"));
        String right = TitleKey.sha256(TitleKey.normalize("nvda shares surge"));
        assertEquals(left, right);
        assertEquals("https://news.example/a", TitleKey.canonicalUrl("https://news.example/a/#section"));
    }

    @Test
    void keepsEveryStoryUntilASymbolExceedsFive() {
        List<NewsItem> items = List.of(
                story("a", "NVDA", 1, 10),
                story("b", "NVDA", 2, 10),
                story("c", "NVDA", 3, 10),
                story("d", "NVDA", 4, 10),
                story("e", "NVDA", 5, 10));
        assertEquals(5, EventCluster.shrink(items, Set.of("NVDA"), 5, 3).size());
    }

    @Test
    void floodKeepsTheThreeNewestThenLongerBody() {
        List<NewsItem> items = List.of(
                story("old", "NVDA", 1, 500),
                story("t2", "NVDA", 2, 10),
                story("t3-long", "NVDA", 3, 400),
                story("t3-short", "NVDA", 3, 20),
                story("t4", "NVDA", 4, 10),
                story("t5", "NVDA", 5, 10));
        List<String> kept = EventCluster.shrink(items, Set.of("NVDA"), 5, 3).stream()
                .map(NewsItem::url)
                .toList();
        assertEquals(List.of("t5", "t4", "t3-long"), kept);
        assertTrue(kept.stream().noneMatch("old"::equals));
    }

    private static NewsItem story(String url, String symbol, long minute, int body) {
        return new NewsItem("test", url, "x".repeat(body), url, List.of(symbol), Instant.ofEpochSecond(minute * 60), 0);
    }
}
