package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动调试 FRED。IDE 里对这个方法点 Debug，工作目录选 lb-finance-fresh。
 * 断点可以下在 line = fred.yieldLine(day) 这一行，以及 FredClient.lastTwo 组完地址之后、ExternalHttp.sendGet 拿到响应之后。
 * 请求头已经是 curl/8.7.1，和终端里打通的那次一样。
 * 这个类标了 manual，mvn test 不会跑。
 */
@Tag("manual")
class FredLiveTest {

    @Test
    void observationsOn20261010() throws Exception {
        if (!Files.isRegularFile(Path.of(".env"))) {
            throw new IllegalStateException("工作目录不是 lb-finance-fresh，读不到 .env");
        }
        FreshSettings settings = new FreshSettings();
        if (!settings.fredReady()) {
            throw new IllegalStateException("FRED_API_KEY 是空的");
        }
        ApiFailures failures = new ApiFailures(settings);
        FredClient fred = new FredClient(settings, new ExternalHttp(failures), new ObjectMapper());
        LocalDate day = LocalDate.of(2026, 10, 10);

        String line = null;
        Exception error = null;
        long started = System.nanoTime();
        try {
            line = fred.yieldLine(day);
        } catch (Exception caught) {
            error = caught;
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        List<ApiFailures.FailedCall> failed = failures.list();

        if (error != null) {
            throw new AssertionError("FRED 没有返回，耗时 " + elapsedMs + " ms。" + error, error);
        }
        assertFalse(line.contains("没有取到"), "FRED 没有给出收益率，耗时 " + elapsedMs + " ms，失败 " + failed.size() + " 条");
        assertTrue(line.contains("10Y 5.24%"), line);
        assertTrue(line.contains("+2.0 bps"), line);
    }
}
