package com.tingfeng.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tingfeng.agent")
public class TingFengProperties {

    /** TingFeng Agent 诊断服务的上报地址 */
    private String endpoint = "http://localhost:8081/tingfeng/report";

    /** JVM 指标上报地址 */
    private String jvmEndpoint = "http://localhost:8081/tingfeng/jvm-metrics";

    /** 是否启用探针上报 */
    private boolean enabled = true;

    /** 是否启用 JVM 指标采集 */
    private boolean jvmCollectEnabled = true;

    /** JVM 指标采集间隔(秒) */
    private int jvmCollectInterval = 15;

    /** 服务器标识 (用于分布式环境下区分探针数据来源), 默认取 hostname */
    private String serverHost = getDefaultHost();

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getJvmEndpoint() { return jvmEndpoint; }
    public void setJvmEndpoint(String jvmEndpoint) { this.jvmEndpoint = jvmEndpoint; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isJvmCollectEnabled() { return jvmCollectEnabled; }
    public void setJvmCollectEnabled(boolean jvmCollectEnabled) { this.jvmCollectEnabled = jvmCollectEnabled; }

    public int getJvmCollectInterval() { return jvmCollectInterval; }
    public void setJvmCollectInterval(int jvmCollectInterval) { this.jvmCollectInterval = jvmCollectInterval; }

    public String getServerHost() { return serverHost; }
    public void setServerHost(String serverHost) { this.serverHost = serverHost; }

    private static String getDefaultHost() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }
}
