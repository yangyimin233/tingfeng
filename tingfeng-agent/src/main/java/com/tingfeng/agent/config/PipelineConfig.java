package com.tingfeng.agent.config;

import com.tingfeng.agent.agent.ExecutorAgent;
import com.tingfeng.agent.agent.PlannerAgent;
import com.tingfeng.agent.agent.ReporterAgent;
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
    DynamicPlannerHolder plannerAgent(ChatModel model) {
        // 初始占位 Planner, ToolRegistryManager.init() 会重建为真实版本
        PlannerAgent agent = AiServices.builder(PlannerAgent.class)
                .chatModel(model)
                .systemMessage("系统初始化中, 工具标签尚未加载...")
                .build();
        return new DynamicPlannerHolder(agent);
    }

    // ── 四类 Executor: 启动时为空壳, ToolRegistryManager.init() 自动注入工具 ──

    @Bean
    DynamicExecutorHolder mysqlExecutor(TingFengProperties props, ChatModel cloudModel) {
        ChatModel model = selectModel(props, cloudModel);
        ExecutorAgent agent = AiServices.builder(ExecutorAgent.class)
                .chatModel(model).build(); // placeholder, 由 ToolRegistryManager 重建
        return new DynamicExecutorHolder(agent);
    }

    @Bean
    DynamicExecutorHolder redisExecutor(TingFengProperties props, ChatModel cloudModel) {
        ChatModel model = selectModel(props, cloudModel);
        ExecutorAgent agent = AiServices.builder(ExecutorAgent.class)
                .chatModel(model).build();
        return new DynamicExecutorHolder(agent);
    }

    @Bean
    DynamicExecutorHolder systemExecutor(TingFengProperties props, ChatModel cloudModel) {
        ChatModel model = selectModel(props, cloudModel);
        ExecutorAgent agent = AiServices.builder(ExecutorAgent.class)
                .chatModel(model).build();
        return new DynamicExecutorHolder(agent);
    }

    @Bean
    DynamicExecutorHolder snapshotExecutor(TingFengProperties props, ChatModel cloudModel) {
        ChatModel model = selectModel(props, cloudModel);
        ExecutorAgent agent = AiServices.builder(ExecutorAgent.class)
                .chatModel(model).build();
        return new DynamicExecutorHolder(agent);
    }

    @Bean
    DynamicExecutorHolder fullExecutor(TingFengProperties props, ChatModel cloudModel) {
        ChatModel model = selectModel(props, cloudModel);
        ExecutorAgent agent = AiServices.builder(ExecutorAgent.class)
                .chatModel(model).build();
        return new DynamicExecutorHolder(agent);
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
