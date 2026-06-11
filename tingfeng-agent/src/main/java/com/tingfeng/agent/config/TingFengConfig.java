package com.tingfeng.agent.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tingfeng.agent.agent.TingFengOpsAgent;
import com.tingfeng.agent.controller.TingFengChatController;
import com.tingfeng.agent.http.DeepSeekHttpClient;
import com.tingfeng.agent.tool.RedisDiagnosticTools;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    // ── MCP Client Bean: MySQL ──

    @Bean
    McpClient mysqlMcpClient(TingFengProperties props) {
        java.util.Map<String, String> env = java.util.Map.of(
                "MYSQL_HOST", props.getMysql().getHost(),
                "MYSQL_PORT", String.valueOf(props.getMysql().getPort()),
                "MYSQL_USER", props.getMysql().getUser(),
                "MYSQL_PASS", props.getMysql().getPass(),
                "MYSQL_DB", props.getMysql().getDb()
        );
        McpClient client = tryBuiltInMcpServer(env);
        if (client != null) {
            log.info("MySQL MCP Server (自研版) 就绪, 连接 {}:{}", props.getMysql().getHost(), props.getMysql().getPort());
            return client;
        }
        client = tryCommunityMcpServer(env);
        if (client != null) {
            log.info("MySQL MCP Server (社区版) 就绪, 连接 {}:{}", props.getMysql().getHost(), props.getMysql().getPort());
            return client;
        }
        log.warn("MySQL MCP Server 全部启动失败");
        return null;
    }

    @Bean
    McpToolProvider mysqlMcpToolProvider(McpClient mysqlMcpClient) {
        return buildProvider(mysqlMcpClient);
    }

    // ── MCP Client Bean: CPU ──

    @Bean
    McpClient cpuMcpClient() {
        try {
            String classpath = System.getProperty("java.class.path");
            String javaExe = ProcessHandle.current()
                    .info().command()
                    .orElse(System.getProperty("java.home") + "/bin/java");
            log.info("启动 CPU MCP Server: {} ... CpuMcpServer", javaExe);
            return DefaultMcpClient.builder()
                    .key("cpu-mcp")
                    .transport(StdioMcpTransport.builder()
                            .command(List.of(javaExe, "-cp", classpath,
                                    "com.tingfeng.agent.mcp.CpuMcpServer"))
                            .logEvents(true)
                            .build())
                    .build();
        } catch (Exception e) {
            log.warn("CPU MCP Server 启动失败: {}", e.getMessage());
            return null;
        }
    }

    @Bean
    McpToolProvider cpuMcpToolProvider(McpClient cpuMcpClient) {
        return buildProvider(cpuMcpClient);
    }

    // ── MCP Client: Snapshot (探针元数据 Resource 服务) ──

    @Bean
    McpClient snapshotMcpClient(TingFengPersistenceProperties persistProps) {
        if (!persistProps.isConfigured()) {
            log.info("探针持久化未配置, 跳过 Snapshot MCP Server");
            return null;
        }
        try {
            String classpath = System.getProperty("java.class.path");
            String javaExe = ProcessHandle.current()
                    .info().command()
                    .orElse(System.getProperty("java.home") + "/bin/java");
            log.info("启动 Snapshot MCP Server (Resource)");
            return DefaultMcpClient.builder()
                    .key("snapshot-mcp")
                    .transport(StdioMcpTransport.builder()
                            .command(List.of(javaExe, "-cp", classpath,
                                    "com.tingfeng.agent.mcp.SnapshotMcpServer"))
                            .environment(java.util.Map.of(
                                    "PERSISTENCE_URL", persistProps.getUrl(),
                                    "PERSISTENCE_USER", persistProps.getUsername(),
                                    "PERSISTENCE_PASS", persistProps.getPassword()))
                            .logEvents(true)
                            .build())
                    .build();
        } catch (Exception e) {
            log.warn("Snapshot MCP Server 启动失败: {}", e.getMessage());
            return null;
        }
    }

    @Bean
    McpToolProvider snapshotMcpToolProvider(McpClient snapshotMcpClient) {
        return buildProvider(snapshotMcpClient);
    }

    // ── 全工具 McpToolProvider (fullExecutor 兜底) ──

    @Bean
    McpToolProvider mcpToolProvider(McpClient mysqlMcpClient,
                                     McpClient cpuMcpClient,
                                     McpClient snapshotMcpClient) {
        List<McpClient> clients = new ArrayList<>();
        if (mysqlMcpClient != null) clients.add(mysqlMcpClient);
        if (cpuMcpClient != null) clients.add(cpuMcpClient);
        if (snapshotMcpClient != null) clients.add(snapshotMcpClient);
        return McpToolProvider.builder()
                .mcpClients(clients.toArray(McpClient[]::new))
                .failIfOneServerFails(false)
                .build();
    }

    private McpClient tryCommunityMcpServer(java.util.Map<String, String> env) {
        try {
            log.info("尝试启动社区版 MySQL MCP Server: npx @benborla29/mcp-server-mysql");
            return DefaultMcpClient.builder()
                    .key("mysql-mcp-community")
                    .transport(StdioMcpTransport.builder()
                            .command(List.of("npx.cmd", "-y", "@benborla29/mcp-server-mysql"))
                            .environment(env)
                            .logEvents(true)
                            .build())
                    .build();
        } catch (Exception e) {
            log.warn("社区版 MySQL MCP Server 启动失败: {}", e.getMessage());
            return null;
        }
    }

    private McpClient tryBuiltInMcpServer(java.util.Map<String, String> env) {
        try {
            String classpath = System.getProperty("java.class.path");
            String javaExe = ProcessHandle.current()
                    .info().command()
                    .orElse(System.getProperty("java.home") + "/bin/java");

            log.info("尝试启动自研 MySQL MCP Server: {}", javaExe);
            return DefaultMcpClient.builder()
                    .key("mysql-mcp-builtin")
                    .transport(StdioMcpTransport.builder()
                            .command(List.of(
                                    javaExe,
                                    "-cp", classpath,
                                    "com.tingfeng.agent.mcp.MySqlMcpServer"
                            ))
                            .environment(env)
                            .logEvents(true)
                            .build())
                    .build();
        } catch (Exception e) {
            log.warn("自研 MySQL MCP Server 启动失败: {}", e.getMessage());
            return null;
        }
    }

    private static McpToolProvider buildProvider(McpClient client) {
        return McpToolProvider.builder()
                .mcpClients(client)
                .failIfOneServerFails(false)
                .build();
    }

    @Bean
    TingFengOpsAgent tingFengOpsAgent(ChatModel model,
                                      RedisDiagnosticTools redisTools,
                                      McpToolProvider mcpToolProvider,
                                      ChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(TingFengOpsAgent.class)
                .chatModel(model)
                .tools(redisTools)              // 本地 Redis 诊断工具
                .toolProvider(mcpToolProvider)   // MCP MySQL 诊断工具（自动发现）
                .chatMemoryProvider(chatMemoryProvider)
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
