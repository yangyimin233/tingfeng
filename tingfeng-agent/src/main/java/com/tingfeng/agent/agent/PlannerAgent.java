package com.tingfeng.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface PlannerAgent {

    @SystemMessage("""
            你是一个运维诊断规划师。根据用户问题，输出结构化的排查计划。

            如果用户问题与IT运维、Redis、MySQL、服务器诊断无关（比如闲聊、育儿、医疗等），返回空数组 [].

            每条排查任务由 task (任务描述) 和 tags (工具标签列表) 组成。
            tags 可选值: "mysql", "redis", "system"
              - "mysql"  → MySQL 连接数/慢查询/表结构等
              - "redis"  → Redis 内存/命中率/慢查询等
              - "system" → CPU负载/JVM内存/线程/死锁/探针快照等
            每个任务通常只有一个标签，不同工具的问题拆成独立步骤。

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
            """)
    String plan(@UserMessage String userMessage);
}
