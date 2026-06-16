package com.tingfeng.agent.controller;

import com.tingfeng.agent.persist.JvmMetricsRepository;
import com.tingfeng.agent.persist.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/tingfeng")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private static final int MAX_SNAPSHOTS = 50;

    private final List<Map<String, Object>> snapshots =
            Collections.synchronizedList(new ArrayList<>() {
                @Override
                public boolean add(Map<String, Object> s) {
                    if (size() >= MAX_SNAPSHOTS) remove(0);
                    return super.add(s);
                }
            });

    private final SnapshotRepository repository;
    private final JvmMetricsRepository jvmRepository;

    public ReportController(Optional<SnapshotRepository> repository,
                            Optional<JvmMetricsRepository> jvmRepository) {
        this.repository = repository.orElse(null);
        this.jvmRepository = jvmRepository.orElse(null);
    }

    @PostMapping("/report")
    public void receive(@RequestBody LinkedHashMap<String, Object> snapshot) {
        log.info("Received snapshot: method={}, success={}, rt={}ms",
                snapshot.get("methodName"), snapshot.get("success"), snapshot.get("rt"));
        snapshots.add(snapshot);
        if (repository != null) {
            repository.save(snapshot);
        }
    }

    @PostMapping("/jvm-metrics")
    public void receiveJvmMetrics(@RequestBody LinkedHashMap<String, Object> metrics) {
        log.info("Received JVM metrics: cpu={}%, heap={}MB, threads={}",
                metrics.get("cpuProcess"), metrics.get("heapUsedMb"), metrics.get("threadCount"));
        if (jvmRepository != null) {
            jvmRepository.save(metrics);
        }
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> listSnapshots() {
        List<Map<String, Object>> copy = new ArrayList<>(snapshots);
        Collections.reverse(copy);
        return copy;
    }
}
