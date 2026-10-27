package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseCalendarTest {

    @Test
    void translationKeepsTheOriginalNameAsTheKey() throws Exception {
        String body = """
                {"items":[{"name":"Retail Sales","chinese":"零售销售"},{"name":" ","chinese":"空"}]}
                """;
        Map<String, String> chinese = ReleaseCalendar.translations(new ObjectMapper().readTree(body));
        assertEquals("零售销售", chinese.get("Retail Sales"));
        assertEquals(1, chinese.size());
    }

    @Test
    void englishCopyIsNotTreatedAsATranslation() throws Exception {
        String body = """
                {"items":[{"name":"Retail Sales","chinese":"Retail Sales"}]}
                """;
        Map<String, String> chinese = ReleaseCalendar.translations(new ObjectMapper().readTree(body));
        assertEquals(0, chinese.size());
        assertEquals(false, ReleaseCalendar.translated(List.of(
                new ReleaseCalendar.Item("2026-10-16", "Retail Sales", "Retail Sales"))));
    }

    @Test
    void repeatedReleaseNamesAreTranslatedOnce() {
        List<String> names = ReleaseCalendar.names(List.of(
                new FredClient.Release("2026-10-15", "Retail Sales"),
                new FredClient.Release("2026-10-16", "Retail Sales"),
                new FredClient.Release("2026-10-16", "FOMC Press Release")));
        assertEquals(List.of("Retail Sales", "FOMC Press Release"), names);
    }

    @Test
    void sqliteReplacesTheSavedCalendar(@TempDir Path dir) throws Exception {
        CalendarStore store = new CalendarStore(dir.resolve("calendar.sqlite"));
        store.replace(List.of(new ReleaseCalendar.Item("2026-10-15", "Retail Sales", "零售销售")));
        store.replace(List.of(new ReleaseCalendar.Item("2026-10-16", "FOMC Press Release", "联邦公开市场委员会新闻稿")));
        List<ReleaseCalendar.Item> saved = store.list();
        assertEquals(1, saved.size());
        assertEquals("FOMC Press Release", saved.get(0).name());
        assertEquals("联邦公开市场委员会新闻稿", saved.get(0).chinese());
    }
}
