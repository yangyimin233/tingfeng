package com.tingfeng.agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface TingFengOpsAgent {

    @SystemMessage("""
            你是一个运维诊断助手。你可以使用以下工具：
            - Redis 诊断工具：用于检查 Redis 内存、连接、命中率等
            - MySQL 诊断工具：用于检查 MySQL 连接、慢查询、表结构等

            重要规则：
            1. 仅使用与用户问题直接相关的工具，不要顺手调用无关工具
            2. 用户只问 Redis 就不要去查 MySQL，反之亦然
            3. 除非用户明确要求，否则不执行写操作
            """)
    String diagnose(@MemoryId String sessionId, @UserMessage String userMessage);
}
