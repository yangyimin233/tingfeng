package com.tingfeng.starter.config;

import com.tingfeng.starter.trace.TingFengMyBatisInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.Map;

@Configuration
@ConditionalOnClass(name = "org.apache.ibatis.session.SqlSessionFactory")
public class TingFengMyBatisConfig {

    private static final Logger log = LoggerFactory.getLogger(TingFengMyBatisConfig.class);

    @Autowired
    private ApplicationContext ctx;

    @PostConstruct
    public void registerInterceptor() {
        Map<String, SqlSessionFactory> factories = ctx.getBeansOfType(SqlSessionFactory.class);
        if (factories.isEmpty()) {
            log.info("未找到 SqlSessionFactory, 跳过 MyBatis 拦截器注册");
            return;
        }
        TingFengMyBatisInterceptor interceptor = new TingFengMyBatisInterceptor();
        for (Map.Entry<String, SqlSessionFactory> entry : factories.entrySet()) {
            entry.getValue().getConfiguration().addInterceptor(interceptor);
            log.info("MyBatis 拦截器已注册至: {}", entry.getKey());
        }
    }
}
