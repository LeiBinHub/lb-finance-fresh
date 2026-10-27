package com.lb.financefresh.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;

@Component
public class ExternalHttp {

    private static final Logger log = LoggerFactory.getLogger(ExternalHttp.class);
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ApiFailures failures;

    public ExternalHttp(ApiFailures failures) {
        this.failures = failures;
    }

    public String get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        return get(uri, headers, true);
    }

    /** {@code recordFailure} is false for calls that should not appear in the brief's request log. */
    public String get(URI uri, Map<String, String> headers, boolean recordFailure) throws IOException, InterruptedException {
        try {
            return sendGet(uri, headers, recordFailure);
        } catch (IOException e) {
            if (statusError(e)) throw e;
            log.warn("GET {} {} retry after {}", uri.getHost(), uri.getPath(), e.toString());
            try {
                return sendGet(uri, headers, recordFailure);
            } catch (IOException again) {
                if (recordFailure && !statusError(again)) {
                    failures.add(uri, again.getMessage() == null ? "连接失败" : again.getMessage());
                }
                throw again;
            }
        }
    }

    public String get(URI uri, Map<String, String> headers, Charset charset, boolean recordFailure)
            throws IOException, InterruptedException {
        Charset encoding = charset == null ? Charset.defaultCharset() : charset;
        try {
            return new String(sendGetBytes(uri, headers, recordFailure), encoding);
        } catch (IOException e) {
            if (statusError(e)) throw e;
            log.warn("GET {} {} retry after {}", uri.getHost(), uri.getPath(), e.toString());
            try {
                return new String(sendGetBytes(uri, headers, recordFailure), encoding);
            } catch (IOException again) {
                if (recordFailure && !statusError(again)) {
                    failures.add(uri, again.getMessage() == null ? "连接失败" : again.getMessage());
                }
                throw again;
            }
        }
    }

    public String postJson(URI uri, String json, Map<String, String> headers) throws IOException, InterruptedException {
        return postJson(uri, json, headers, Duration.ofSeconds(60));
    }

    public String postJson(URI uri, String json, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout == null ? Duration.ofSeconds(60) : timeout)
                .header("User-Agent", "lb-finance-fresh/0.1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(builder::setHeader);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("POST {} {} -> {}", uri.getHost(), uri.getPath(), response.statusCode());
            failures.add(uri, "HTTP " + response.statusCode());
            throw new IOException(uri.getHost() + uri.getPath() + " HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String sendGet(URI uri, Map<String, String> headers, boolean recordFailure) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "lb-finance-fresh/0.1")
                .GET();
        headers.forEach(builder::setHeader);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("GET {} {} -> {}", uri.getHost(), uri.getPath(), response.statusCode());
            if (recordFailure) failures.add(uri, "HTTP " + response.statusCode());
            throw new IOException(uri.getHost() + uri.getPath() + " HTTP " + response.statusCode());
        }
        return response.body();
    }

    private byte[] sendGetBytes(URI uri, Map<String, String> headers, boolean recordFailure)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "lb-finance-fresh/0.1")
                .GET();
        headers.forEach(builder::setHeader);
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            log.warn("GET {} {} -> {}", uri.getHost(), uri.getPath(), response.statusCode());
            if (recordFailure) failures.add(uri, "HTTP " + response.statusCode());
            throw new IOException(uri.getHost() + uri.getPath() + " HTTP " + response.statusCode());
        }
        return response.body() == null ? new byte[0] : response.body();
    }

    private static boolean statusError(IOException error) {
        return error.getMessage() != null && error.getMessage().contains("HTTP ");
    }
}
