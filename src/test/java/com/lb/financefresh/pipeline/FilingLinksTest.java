package com.lb.financefresh.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingLinksTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void normalizesTicker() {
        assertEquals("GOOGL", FilingLinks.normalize(" googl "));
        assertEquals("BRK.B", FilingLinks.normalize("brk.b"));
        assertThrows(IllegalArgumentException.class, () -> FilingLinks.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> FilingLinks.normalize("too long ticker!!"));
    }

    @Test
    void unwrapsMarkdownFence() {
        String raw = """
                ```json
                {"heading":"ok","groups":[]}
                ```
                """;
        assertTrue(FilingLinks.unwrap(raw).startsWith("{"));
        assertTrue(FilingLinks.unwrap("prefix {\"a\":1} trailing").contains("\"a\":1"));
    }

    @Test
    void parseKeepsHttpsItemsAndDropsJunk() throws Exception {
        Map<String, Object> body = FilingLinks.parseModelJson("""
                这里是链接
                {
                  "heading": "微软 FY27 Q1",
                  "groups": [
                    {
                      "title": "10-Q / 业绩稿",
                      "items": [
                        {"label": "10-Q", "url": "https://www.sec.gov/Archives/edgar/data/789019/a.htm", "note": "另存"},
                        {"label": "skip", "url": "http://insecure.example/file"},
                        {"label": "empty", "url": ""}
                      ]
                    },
                    {
                      "title": "电话会纪要",
                      "hint": "打印成 PDF",
                      "items": [
                        {"label": "Fool", "url": "https://www.fool.com/earnings/call-transcripts/2026/10/29/microsoft/"}
                      ]
                    },
                    {
                      "title": "一致预期表",
                      "items": [
                        {"label": "Yahoo", "url": "https://finance.yahoo.com/quote/MSFT/analysis/"}
                      ]
                    },
                    {
                      "title": "第四组应丢",
                      "items": [{"label": "x", "url": "https://example.com/x"}]
                    }
                  ]
                }
                extra
                """, json);
        assertEquals("微软 FY27 Q1", body.get("heading"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) body.get("groups");
        assertEquals(3, groups.size());
        assertEquals("10-Q / 业绩稿", groups.get(0).get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) groups.get(0).get("items");
        assertEquals(1, firstItems.size());
        assertEquals("https://www.sec.gov/Archives/edgar/data/789019/a.htm", firstItems.get(0).get("url"));
        assertEquals("另存", firstItems.get(0).get("note"));
        assertEquals("打印成 PDF", groups.get(1).get("hint"));
        assertFalse(body.toString().contains("第四组"));
        assertFalse(body.toString().contains("insecure"));
    }

    @Test
    void parseRejectsEmptyGroups() {
        assertThrows(IllegalStateException.class, () -> FilingLinks.parseModelJson("{}", json));
        assertThrows(IllegalStateException.class, () -> FilingLinks.parseModelJson(
                "{\"heading\":\"x\",\"groups\":[{\"title\":\"10-Q\",\"items\":[{\"label\":\"a\",\"url\":\"ftp://x\"}]}]}",
                json));
    }
}
