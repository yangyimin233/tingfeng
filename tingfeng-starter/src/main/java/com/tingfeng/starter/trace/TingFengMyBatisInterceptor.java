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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * MyBatis Executor 层拦截器 — 摘取 Mapper ID + SQL + 参数 + 行数 + 成败。
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
        System.out.println("[TingFeng] MyBatis Interceptor 实例已创建!");
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        boolean success = true;
        String errorMsg = null;
        try {
            result = invocation.proceed();
            return result;
        } catch (Throwable t) {
            success = false;
            errorMsg = t.getClass().getSimpleName() + ": " + t.getMessage();
            throw t;
        } finally {
            long duration = System.currentTimeMillis() - start;
            try {
                MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
                Object param = invocation.getArgs()[1];
                BoundSql boundSql = ms.getBoundSql(param);
                String sql = boundSql.getSql().replace('\n', ' ').replaceAll("\\s+", " ").trim();

                long rows = computeRows(result);
                String paramsSummary = summarizeParam(param);

                TingFengTraceContext.SqlEntry entry = new TingFengTraceContext.SqlEntry(
                        ms.getId(), sql, paramsSummary, duration, rows, success, errorMsg);

                log.debug("[TingFeng SQL] {} rows={} duration={}ms", ms.getId(), rows, duration);
                TingFengTraceContext.addSql(entry);
            } catch (Exception e) {
                log.warn("TingFeng MyBatis SQL 摘取失败: {}", e.getMessage(), e);
            }
        }
    }

    /** 计算结果行数: List→size, Integer(update count)→值, 单个对象→1, null→0 */
    private long computeRows(Object result) {
        if (result == null) return 0;
        if (result instanceof Collection) return ((Collection<?>) result).size();
        if (result instanceof Integer) return (Integer) result;
        if (result instanceof Long) return (Long) result;
        return 1; // 单条 entity
    }

    private String summarizeParam(Object param) {
        if (param == null) return "";
        if (param instanceof Collection) {
            Collection<?> c = (Collection<?>) param;
            int size = c.size();
            if (size == 0) return "(empty list)";
            String typeName = c.iterator().next().getClass().getSimpleName();
            return "(List<" + typeName + "> size=" + size + ")";
        }
        if (param instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) param;
            List<String> values = new ArrayList<>();
            for (Object v : map.values()) {
                if (v == null) continue;
                String s = v.toString();
                values.add(s.length() > 80 ? s.substring(0, 80) + "..." : s);
            }
            if (!values.isEmpty()) {
                return "[" + String.join(", ", values) + "]";
            }
            return "";
        }
        String s = param.toString();
        return s.length() > 100 ? "[" + s.substring(0, 100) + "...]" : "[" + s + "]";
    }

    @Override
    public Object plugin(Object target) {
        return Interceptor.super.plugin(target);
    }

    @Override
    public void setProperties(Properties properties) {}
}
