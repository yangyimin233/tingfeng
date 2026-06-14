package com.tingfeng.agent.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 动态构建 Planner 的 SystemMessage。
 * 标签描述随工具注册/注销实时变化，重建 PlannerAgent 时注入。
 */
public class DynamicSystemPromptBuilder {

    private final List<TagMeta> dynamicTags = new CopyOnWriteArrayList<>();

    public record TagMeta(String tag, String desc) {}

    private static final String BASE = """
            你是一个运维诊断规划师。根据用户问题，输出结构化的排查计划。

            如果用户问题与IT运维、Redis、MySQL、服务器诊断无关（比如闲聊、育儿、医疗等），返回空数组 [].

            每条排查任务由 task (任务描述) 和 tags (工具标签列表) 组成。
            每个任务通常只有一个标签，不同工具的问题拆成独立步骤。

            """;

    private static final String RULES = """
            规则：
            1. 只有与运维相关的问题才生成排查计划
            2. task 描述要具体，指明要检查什么指标
            3. 不超过5个步骤
            4. 严格返回 JSON 数组格式，只输出 JSON，不要其他内容

            输出格式示例：
            [
              {"task":"检查当前连接数及来源分布","tags":["mysql"]},
              {"task":"检查内存使用情况和命中率","tags":["redis"]},
              {"task":"检查JVM线程和CPU负载","tags":["system"]}
            ]
            """;

    public void addDynamicTag(String tag, String desc) {
        if (dynamicTags.stream().noneMatch(t -> t.tag.equals(tag))) {
            dynamicTags.add(new TagMeta(tag, desc));
        }
    }

    public void removeDynamicTag(String tag) {
        dynamicTags.removeIf(t -> t.tag.equals(tag));
    }

    public List<Map<String, String>> getDynamicTags() {
        List<Map<String, String>> list = new ArrayList<>();
        for (TagMeta t : dynamicTags) {
            list.add(Map.of("tag", t.tag, "desc", t.desc));
        }
        return list;
    }

    public String build() {
        StringBuilder sb = new StringBuilder(BASE);

        sb.append("当前可用的标签及对应工具领域：\n");
        sb.append("  \"mysql\"  → MySQL 诊断（连接数、慢查询、Buffer Pool 命中率、锁等待、主从复制、当前查询等）\n");
        sb.append("  \"redis\"  → Redis 诊断（内存使用、命中率、慢查询、大Key扫描等）\n");
        sb.append("  \"system\" → 系统诊断（CPU负载、JVM内存/线程/死锁、GC统计、磁盘空间、探针快照查询等）\n");

        for (TagMeta t : dynamicTags) {
            sb.append("  \"").append(t.tag).append("\" → ").append(t.desc).append("\n");
        }

        sb.append("\n").append(RULES);
        return sb.toString();
    }
}
