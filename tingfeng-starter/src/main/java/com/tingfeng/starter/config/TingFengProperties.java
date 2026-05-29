package com.tingfeng.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tingfeng.agent")
public class TingFengProperties {

    /** TingFeng Agent 诊断服务的上报地址 */
    private String endpoint = "http://localhost:8081/tingfeng/report";

    /** 是否启用探针上报 */
    private boolean enabled = true;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
