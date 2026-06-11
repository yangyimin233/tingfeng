package com.tingfeng.agent.agent;

import java.util.List;

/**
 * Planner 产出的单条排查任务 — 包含任务描述和标签。
 * 标签用于 Executor 按需选择工具集: mysql → MySQL MCP, redis → Redis, system → CPU/JVM
 */
public record TodoItem(String task, List<String> tags) {}
