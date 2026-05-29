package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.TingFengOpsAgent;
import com.tingfeng.agent.controller.TingFengChatController;
import com.tingfeng.agent.http.DeepSeekHttpClient;
import com.tingfeng.agent.tool.RedisDiagnosticTools;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TingFengProperties.class)
public class TingFengConfig {

    @Bean
    ChatLanguageModel chatLanguageModel(TingFengProperties props) {
        return OpenAiChatModel.builder()
                .apiKey(props.getApiKey())
                .baseUrl(props.getBaseUrl())
                .modelName(props.getModelName())
                .timeout(Duration.ofSeconds(60))
                .httpClientBuilder(DeepSeekHttpClient.httpClientBuilder())
                .build();
    }

    @Bean
    TingFengOpsAgent tingFengOpsAgent(ChatLanguageModel model,
                                      RedisDiagnosticTools redisTools) {
        return AiServices.builder(TingFengOpsAgent.class)
                .chatLanguageModel(model)
                .tools(redisTools)
                .build();
    }

    @Bean
    TingFengChatController tingFengChatController(TingFengOpsAgent agent) {
        return new TingFengChatController(agent);
    }

    @Bean
    RedisDiagnosticTools redisDiagnosticTools(StringRedisTemplate redis) {
        return new RedisDiagnosticTools(redis);
    }
}
