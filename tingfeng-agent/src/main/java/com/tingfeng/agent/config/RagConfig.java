package com.tingfeng.agent.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RagConfig {

    @Bean
    EmbeddingModel embeddingModel(TingFengProperties props) {
        var ollama = props.getOllama();
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollama.getBaseUrl().replace("/v1", ""))
                .modelName(ollama.getEmbeddingModel())
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}
