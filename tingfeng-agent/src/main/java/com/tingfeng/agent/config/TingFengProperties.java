package com.tingfeng.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tingfeng")
public class TingFengProperties {

    private Llm llm = new Llm();
    private Memory memory = new Memory();

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }

    public static class Llm {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com";
        private String modelName = "deepseek-v4-flash";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }

    public static class Memory {
        private int ttlMinutes = 30;

        public int getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(int ttlMinutes) { this.ttlMinutes = ttlMinutes; }
    }
}
