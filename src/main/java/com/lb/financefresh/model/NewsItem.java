package com.lb.financefresh.model;

import java.time.Instant;
import java.util.List;

public record NewsItem(
        String source,
        String title,
        String summary,
        String url,
        List<String> symbols,
        Instant publishedAt,
        double sentiment
) {
    public NewsItem {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        url = url == null ? "" : url;
        source = source == null ? "" : source;
    }

    public int bodyLength() {
        String body = summary.isBlank() ? title : summary;
        return body.length();
    }
}
