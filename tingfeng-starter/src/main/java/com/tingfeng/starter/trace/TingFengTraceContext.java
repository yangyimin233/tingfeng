package com.tingfeng.starter.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次请求的调用链路上下文。
 * AOP 入口设入, MyBatis Interceptor 中途追加, finally 读取并清除。
 */
public class TingFengTraceContext {

    private static final ThreadLocal<Trace> LOCAL = new ThreadLocal<>();

    /** 开启一次追踪 */
    public static void start(String methodName, String args, long requestTime) {
        Trace t = new Trace();
        t.methodName = methodName;
        t.args = args;
        t.requestTime = requestTime;
        LOCAL.set(t);
    }

    private static final int MAX_SQL_COUNT = 50;

    /** MyBatis Interceptor 追加一条 SQL (超过上限不再收集) */
    public static void addSql(String sql, long durationMs) {
        Trace t = LOCAL.get();
        if (t != null && t.sqlStatements != null && t.sqlStatements.size() < MAX_SQL_COUNT) {
            t.sqlStatements.add(new SqlEntry(sql, durationMs));
        }
    }

    /** 读取当前上下文 (不清除), AOP finally 用 */
    public static Trace current() {
        return LOCAL.get();
    }

    /** 清除 (防止内存泄漏) */
    public static void clear() {
        LOCAL.remove();
    }

    public static class Trace {
        public String methodName;
        public String args;
        public long requestTime;
        public final List<SqlEntry> sqlStatements = new ArrayList<>();
    }

    public static class SqlEntry {
        public final String sql;
        public final long durationMs;

        public SqlEntry(String sql, long durationMs) {
            this.sql = sql;
            this.durationMs = durationMs;
        }
    }
}
