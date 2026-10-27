package com.lb.financefresh.model;

import java.util.List;

public enum MediaDesk {
    US_MACRO("us-macro", "美国宏观", List.of()),
    US_MICRO("us-micro", "美国微观", List.of(
            "TreeNewsFeed", "WalterBloomberg", "unusual_whales_TG1", "forexlive")),
    CN_MICRO("cn-micro", "中国微观", List.of("jin10light"));

    private final String id;
    private final String title;
    private final List<String> channels;

    MediaDesk(String id, String title, List<String> channels) {
        this.id = id;
        this.title = title;
        this.channels = channels;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public List<String> channels() {
        return channels;
    }

    public boolean generated() {
        return this != US_MACRO;
    }

    public static MediaDesk fromPath(String value) {
        if (value == null || value.isBlank()) return US_MACRO;
        return switch (value.toLowerCase().strip()) {
            case "us-macro", "macro" -> US_MACRO;
            case "us-micro", "micro" -> US_MICRO;
            case "cn-micro", "china" -> CN_MICRO;
            default -> throw new IllegalArgumentException("栏目要写成 us-macro、us-micro 或 cn-micro");
        };
    }

    public static MediaDesk fromFile(String name) {
        if (name == null) return US_MACRO;
        if (name.startsWith("us-micro-")) return US_MICRO;
        if (name.startsWith("cn-micro-")) return CN_MICRO;
        return US_MACRO;
    }
}
