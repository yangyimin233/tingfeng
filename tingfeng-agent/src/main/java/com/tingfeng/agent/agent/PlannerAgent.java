package com.tingfeng.agent.agent;

import dev.langchain4j.service.UserMessage;

/**
 * 动态 Planner — SystemMessage 由 ToolRegistryManager.rebuildPlanner() 运行时注入。
 * 不标注 @SystemMessage，避免覆盖动态消息。
 */
public interface PlannerAgent {

    String plan(@UserMessage String userMessage);
}
