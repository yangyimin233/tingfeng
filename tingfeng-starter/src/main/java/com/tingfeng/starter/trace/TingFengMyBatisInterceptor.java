package com.tingfeng.starter.trace;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Properties;

/**
 * MyBatis Executor 层拦截器 — 摘取 Mapper ID + SQL + 参数规模。
 */
@Intercepts({
    @Signature(type = Executor.class, method = "update",
               args = {MappedStatement.class, Object.class}),
    @Signature(type = Executor.class, method = "query",
               args = {MappedStatement.class, Object.class,
                       org.apache.ibatis.session.RowBounds.class,
                       org.apache.ibatis.session.ResultHandler.class})
})
public class TingFengMyBatisInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(TingFengMyBatisInterceptor.class);

    {
        // 实例初始化块 — 验证拦截器确实被创建
        System.out.println("[TingFeng] MyBatis Interceptor 实例已创建!");
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        Object result;
        try {
            result = invocation.proceed();
        } finally {
            long duration = System.currentTimeMillis() - start;
            try {
                MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
                Object param = invocation.getArgs()[1];
                BoundSql boundSql = ms.getBoundSql(param);
                String sql = boundSql.getSql().replace('\n', ' ').replaceAll("\\s+", " ").trim();
                String paramSummary = summarizeParam(param);
                String entry = "[" + ms.getId() + "]"
                        + (paramSummary.isEmpty() ? "" : " " + paramSummary)
                        + " " + sql;
                log.debug("[TingFeng SQL] {} → {}ms", ms.getId(), duration);
                TingFengTraceContext.addSql(entry, duration);
            } catch (Exception e) {
                log.warn("TingFeng MyBatis SQL 摘取失败: {}", e.getMessage(), e);
            }
        }
        return result;
    }

    private String summarizeParam(Object param) {
        if (param == null) return "";
        if (param instanceof Collection) return "(List size=" + ((Collection<?>) param).size() + ")";
        if (param instanceof java.util.Map) return "(Map keys=" + ((java.util.Map<?,?>) param).keySet() + ")";
        return "";
    }

    @Override
    public Object plugin(Object target) {
        return Interceptor.super.plugin(target);
    }

    @Override
    public void setProperties(Properties properties) {}
}
