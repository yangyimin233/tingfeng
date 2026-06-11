package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.JudgeAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JudgeConfig {

    @Bean
    JudgeAgent judgeAgent(ChatModel model) {
        return AiServices.builder(JudgeAgent.class)
                .chatModel(model)
                .build();
    }
}
