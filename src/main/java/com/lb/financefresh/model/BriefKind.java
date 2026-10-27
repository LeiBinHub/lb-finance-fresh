package com.lb.financefresh.model;

public enum BriefKind {
    PREMARKET("美股盘前简报"),
    RECAP("美股复盘早报"),
    NOW("美股当前时报");

    private final String title;

    BriefKind(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public static BriefKind fromPath(String value) {
        if (value == null) throw new IllegalArgumentException("要指定 premarket、recap 或 now");
        return switch (value.toLowerCase()) {
            case "premarket" -> PREMARKET;
            case "recap" -> RECAP;
            case "now" -> NOW;
            default -> throw new IllegalArgumentException("只能是 premarket、recap 或 now");
        };
    }
}
