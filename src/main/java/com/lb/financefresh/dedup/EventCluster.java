package com.lb.financefresh.dedup;

import com.lb.financefresh.model.NewsItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * When one symbol has more than {@code trigger} stories in this batch,
 * keep the {@code keep} newest, strongest-sentiment, longest-body items.
 */
public final class EventCluster {

    private EventCluster() {}

    public static List<NewsItem> shrink(List<NewsItem> items, Set<String> watchlist, int trigger, int keep) {
        LinkedHashMap<String, List<NewsItem>> groups = new LinkedHashMap<>();
        for (NewsItem item : items) {
            List<String> symbols = item.symbols().stream()
                    .map(symbol -> symbol.toUpperCase(Locale.ROOT))
                    .filter(watchlist::contains)
                    .distinct()
                    .toList();
            if (symbols.isEmpty()) symbols = List.of("MARKET");
            for (String symbol : symbols) {
                groups.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(item);
            }
        }
        LinkedHashMap<String, NewsItem> kept = new LinkedHashMap<>();
        for (List<NewsItem> group : groups.values()) {
            List<NewsItem> chosen = group.size() > trigger ? top(group, keep) : group;
            for (NewsItem item : chosen) {
                kept.putIfAbsent(identity(item), item);
            }
        }
        return List.copyOf(kept.values());
    }

    private static List<NewsItem> top(List<NewsItem> group, int keep) {
        return group.stream()
                .sorted(Comparator
                        .comparing(NewsItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(item -> Math.abs(item.sentiment()), Comparator.reverseOrder())
                        .thenComparing(NewsItem::bodyLength, Comparator.reverseOrder()))
                .limit(Math.max(keep, 1))
                .toList();
    }

    private static String identity(NewsItem item) {
        String url = TitleKey.canonicalUrl(item.url());
        return url.isBlank() ? TitleKey.normalize(item.title()) : url;
    }
}
