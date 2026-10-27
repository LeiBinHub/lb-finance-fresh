package com.lb.financefresh.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.market.ExternalHttp;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class WeComPusher implements Pusher {

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;

    public WeComPusher(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    @Override
    public String name() { return "wecom"; }

    @Override
    public boolean enabled() { return !settings.wecomWebhook().isBlank(); }

    @Override
    public void push(String title, String markdown) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("msgtype", "markdown");
        body.putObject("markdown").put("content", clip(markdown));
        String response = http.postJson(URI.create(settings.wecomWebhook()), json.writeValueAsString(body), Map.of());
        int code = json.readTree(response).path("errcode").asInt(-1);
        if (code != 0) throw new IllegalStateException("企业微信返回 " + response);
    }

    static String clip(String markdown) {
        byte[] raw = markdown.getBytes(StandardCharsets.UTF_8);
        String note = "\n…后面在页面上。";
        int budget = 4000 - note.getBytes(StandardCharsets.UTF_8).length;
        if (raw.length <= budget) return markdown;
        int end = budget;
        while (end > 0 && (raw[end] & 0b1100_0000) == 0b1000_0000) end--;
        return new String(raw, 0, end, StandardCharsets.UTF_8) + note;
    }
}
