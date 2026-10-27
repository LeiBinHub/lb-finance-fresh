package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeNewsTest {

    @Test
    void parseKeepsIdTimeAndUnescapedText() {
        String html = """
                <div class="tgme_widget_message" data-post="TreeNewsFeed/8176">
                  <time datetime="2026-09-28T11:30:10+00:00"></time>
                  <div class="tgme_widget_message_text js-message_text">U.S. CPI: +3.7%<br/>CORE: &#036;2.6%</div>
                </div>
                <div class="tgme_widget_message" data-post="TreeNewsFeed/8177">
                  <time datetime="2026-10-23T01:02:03+00:00"></time>
                  <div class="tgme_widget_message_text">NVDA GTC KEYNOTE TONIGHT</div>
                </div>
                """;
        List<TreeNews.Item> items = TreeNews.parse(html, "TreeNewsFeed", ZoneId.of("Asia/Shanghai"));
        assertEquals(2, items.size());
        TreeNews.Item first = items.getFirst();
        assertEquals("TreeNewsFeed/8176", first.id());
        assertEquals("TreeNewsFeed", first.channel());
        assertEquals("https://t.me/TreeNewsFeed/8176", first.url());
        assertTrue(first.text().contains("U.S. CPI: +3.7%"));
        assertTrue(first.text().contains("CORE: $2.6%"));
        assertEquals("2026-09-28 19:30:10", first.at());
        assertEquals("NVDA GTC KEYNOTE TONIGHT", items.get(1).text());
        assertEquals("2026-10-23 09:02:03", items.get(1).at());
    }

    @Test
    void parseDropsSponsoredAndInvitePosts() {
        String html = """
                <div class="tgme_widget_message" data-post="WalterBloomberg/1">
                  <time datetime="2026-10-23T01:00:00+00:00"></time>
                  <div class="tgme_widget_message_text">Join us in the VIP room</div>
                </div>
                <div class="tgme_widget_message" data-post="WalterBloomberg/2">
                  <time datetime="2026-10-23T01:01:00+00:00"></time>
                  <div class="tgme_widget_message_text">Ad: buy our trading course</div>
                </div>
                <div class="tgme_widget_message" data-post="WalterBloomberg/3">
                  <time datetime="2026-10-23T01:02:00+00:00"></time>
                  <div class="tgme_widget_message_text">This promo ends tonight</div>
                </div>
                <div class="tgme_widget_message" data-post="WalterBloomberg/4">
                  <time datetime="2026-10-23T01:03:00+00:00"></time>
                  <div class="tgme_widget_message_text">Sponsored by a broker</div>
                </div>
                <div class="tgme_widget_message" data-post="WalterBloomberg/5">
                  <time datetime="2026-10-23T01:04:00+00:00"></time>
                  <div class="tgme_widget_message_text">Launch app to claim bonus</div>
                </div>
                <div class="tgme_widget_message" data-post="WalterBloomberg/6">
                  <time datetime="2026-10-23T01:05:00+00:00"></time>
                  <div class="tgme_widget_message_text">Chat here https://t.me/+AbCdEf</div>
                </div>
                <div class="tgme_widget_message" data-post="WalterBloomberg/7">
                  <time datetime="2026-10-23T01:06:00+00:00"></time>
                  <div class="tgme_widget_message_text">U.S. CPI: +3.7% YEAR-OVER-YEAR</div>
                </div>
                """;
        List<TreeNews.Item> items = TreeNews.parse(html, "WalterBloomberg", ZoneId.of("Asia/Shanghai"));
        assertEquals(1, items.size());
        assertEquals("WalterBloomberg/7", items.getFirst().id());
    }

    @Test
    void junkLeavesOrdinaryMarketCopy() {
        assertFalse(TreeNews.junk("U.S. CPI: +3.7% YEAR-OVER-YEAR"));
        assertFalse(TreeNews.junk("Nasdaq futures rebound after the Fed"));
        assertFalse(TreeNews.junk("bid-ask spread tighter into the close"));
        assertFalse(TreeNews.junk("Healthcare premiums are expected to skyrocket, per CNN"));
        assertTrue(TreeNews.junk("https://discord.gg/signals"));
        assertTrue(TreeNews.junk("affiliate link https://bit.ly/abc"));
        assertTrue(TreeNews.junk("1️⃣ Hello\n2️⃣ I am new here\n3️⃣ How can I join VIP"));
        assertTrue(TreeNews.junk("Hello, join VIP for signals"));
        assertTrue(TreeNews.junk("This is a delayed news feed. Please check our Premium product"));
        assertFalse(TreeNews.junk("https://investinglive.com/news/uk-pmi-beats This is a delayed news feed. Please check our Premium product with real time news"));
        assertEquals(
                "https://investinglive.com/news/uk-pmi-beats",
                TreeNews.clean("https://investinglive.com/news/uk-pmi-beats This is a delayed news feed. Please check our Premium product"));
        assertTrue(TreeNews.junk("Satisfied Investor, Congratulations on your profit received"));
        assertTrue(TreeNews.junk("加微信领取VIP信号"));
    }

    @Test
    void forDayKeepsOnlyThatShanghaiCalendarDay(@TempDir Path dir) throws Exception {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Path file = dir.resolve("tree-news.json");
        List<TreeNews.Item> stored = List.of(
                item("old", "TreeNewsFeed", ZonedDateTime.of(2026, 10, 22, 23, 59, 59, 0, zone), "yesterday"),
                item("today-late", "WalterBloomberg", ZonedDateTime.of(2026, 10, 23, 23, 50, 0, 0, zone), "today night"),
                item("today-early", "jin10light", ZonedDateTime.of(2026, 10, 23, 0, 0, 1, 0, zone), "today morning"),
                item("next", "forexlive", ZonedDateTime.of(2026, 10, 24, 0, 0, 1, 0, zone), "tomorrow"));
        new ObjectMapper().writeValue(file.toFile(), stored);
        TreeNews news = new TreeNews(new FreshSettings(), null, file);
        List<TreeNews.Item> day = news.forDay(LocalDate.of(2026, 10, 23));
        assertEquals(List.of("today-early", "today-late"), day.stream().map(TreeNews.Item::id).toList());
        assertEquals(1, news.latest("WalterBloomberg").size());
        assertEquals(List.of("2026-10-24", "2026-10-23", "2026-10-22"), news.days());
        assertEquals("2026-10-22", news.previousDay(LocalDate.of(2026, 10, 23)));
        assertEquals("2026-10-24", news.nextDay(LocalDate.of(2026, 10, 23)));
        assertEquals(1, news.forDay("jin10light", LocalDate.of(2026, 10, 23)).size());
        assertEquals(0, news.forDay("jin10light", LocalDate.of(2026, 10, 22)).size());
        assertEquals("彭博快讯", TreeNews.titleZh("WalterBloomberg"));
        assertEquals("金十数据", TreeNews.titleZh("jin10light"));
    }

    @Test
    void loadDropsMessagesOlderThanThreeMonths(@TempDir Path dir) throws Exception {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Path file = dir.resolve("tree-news.json");
        LocalDate today = LocalDate.now(zone);
        List<TreeNews.Item> stored = List.of(
                item("keep", "jin10light", today.minusMonths(2).atTime(10, 0).atZone(zone), "within three months"),
                item("edge", "jin10light", today.minusMonths(3).atTime(10, 0).atZone(zone), "exactly three months"),
                item("drop", "jin10light", today.minusMonths(3).minusDays(1).atTime(10, 0).atZone(zone), "older than three months"));
        new ObjectMapper().writeValue(file.toFile(), stored);
        TreeNews news = new TreeNews(new FreshSettings(), null, file);
        List<String> ids = news.latest("jin10light").stream().map(TreeNews.Item::id).toList();
        assertTrue(ids.contains("keep"));
        assertTrue(ids.contains("edge"));
        assertFalse(ids.contains("drop"));
        TreeNews reloaded = new TreeNews(new FreshSettings(), null, file);
        assertFalse(reloaded.latest("jin10light").stream().anyMatch(item -> item.id().equals("drop")));
    }

    @Test
    void loadDropsStoredHelloVipPosts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tree-news.json");
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        List<TreeNews.Item> stored = List.of(
                item("keep", "unusual_whales_TG1", ZonedDateTime.of(2026, 10, 23, 10, 0, 0, 0, zone), "Barclays cutting 5,000 jobs"),
                item("vip", "unusual_whales_TG1", ZonedDateTime.of(2026, 10, 23, 10, 1, 0, 0, zone), "How can I join VIP"));
        new ObjectMapper().writeValue(file.toFile(), stored);
        TreeNews news = new TreeNews(new FreshSettings(), null, file);
        assertEquals(1, news.latest("unusual_whales_TG1").size());
        assertEquals("keep", news.latest("unusual_whales_TG1").getFirst().id());
    }

    @Test
    void loadStripsDelayedFeedFooter(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tree-news.json");
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        List<TreeNews.Item> stored = List.of(
                item(
                        "forexlive/9",
                        "forexlive",
                        ZonedDateTime.of(2026, 10, 23, 10, 0, 0, 0, zone),
                        "https://investinglive.com/news/uk-pmi-beats This is a delayed news feed. Please check our Premium product"));
        new ObjectMapper().writeValue(file.toFile(), stored);
        TreeNews news = new TreeNews(new FreshSettings(), null, file);
        assertEquals(1, news.latest("forexlive").size());
        assertEquals("https://investinglive.com/news/uk-pmi-beats", news.latest("forexlive").getFirst().text());
    }

    @Test
    void ingestKeepsAccountPostsAndDropsAds(@TempDir Path dir) {
        Path file = dir.resolve("tree-news.json");
        TreeNews news = new TreeNews(new FreshSettings(), null, file);
        int added = news.ingest(List.of(
                new TreeNews.Flash("forexlive", 12, 1_798_000_000_000L, "USDJPY pops after CPI"),
                new TreeNews.Flash("forexlive", 13, 1_798_000_000_100L, "Join us in VIP"),
                new TreeNews.Flash(
                        "forexlive",
                        14,
                        1_798_000_000_150L,
                        "https://investinglive.com/news/uk-pmi-beats This is a delayed news feed. Please check our Premium product"),
                new TreeNews.Flash("unusual_whales_TG1", 88, 1_798_000_000_200L, "Unusual flow in NVDA calls")));
        assertEquals(3, added);
        assertEquals(2, news.latest("forexlive").size());
        assertEquals(
                "https://investinglive.com/news/uk-pmi-beats",
                news.latest("forexlive").getFirst().text());
        assertEquals("USDJPY pops after CPI", news.latest("forexlive").get(1).text());
        assertEquals("unusual_whales_TG1/88", news.latest("unusual_whales_TG1").getFirst().id());
        assertTrue(news.lastError().isBlank());
    }

    @Test
    void parseKeepsInvestingLiveDelayedFeedAsForexLive() {
        String html = """
                <div class="tgme_widget_message" data-post="investinglivenews/179834">
                  <time datetime="2026-10-27T00:40:08+00:00"></time>
                  <div class="tgme_widget_message_text">https://investinglive.com/news/fed-hike This is a delayed news feed. Please check our Premium product</div>
                </div>
                """;
        List<TreeNews.Item> parsed = TreeNews.parse(html, "forexlive", ZoneId.of("Asia/Shanghai"));
        assertEquals(1, parsed.size());
        TreeNews.Feed feed = new TreeNews.Feed("forexlive", "ForexLive", "外汇与宏观直播", true, "investinglivenews");
        TreeNews.Item item = TreeNews.underFeed(parsed.getFirst(), feed);
        assertEquals("forexlive/179834", item.id());
        assertEquals("forexlive", item.channel());
        assertEquals("https://investinglive.com/news/fed-hike", item.text());
        assertEquals("https://t.me/investinglivenews/179834", item.url());
        assertEquals("investinglivenews", feed.sourceHandle());
    }

    @Test
    void ingestMapsInvestingLiveNewsToForexLive(@TempDir Path dir) {
        TreeNews news = new TreeNews(new FreshSettings(), null, dir.resolve("tree-news.json"));
        int added = news.ingest(List.of(
                new TreeNews.Flash("investinglivenews", 179834, 1_798_000_000_000L,
                        "https://investinglive.com/news/fed-hike This is a delayed news feed. Please check our Premium product")));
        assertEquals(1, added);
        assertEquals(1, news.latest("forexlive").size());
        assertEquals("forexlive/179834", news.latest("forexlive").getFirst().id());
        assertTrue(news.latest("forexlive").getFirst().url().contains("investinglivenews/179834"));
    }

    @Test
    void loadInfersChannelFromOldJson(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tree-news.json");
        Files.writeString(file, """
                [{"id":"TreeNewsFeed/8177","at":"2026-10-14 20:30:04","epoch":1791981004000,"text":"U.S. CPI","url":"https://t.me/TreeNewsFeed/8177"}]
                """);
        TreeNews news = new TreeNews(new FreshSettings(), null, file);
        TreeNews.Item item = news.latest().getFirst();
        assertEquals("TreeNewsFeed", item.channel());
        assertEquals("", item.zh());
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
