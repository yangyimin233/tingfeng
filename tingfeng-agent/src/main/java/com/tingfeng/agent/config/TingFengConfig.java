package com.tingfeng.agent.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tingfeng.agent.agent.TingFengOpsAgent;
import com.tingfeng.agent.controller.TingFengChatController;
import com.tingfeng.agent.http.DeepSeekHttpClient;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(TingFengProperties.class)
public class TingFengConfig {

    private static final Logger log = LoggerFactory.getLogger(TingFengConfig.class);

    @Bean
    ChatModel chatLanguageModel(TingFengProperties props) {
        return OpenAiChatModel.builder()
                .apiKey(props.getLlm().getApiKey())
                .baseUrl(props.getLlm().getBaseUrl())
                .modelName(props.getLlm().getModelName())
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(true)
                .httpClientBuilder(DeepSeekHttpClient.httpClientBuilder())
                .build();
    }

    @Bean
    ChatMemoryProvider chatMemoryProvider(TingFengProperties props) {
        Cache<String, ChatMemory> cache = Caffeine.newBuilder()
                .expireAfterAccess(props.getMemory().getTtlMinutes(), TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

        return memoryId -> cache.get((String) memoryId, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(10)
                        .build()
        );
    }

    /**
     * 旧版 Chat 接口: 会话式诊断 (非 Pipeline)。
     * 工具由 ToolRegistryManager 统一管理, 这里给一个空工具集的 agent 作为初始状态。
     */
    @Bean
    TingFengOpsAgent tingFengOpsAgent(ChatModel model,
                                      ChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(TingFengOpsAgent.class)
                .chatModel(model)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }

    @Bean
    TingFengChatController tingFengChatController(TingFengOpsAgent agent) {
        return new TingFengChatController(agent);
    }
}
