package com.tingfeng.agent.config;

import com.tingfeng.agent.persist.JvmMetricsRepository;
import com.tingfeng.agent.persist.SnapshotRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(TingFengPersistenceProperties.class)
public class PersistenceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "tingfeng.persistence", name = "url")
    public DataSource persistenceDataSource(TingFengPersistenceProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        ds.setDriverClassName(props.getDriverClassName());
        ds.setMaximumPoolSize(props.getMaximumPoolSize());
        ds.setPoolName("TingFengPersist");
        return ds;
    }

    @Bean
    @ConditionalOnProperty(prefix = "tingfeng.persistence", name = "url")
    public SnapshotRepository snapshotRepository(DataSource persistenceDataSource) {
        return new SnapshotRepository(persistenceDataSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tingfeng.persistence", name = "url")
    public JvmMetricsRepository jvmMetricsRepository(DataSource persistenceDataSource) {
        return new JvmMetricsRepository(persistenceDataSource);
    }
}
