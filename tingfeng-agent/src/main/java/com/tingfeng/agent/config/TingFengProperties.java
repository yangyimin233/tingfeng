package com.tingfeng.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tingfeng")
public class TingFengProperties {

    private Llm llm = new Llm();
    private Memory memory = new Memory();
    private Mysql mysql = new Mysql();
    private Executor executor = new Executor();
    private Ollama ollama = new Ollama();

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }

    public Mysql getMysql() { return mysql; }
    public void setMysql(Mysql mysql) { this.mysql = mysql; }

    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }

    public Ollama getOllama() { return ollama; }
    public void setOllama(Ollama ollama) { this.ollama = ollama; }

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

    public static class Executor {
        private String provider = "cloud";
        private int timeoutSeconds = 60;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Ollama {
        private String baseUrl = "http://localhost:11434/v1";
        private String modelName = "deepseek-r1:8b";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }

    public static class Mysql {
        private String host = "localhost";
        private int port = 3306;
        private String user = "root";
        private String pass = "1234";
        private String db = "";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }

        public String getPass() { return pass; }
        public void setPass(String pass) { this.pass = pass; }

        public String getDb() { return db; }
        public void setDb(String db) { this.db = db; }
    }
}
