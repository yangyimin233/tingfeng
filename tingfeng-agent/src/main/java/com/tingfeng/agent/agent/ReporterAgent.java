package com.tingfeng.agent.agent;

import dev.langchain4j.service.UserMessage;

/**
 * 动态 Reporter — SystemMessage 由 ToolRegistryManager.rebuildReporter() 运行时注入。
 */
public interface ReporterAgent {

    String report(@UserMessage String fullContext);
}
