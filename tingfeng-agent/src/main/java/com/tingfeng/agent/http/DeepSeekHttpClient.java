package com.tingfeng.agent.http;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEventListener;

import java.time.Duration;

/**
 * Injects {@code "thinking":{"type":"disabled"}} into every request body,
 * preventing the DeepSeek V4 {@code reasoning_content} error in multi-turn tool-calling flows.
 */
public class DeepSeekHttpClient implements HttpClient {

    private final HttpClient delegate = new JdkHttpClient(new JdkHttpClientBuilder());

    public static HttpClientBuilder httpClientBuilder() {
        return new HttpClientBuilder() {
            private Duration connectTimeout;
            private Duration readTimeout;

            @Override public Duration connectTimeout() { return connectTimeout; }
            @Override public HttpClientBuilder connectTimeout(Duration t) { connectTimeout = t; return this; }
            @Override public Duration readTimeout() { return readTimeout; }
            @Override public HttpClientBuilder readTimeout(Duration t) { readTimeout = t; return this; }
            @Override public HttpClient build() { return new DeepSeekHttpClient(); }
        };
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        return delegate.execute(disableThinking(request));
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        delegate.execute(disableThinking(request), parser, listener);
    }

    private HttpRequest disableThinking(HttpRequest request) {
        String body = request.body();
        if (body == null || body.isBlank()) return request;
        String modified = body.replaceFirst("\\{",
                "{\"thinking\":{\"type\":\"disabled\"},");
        return HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(request.headers())
                .body(modified)
                .build();
    }
}
