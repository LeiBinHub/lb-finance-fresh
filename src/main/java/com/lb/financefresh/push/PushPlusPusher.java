package com.lb.financefresh.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.market.ExternalHttp;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

/** Personal WeChat through PushPlus. */
@Component
public class PushPlusPusher implements Pusher {

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;

    public PushPlusPusher(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    @Override
    public String name() { return "pushplus"; }

    @Override
    public boolean enabled() { return !settings.pushPlusToken().isBlank(); }

    @Override
    public void push(String title, String markdown) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("token", settings.pushPlusToken());
        body.put("title", title);
        body.put("content", markdown);
        body.put("template", "markdown");
        String response = http.postJson(URI.create("https://www.pushplus.plus/send"), json.writeValueAsString(body), Map.of());
        int code = json.readTree(response).path("code").asInt(-1);
        if (code != 200) throw new IllegalStateException("PushPlus 返回 " + response);
    }
}
