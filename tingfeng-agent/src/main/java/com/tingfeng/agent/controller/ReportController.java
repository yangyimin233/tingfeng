package com.tingfeng.agent.controller;

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

@RestController
@RequestMapping("/tingfeng")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private static final int MAX_SNAPSHOTS = 50;

    private final List<Map<String, Object>> snapshots =
            Collections.synchronizedList(new ArrayList<Map<String, Object>>() {
                @Override
                public boolean add(Map<String, Object> s) {
                    if (size() >= MAX_SNAPSHOTS) {
                        remove(0);
                    }
                    return super.add(s);
                }
            });

    @PostMapping("/report")
    public void receive(@RequestBody Map<String, Object> snapshot) {
        log.info("Received diagnostic snapshot: method={}, success={}, rt={}ms",
                snapshot.get("methodName"), snapshot.get("success"), snapshot.get("rt"));
        snapshots.add(snapshot);
    }

    @GetMapping("/snapshots")
    public List<Map<String, Object>> listSnapshots() {
        List<Map<String, Object>> copy = new ArrayList<>(snapshots);
        Collections.reverse(copy);
        return copy;
    }
}
