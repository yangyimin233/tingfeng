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

    @Bean
    ExecutorAgent executorAgent(TingFengProperties props,
                                 ChatModel cloudModel,
                                 RedisDiagnosticTools redisTools,
                                 McpToolProvider mcpToolProvider) {
        ChatModel model = isLocal(props)
                ? buildOllamaModel(props.getOllama())
                : cloudModel;
        log.info("Executor Agent 模型: {}", isLocal(props) ? "Ollama (本地)" : "DeepSeek (云端)");
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

    private boolean isLocal(TingFengProperties props) {
        return "local".equalsIgnoreCase(props.getExecutor().getProvider());
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
