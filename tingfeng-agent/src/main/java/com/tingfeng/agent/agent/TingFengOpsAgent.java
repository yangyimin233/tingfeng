package com.tingfeng.agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

public interface TingFengOpsAgent {

    String diagnose(@MemoryId String sessionId, @UserMessage String userMessage);
}
