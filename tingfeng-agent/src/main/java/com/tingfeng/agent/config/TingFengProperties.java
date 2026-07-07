package com.tingfeng.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tingfeng")
public class TingFengProperties {

    private Llm llm = new Llm();
    private Memory memory = new Memory();
    private Mysql mysql = new Mysql();
    private Redis redis = new Redis();
    private Executor executor = new Executor();
    private Ollama ollama = new Ollama();
    private Alert alert = new Alert();

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }

    public Mysql getMysql() { return mysql; }
    public void setMysql(Mysql mysql) { this.mysql = mysql; }

    public Redis getRedis() { return redis; }
    public void setRedis(Redis redis) { this.redis = redis; }

    public Executor getExecutor() { return executor; }
    public void setExecutor(Executor executor) { this.executor = executor; }

    public Ollama getOllama() { return ollama; }
    public void setOllama(Ollama ollama) { this.ollama = ollama; }

    public Alert getAlert() { return alert; }
    public void setAlert(Alert alert) { this.alert = alert; }

    public static class Llm {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com";
        private String modelName = "deepseek-v4-flash";
        private boolean trackingEnabled = false;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }

        public boolean isTrackingEnabled() { return trackingEnabled; }
        public void setTrackingEnabled(boolean trackingEnabled) { this.trackingEnabled = trackingEnabled; }
    }

    public static class Memory {
        private int ttlMinutes = 30;

        public int getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(int ttlMinutes) { this.ttlMinutes = ttlMinutes; }
    }

    public static class Executor {
        private String provider = "cloud";
        private int timeoutSeconds = 60;
        private int snapshotContextSize = 10;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public int getSnapshotContextSize() { return snapshotContextSize; }
        public void setSnapshotContextSize(int snapshotContextSize) { this.snapshotContextSize = snapshotContextSize; }
    }

    public static class Ollama {
        private String baseUrl = "http://localhost:11434/v1";
        private String modelName = "deepseek-r1:8b";
        private String embeddingModel = "bge-m3";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }

        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
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

    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password = "123456";
        private int database = 1;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
    }

    public static class Alert {
        private boolean enabled = false;
        private int batchWindowSeconds = 30;
        private int maxBatchSize = 100;
        private Feishu feishu = new Feishu();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getBatchWindowSeconds() { return batchWindowSeconds; }
        public void setBatchWindowSeconds(int batchWindowSeconds) { this.batchWindowSeconds = batchWindowSeconds; }

        public int getMaxBatchSize() { return maxBatchSize; }
        public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }

        public Feishu getFeishu() { return feishu; }
        public void setFeishu(Feishu feishu) { this.feishu = feishu; }
    }

    public static class Feishu {
        private String webhookUrl = "";

        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    }
}
