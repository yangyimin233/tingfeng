package com.tingfeng.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 探针快照持久化数据源配置，独立于诊断用的 MySQL。
 * 不配置 url 时仅内存存储。
 */
@ConfigurationProperties(prefix = "tingfeng.persistence")
public class TingFengPersistenceProperties {

    private String url;
    private String username = "root";
    private String password = "123456";
    private String driverClassName = "com.mysql.cj.jdbc.Driver";
    private int maximumPoolSize = 5;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    public int getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }

    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }
}
