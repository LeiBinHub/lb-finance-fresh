package com.lb.financefresh.dedup;

import com.lb.financefresh.model.NewsItem;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact duplicates: URL or normalized title hash, kept 72 hours. */
@Component
public class ExactDedup {

    static final Duration TTL = Duration.ofHours(72);
    private static final String URL_PREFIX = "finance:news:url:";
    private static final String TITLE_PREFIX = "finance:news:title:";

    private final StringRedisTemplate redis;

    public ExactDedup(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void ping() {
        String where = endpoint();
        try {
            String pong = redis.execute(connection -> connection.ping(), true);
            if (pong == null || pong.isBlank()) {
                throw new IllegalStateException("Redis 没有响应：" + where);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException(failure(where, e));
        }
    }

    private String endpoint() {
        if (redis.getConnectionFactory() instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuce) {
            return lettuce.getHostName() + ":" + lettuce.getPort();
        }
        return "Redis";
    }

    private static String failure(String where, RuntimeException error) {
        String reason = error.getMessage() == null ? "" : error.getMessage();
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) reason = cause.getMessage();
        if (reason.contains("No route to host")) {
            return "Redis 没有连上 " + where + "。这个进程到不了 OrbStack 的容器网段，请改用 127.0.0.1。";
        }
        if (reason.contains("Connection refused")) {
            return "Redis 没有连上 " + where + "。本机这个端口没有 Redis 在听。";
        }
        return "Redis 没有连上 " + where + (reason.isBlank() ? "。" : "。" + reason);
    }

    public List<NewsItem> filterUnseen(List<NewsItem> items) {
        List<NewsItem> out = new ArrayList<>();
        Set<String> batchUrls = new HashSet<>();
        Set<String> batchTitles = new HashSet<>();
        for (NewsItem item : items) {
            String url = TitleKey.canonicalUrl(item.url());
            String title = TitleKey.normalize(item.title());
            if (url.isBlank() && title.isBlank()) continue;
            String urlHash = url.isBlank() ? "" : TitleKey.sha256(url);
            String titleHash = title.isBlank() ? "" : TitleKey.sha256(title);
            if (!urlHash.isBlank() && !batchUrls.add(urlHash)) continue;
            if (!titleHash.isBlank() && !batchTitles.add(titleHash)) continue;
            if (!urlHash.isBlank() && Boolean.TRUE.equals(redis.hasKey(URL_PREFIX + urlHash))) continue;
            if (!titleHash.isBlank() && Boolean.TRUE.equals(redis.hasKey(TITLE_PREFIX + titleHash))) continue;
            out.add(item);
        }
        return out;
    }

    public void remember(List<NewsItem> items) {
        for (NewsItem item : items) {
            String url = TitleKey.canonicalUrl(item.url());
            String title = TitleKey.normalize(item.title());
            if (!url.isBlank()) {
                redis.opsForValue().setIfAbsent(URL_PREFIX + TitleKey.sha256(url), "1", TTL);
            }
            if (!title.isBlank()) {
                redis.opsForValue().setIfAbsent(TITLE_PREFIX + TitleKey.sha256(title), "1", TTL);
            }
        }
    }
}
