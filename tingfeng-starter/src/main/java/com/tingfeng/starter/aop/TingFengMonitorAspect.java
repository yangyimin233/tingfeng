package com.tingfeng.starter.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tingfeng.starter.annotation.TingFengMonitor;
import com.tingfeng.starter.config.TingFengProperties;
import com.tingfeng.starter.model.DiagnosticSnapshot;
import com.tingfeng.starter.report.TingFengReportClient;
import com.tingfeng.starter.trace.TingFengTraceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Aspect
public class TingFengMonitorAspect {

    private static final Logger log = LoggerFactory.getLogger(TingFengMonitorAspect.class);
    private static final int MAX_RETURN_LENGTH = 5000;

    private final TingFengReportClient reportClient;
    private final TingFengProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService reportExecutor;

    public TingFengMonitorAspect(TingFengReportClient reportClient,
                                  TingFengProperties properties) {
        this.reportClient = reportClient;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.reportExecutor = new ThreadPoolExecutor(
                1, 2,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000),
                new ThreadPoolExecutor.DiscardPolicy()
        );
    }

    @Around("@annotation(tingFengMonitor)")
    public Object around(ProceedingJoinPoint joinPoint, TingFengMonitor tingFengMonitor) throws Throwable {
        long start = System.currentTimeMillis();
        String methodName = resolveMethodName(joinPoint, tingFengMonitor);
        String argsJson = serialize(joinPoint.getArgs());

        // 开启 ThreadLocal 追踪, MyBatis Interceptor 中途追加 SQL
        TingFengTraceContext.start(methodName, argsJson, start);

        boolean success = true;
        Object returnValue = null;
        String errorMsg = null;
        String errorStack = null;

        try {
            returnValue = joinPoint.proceed();
            return returnValue;
        } catch (Throwable t) {
            success = false;
            errorMsg = t.getClass().getName() + ": " + t.getMessage();
            errorStack = stackTraceToString(t);
            throw t;
        } finally {
            if (!success || tingFengMonitor.value() == TingFengMonitor.Strategy.ALL) {
                DiagnosticSnapshot snapshot = new DiagnosticSnapshot();
                snapshot.setServerHost(properties.getServerHost());
                snapshot.setMethodName(methodName);
                snapshot.setArgs(argsJson);
                snapshot.setReturnValue(serializeReturn(returnValue));
                snapshot.setRequestTime(start);     // 请求到达时间
                snapshot.setRt(System.currentTimeMillis() - start);
                snapshot.setSuccess(success);
                snapshot.setErrorMsg(errorMsg);
                snapshot.setErrorStack(errorStack);
                snapshot.setTimestamp(System.currentTimeMillis());

                // 收集 MyBatis SQL 列表 (最多 50 条, JSON 截断 10000 字符)
                TingFengTraceContext.Trace trace = TingFengTraceContext.current();
                log.debug("[TingFeng Trace] current trace={}", trace);
                if (trace != null && !trace.sqlStatements.isEmpty()) {
                    String json = serialize(trace.sqlStatements);
                    log.info("[TingFeng SQL] 方法={} 捕获到 {} 条SQL",
                            methodName, trace.sqlStatements.size());
                    snapshot.setSqlStatements(json.length() > 10000
                            ? json.substring(0, 10000) + "...(truncated)" : json);
                } else {
                    log.debug("[TingFeng Trace] trace is null or sql list empty, method={}", methodName);
                }

                reportExecutor.execute(() -> {
                    try { reportClient.report(snapshot); }
                    catch (Exception e) { log.debug("TingFeng report failed: {}", e.getMessage()); }
                });
            }
            TingFengTraceContext.clear();
        }
    }

    private String resolveMethodName(ProceedingJoinPoint joinPoint, TingFengMonitor annotation) {
        if (annotation.name() != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        return sig.getDeclaringType().getSimpleName() + "#" + sig.getName();
    }

    private String serialize(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }

    private String serializeReturn(Object returnValue) {
        if (returnValue == null) return null;
        try {
            String json = objectMapper.writeValueAsString(returnValue);
            return json.length() > MAX_RETURN_LENGTH
                    ? json.substring(0, MAX_RETURN_LENGTH) + "...(truncated)" : json;
        } catch (Exception e) {
            return String.valueOf(returnValue);
        }
    }

    private String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
