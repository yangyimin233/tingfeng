package com.tingfeng.starter.config;

import com.tingfeng.starter.aop.TingFengMonitorAspect;
import com.tingfeng.starter.collect.JvmMetricsCollector;
import com.tingfeng.starter.report.TingFengReportClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TingFengProperties.class)
@ConditionalOnProperty(prefix = "tingfeng.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TingFengAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TingFengReportClient tingFengReportClient(TingFengProperties properties) {
        return new TingFengReportClient(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TingFengMonitorAspect tingFengMonitorAspect(TingFengReportClient reportClient,
                                                         TingFengProperties properties) {
        return new TingFengMonitorAspect(reportClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "tingfeng.agent", name = "jvm-collect-enabled", havingValue = "true", matchIfMissing = true)
    public JvmMetricsCollector jvmMetricsCollector(TingFengReportClient reportClient,
                                                    TingFengProperties properties) {
        JvmMetricsCollector collector = new JvmMetricsCollector(reportClient, properties);
        collector.start();
        return collector;
    }

}
