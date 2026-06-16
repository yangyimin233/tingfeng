package com.tingfeng.starter.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tingfeng.starter.config.TingFengProperties;
import com.tingfeng.starter.model.DiagnosticSnapshot;
import com.tingfeng.starter.model.JvmMetricsSnapshot;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

public class TingFengReportClient {

    private static final Logger log = LoggerFactory.getLogger(TingFengReportClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TingFengProperties properties;

    public TingFengReportClient(TingFengProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .writeTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void report(DiagnosticSnapshot snapshot) {
        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.debug("TingFeng JSON serialize failed: {}", e.getMessage());
            return;
        }

        Request request = new Request.Builder()
                .url(properties.getEndpoint())
                .post(RequestBody.create(json, JSON))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("TingFeng report send failed: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }

    public void reportJvmMetrics(JvmMetricsSnapshot metrics) {
        String json;
        try {
            json = objectMapper.writeValueAsString(metrics);
        } catch (Exception e) {
            log.debug("TingFeng JVM metrics serialize failed: {}", e.getMessage());
            return;
        }

        Request request = new Request.Builder()
                .url(properties.getJvmEndpoint())
                .post(RequestBody.create(json, JSON))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("TingFeng JVM metrics send failed: {}", e.getMessage());
            }
            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }
}
