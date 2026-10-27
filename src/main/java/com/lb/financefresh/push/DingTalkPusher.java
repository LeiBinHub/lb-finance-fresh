package com.lb.financefresh.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.market.ExternalHttp;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Component
public class DingTalkPusher implements Pusher {

    private final FreshSettings settings;
    private final ExternalHttp http;
    private final ObjectMapper json;

    public DingTalkPusher(FreshSettings settings, ExternalHttp http, ObjectMapper json) {
        this.settings = settings;
        this.http = http;
        this.json = json;
    }

    @Override
    public String name() { return "dingtalk"; }

    @Override
    public boolean enabled() { return !settings.dingtalkWebhook().isBlank(); }

    @Override
    public void push(String title, String markdown) throws Exception {
        String webhook = settings.dingtalkWebhook();
        if (!settings.dingtalkSecret().isBlank()) {
            long timestamp = System.currentTimeMillis();
            String sign = sign(timestamp, settings.dingtalkSecret());
            webhook = webhook + (webhook.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
        }
        ObjectNode body = json.createObjectNode();
        body.put("msgtype", "markdown");
        body.putObject("markdown").put("title", title).put("text", markdown);
        String response = http.postJson(URI.create(webhook), json.writeValueAsString(body), Map.of());
        int code = json.readTree(response).path("errcode").asInt(-1);
        if (code != 0) throw new IllegalStateException("钉钉返回 " + response);
    }

    static String sign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return URLEncoder.encode(Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
    }
}
