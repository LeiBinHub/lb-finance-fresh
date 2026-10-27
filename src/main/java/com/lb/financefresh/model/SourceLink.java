package com.lb.financefresh.model;

public record SourceLink(String title, String url) {
    public SourceLink {
        title = title == null ? "" : title;
        url = url == null ? "" : url;
    }
}
