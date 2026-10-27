package com.lb.financefresh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "fresh")
public class FreshSettings {

    private String zone = "Asia/Shanghai";
    private String premarketCron = "0 30 20 * * MON-FRI";
    private String recapCron = "0 30 6 * * TUE-SAT";
    private int lookbackHours = 24;
    private int clusterTrigger = 5;
    private int clusterKeep = 3;
    private double moverPercent = 1.0;
    private List<String> watchlist = new ArrayList<>();

    public List<String> watchlist() {
        String override = DotEnv.get("WATCHLIST");
        List<String> source = override.isBlank() ? watchlist : Arrays.asList(override.split(","));
        return source.stream()
                .map(String::trim)
                .filter(symbol -> !symbol.isEmpty())
                .map(symbol -> symbol.toUpperCase())
                .distinct()
                .toList();
    }

    public String zone() { return zone; }
    public String premarketCron() { return premarketCron; }
    public String recapCron() { return recapCron; }
    public int lookbackHours() { return lookbackHours; }
    public int clusterTrigger() { return clusterTrigger; }
    public int clusterKeep() { return clusterKeep; }
    public double moverPercent() { return moverPercent; }

    public String deepseekKey() { return DotEnv.get("DEEPSEEK_API_KEY"); }
    public String deepseekUrl() {
        String url = DotEnv.get("DEEPSEEK_BASE_URL");
        return url.isBlank() ? "https://api.deepseek.com/v1/chat/completions" : url;
    }
    public String deepseekModel() {
        String model = DotEnv.get("DEEPSEEK_MODEL");
        return model.isBlank() ? "deepseek-chat" : model;
    }

    /** Model for media articles. Defaults to DEEPSEEK_MODEL. Thinking mode is enabled separately. */
    public String deepseekThinkModel() {
        String model = DotEnv.get("DEEPSEEK_THINK_MODEL");
        return model.isBlank() ? deepseekModel() : model;
    }

    public String deepseekReasoningEffort() {
        String effort = DotEnv.get("DEEPSEEK_REASONING_EFFORT");
        return effort.isBlank() ? "high" : effort;
    }
    public String finnhubKey() { return DotEnv.get("FINNHUB_API_KEY"); }
    public String fredKey() { return DotEnv.get("FRED_API_KEY"); }
    public String alphaVantageKey() { return DotEnv.get("ALPHAVANTAGE_API_KEY"); }
    public String alpacaKey() { return DotEnv.get("ALPACA_API_KEY_ID"); }
    public String alpacaSecret() { return DotEnv.get("ALPACA_API_SECRET_KEY"); }
    public String alpacaFeed() {
        String feed = DotEnv.get("ALPACA_FEED");
        return feed.isBlank() ? "iex" : feed;
    }
    public String dingtalkWebhook() { return DotEnv.get("DINGTALK_WEBHOOK"); }
    public String dingtalkSecret() { return DotEnv.get("DINGTALK_SECRET"); }
    public String wecomWebhook() { return DotEnv.get("WECOM_WEBHOOK"); }
    public String pushPlusToken() { return DotEnv.get("PUSHPLUS_TOKEN"); }
    public String telegramApiId() { return DotEnv.get("TELEGRAM_API_ID"); }
    public String telegramApiHash() { return DotEnv.get("TELEGRAM_API_HASH"); }
    public String treeNewsChannel() {
        String channel = DotEnv.get("TELEGRAM_TREE_CHANNEL");
        return channel.isBlank() ? "TreeNewsFeed" : channel.replace("@", "").strip();
    }

    public boolean telegramReady() { return !telegramApiId().isBlank() && !telegramApiHash().isBlank(); }

    public boolean finnhubReady() { return !finnhubKey().isBlank(); }
    public boolean alphaVantageReady() { return !alphaVantageKey().isBlank(); }
    public boolean fredReady() { return !fredKey().isBlank(); }
    public boolean alpacaReady() { return !alpacaKey().isBlank() && !alpacaSecret().isBlank(); }
    public boolean deepseekReady() { return !deepseekKey().isBlank(); }

    public List<String> requiredMissing() {
        List<String> missing = blockingMissing();
        if (dingtalkWebhook().isBlank() && wecomWebhook().isBlank() && pushPlusToken().isBlank()) {
            missing.add("DINGTALK_WEBHOOK、WECOM_WEBHOOK、PUSHPLUS_TOKEN 至少填一个");
        }
        return missing;
    }

    public List<String> blockingMissing() {
        List<String> missing = new ArrayList<>();
        if (!deepseekReady()) missing.add("DEEPSEEK_API_KEY");
        if (!finnhubReady() && !alpacaReady() && !alphaVantageReady()) {
            missing.add("FINNHUB_API_KEY、ALPHAVANTAGE_API_KEY、ALPACA 至少填一个新闻源");
        }
        if (!fredReady()) missing.add("FRED_API_KEY");
        return missing;
    }

    public void setZone(String zone) { this.zone = zone; }
    public void setPremarketCron(String premarketCron) { this.premarketCron = premarketCron; }
    public void setRecapCron(String recapCron) { this.recapCron = recapCron; }
    public void setLookbackHours(int lookbackHours) { this.lookbackHours = lookbackHours; }
    public void setClusterTrigger(int clusterTrigger) { this.clusterTrigger = clusterTrigger; }
    public void setClusterKeep(int clusterKeep) { this.clusterKeep = clusterKeep; }
    public void setMoverPercent(double moverPercent) { this.moverPercent = moverPercent; }
    public void setWatchlist(List<String> watchlist) { this.watchlist = watchlist; }
}
