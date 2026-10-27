package com.lb.financefresh.market;

import com.lb.financefresh.config.FreshSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiFailuresTest {

    @TempDir
    Path dir;

    @Test
    void gapsBecomeOneRequestLogRow() {
        ApiFailures failures = new ApiFailures(new FreshSettings(), dir.resolve("log.json"));
        failures.addGaps(List.of(
                "Finnhub 免费档没有公司情绪分，新闻仍会拉取",
                "Alpha Vantage 新闻失败",
                "Finnhub 免费档没有经济日历，财报日历仍会拉取"));
        assertEquals(1, failures.list().size());
        assertEquals(
                "本次缺口：Finnhub 免费档没有公司情绪分，新闻仍会拉取；Alpha Vantage 新闻失败；Finnhub 免费档没有经济日历，财报日历仍会拉取",
                failures.list().getFirst().api());
        assertTrue(failures.list().getFirst().address().contains("采集"));
    }

    @Test
    void tencentHostIsLabeled行情() {
        assertEquals("腾讯行情", ApiFailures.apiName(java.net.URI.create("https://qt.gtimg.cn/q=sh000001")));
    }
}
