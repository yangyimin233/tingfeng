package com.tingfeng.starter.aop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tingfeng.starter.annotation.TingFengMonitor;
import com.tingfeng.starter.model.DiagnosticSnapshot;
import com.tingfeng.starter.report.TingFengReportClient;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Aspect
public class TingFengMonitorAspect {

    private static final Logger log = LoggerFactory.getLogger(TingFengMonitorAspect.class);

    private final TingFengReportClient reportClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService reportExecutor;

    public TingFengMonitorAspect(TingFengReportClient reportClient) {
        this.reportClient = reportClient;
        this.objectMapper = new ObjectMapper();
        this.reportExecutor = new ThreadPoolExecutor(
                1, 2,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(1000),
                new ThreadPoolExecutor.DiscardPolicy()
        );
    }

    @Around("@annotation(tingFengMonitor)")
    public Object around(ProceedingJoinPoint joinPoint, TingFengMonitor tingFengMonitor) throws Throwable {
        long start = System.currentTimeMillis();
        String methodName = resolveMethodName(joinPoint, tingFengMonitor);
        String argsJson = serializeArgs(joinPoint.getArgs());

        boolean success = true;
        String errorMsg = null;
        Throwable businessException = null;

        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            success = false;
            errorMsg = t.getClass().getName() + ": " + t.getMessage();
            businessException = t;
            throw t;
        } finally {
            if (businessException != null || tingFengMonitor.value() == TingFengMonitor.Strategy.ALL) {
                DiagnosticSnapshot snapshot = new DiagnosticSnapshot();
                snapshot.setMethodName(methodName);
                snapshot.setArgs(argsJson);
                snapshot.setRt(System.currentTimeMillis() - start);
                snapshot.setSuccess(success);
                snapshot.setErrorMsg(errorMsg);
                snapshot.setTimestamp(System.currentTimeMillis());

                reportExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            reportClient.report(snapshot);
                        } catch (Exception e) {
                            log.debug("TingFeng report failed: {}", e.getMessage());
                        }
                    }
                });
            }
        }
    }

    private String resolveMethodName(ProceedingJoinPoint joinPoint, TingFengMonitor annotation) {
        if (annotation.name() != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        return sig.getDeclaringType().getSimpleName() + "#" + sig.getName();
    }

    private String serializeArgs(Object[] args) {
        try {
            return objectMapper.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
