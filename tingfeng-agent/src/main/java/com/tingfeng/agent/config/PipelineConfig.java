package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.ExecutorAgent;
import com.tingfeng.agent.agent.PlannerAgent;
import com.tingfeng.agent.agent.ReporterAgent;
import com.tingfeng.agent.tool.RedisDiagnosticTools;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PipelineConfig {

    @Bean
    PlannerAgent plannerAgent(ChatModel model) {
        return AiServices.builder(PlannerAgent.class)
                .chatModel(model)
                .build();
    }

    @Bean
    ExecutorAgent executorAgent(ChatModel model,
                                 RedisDiagnosticTools redisTools,
                                 McpToolProvider mcpToolProvider) {
        return AiServices.builder(ExecutorAgent.class)
                .chatModel(model)
                .tools(redisTools)
                .toolProvider(mcpToolProvider)
                .build();
    }

    @Bean
    ReporterAgent reporterAgent(ChatModel model) {
        return AiServices.builder(ReporterAgent.class)
                .chatModel(model)
                .build();
    }
}
