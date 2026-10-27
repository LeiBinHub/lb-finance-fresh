package com.lb.financefresh.model;

import java.util.List;

public record MarketSnapshot(
        List<NewsItem> news,
        List<Quote> quotes,
        String yieldLine,
        String macroMonitor,
        List<CalendarEvent> released,
        List<CalendarEvent> upcoming,
        List<String> gaps
) {}
