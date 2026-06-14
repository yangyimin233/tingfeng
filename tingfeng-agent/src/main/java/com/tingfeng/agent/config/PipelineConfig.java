package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.PlannerAgent;
import com.tingfeng.agent.agent.ReporterAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PipelineConfig {

    /** 初始占位 Planner, ToolRegistryManager.init() 会重建为真实版本 */
    @Bean
    DynamicPlannerHolder plannerAgent(ChatModel model) {
        PlannerAgent agent = AiServices.builder(PlannerAgent.class)
                .chatModel(model)
                .systemMessage("系统初始化中, 工具标签尚未加载...")
                .build();
        return new DynamicPlannerHolder(agent);
    }

    @Bean
    ReporterAgent reporterAgent(ChatModel model) {
        return AiServices.builder(ReporterAgent.class)
                .chatModel(model)
                .build();
    }
}
