package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.ExecutorAgent;
import com.tingfeng.agent.agent.PlannerAgent;
import com.tingfeng.agent.agent.ReporterAgent;
import com.tingfeng.agent.tool.RedisDiagnosticTools;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class PipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfig.class);

    @Bean
    PlannerAgent plannerAgent(ChatModel model) {
        return AiServices.builder(PlannerAgent.class)
                .chatModel(model)
                .build();
    }

    // ── 三类 Executor: 按工具分组, 减少 token 浪费 ──

    @Bean
    ExecutorAgent mysqlExecutor(TingFengProperties props,
                                 ChatModel cloudModel,
                                 @Qualifier("mysqlMcpToolProvider") McpToolProvider mcpToolProvider) {
        ChatModel model = selectModel(props, cloudModel);
        log.info("MySQL Executor 模型: {}", modelLabel(props));
        return AiServices.builder(ExecutorAgent.class)
                .chatModel(model)
                .toolProvider(mcpToolProvider)
                .build();
    }

    @Bean
    ExecutorAgent redisExecutor(TingFengProperties props,
                                 ChatModel cloudModel,
                                 RedisDiagnosticTools redisTools) {
        ChatModel model = selectModel(props, cloudModel);
        log.info("Redis Executor 模型: {}", modelLabel(props));
        return AiServices.builder(ExecutorAgent.class)
                .chatModel(model)
                .tools(redisTools)
                .build();
    }

    @Bean
    ExecutorAgent systemExecutor(TingFengProperties props,
                                  ChatModel cloudModel,
                                  @Qualifier("cpuMcpToolProvider") McpToolProvider cpuProvider,
                                  @Qualifier("snapshotMcpToolProvider") McpToolProvider snapshotProvider) {
        ChatModel model = selectModel(props, cloudModel);
        log.info("System Executor 模型: {}", modelLabel(props));
        return AiServices.builder(ExecutorAgent.class)
                .chatModel(model)
                .toolProvider(cpuProvider)
                .toolProvider(snapshotProvider)
                .build();
    }

    @Bean
    ExecutorAgent fullExecutor(TingFengProperties props,
                                ChatModel cloudModel,
                                RedisDiagnosticTools redisTools,
                                McpToolProvider mcpToolProvider,
                                @Qualifier("snapshotMcpToolProvider") McpToolProvider snapshotProvider) {
        ChatModel model = selectModel(props, cloudModel);
        log.info("Full Executor 模型: {}", modelLabel(props));
        return AiServices.builder(ExecutorAgent.class)
                .chatModel(model)
                .tools(redisTools)
                .toolProvider(mcpToolProvider)
                .toolProvider(snapshotProvider)
                .build();
    }

    @Bean
    ReporterAgent reporterAgent(ChatModel model) {
        return AiServices.builder(ReporterAgent.class)
                .chatModel(model)
                .build();
    }

    // ── 私有方法 ──

    private boolean isLocal(TingFengProperties props) {
        return "local".equalsIgnoreCase(props.getExecutor().getProvider());
    }

    private String modelLabel(TingFengProperties props) {
        return isLocal(props) ? "Ollama (本地)" : "DeepSeek (云端)";
    }

    private ChatModel selectModel(TingFengProperties props, ChatModel cloudModel) {
        return isLocal(props) ? buildOllamaModel(props.getOllama()) : cloudModel;
    }

    private ChatModel buildOllamaModel(TingFengProperties.Ollama ollama) {
        return OpenAiChatModel.builder()
                .baseUrl(ollama.getBaseUrl())
                .modelName(ollama.getModelName())
                .apiKey("ollama")
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
