# TingFeng (听风) &middot; [![Java](https://img.shields.io/badge/Java-17-blue)](https://adoptium.net/) [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green)](https://spring.io/projects/spring-boot) [![License](https://img.shields.io/badge/License-Apache%202.0-orange)](LICENSE)

> **轻量级微服务智能运维 Agent —— 独立部署，零耦合，用自然语言诊断 Redis。**

听风 (TingFeng) 是一个基于 Spring Boot 3.2 和 LangChain4j 的 AIOps Sidecar Agent。通过大语言模型的 Function Calling 能力，以**独立进程**方式运行，共享目标 Redis 连接，用自然语言提供智能诊断。

---

## 项目愿景

> "让每一个微服务都拥有一个随叫随到的 AI 运维专家。"

传统运维依赖 Prometheus / Grafana 看仪表盘 —— 门槛高、响应慢。TingFeng 的核心理念：

- **自然语言驱动** —— "系统为什么慢了？" → Agent 自动查指标、分析数据、给出结论
- **Sidecar 部署，零耦合** —— 独立进程，不碰业务代码、不改 POM、不共享依赖
- **渐进式扩展** —— 从 Redis 探针开始，逐步覆盖数据库、消息队列、JVM

---

## 架构

```
┌──────────────────────┐      ┌──────────────────────────┐
│   你的业务服务         │      │   TingFeng Agent (:8081)   │
│   (苍穹外卖 / 任意)    │      │                          │
│                      │      │  ┌────────────────────┐  │
│  ┌────────────┐      │      │  │  TingFengOpsAgent  │  │
│  │   Redis    │◄─────┼──────┼─►│  (LLM + Tools)     │  │
│  │   Client   │      │ 共享  │  └────────────────────┘  │
│  └────────────┘      │ Redis │                          │
│                      │      │  GET /tingfeng/chat?msg=  │
└──────────────────────┘      └──────────────────────────┘

零依赖关系 — TingFeng 不嵌入业务服务，通过共享 Redis 连接实现诊断。
```

---

## 快速开始

### 1. 环境要求

- JDK 17+
- Redis（被诊断的目标实例）

### 2. 配置

```bash
# 方式 A：环境变量（推荐）
export AI_API_KEY=sk-your-key

# 方式 B：创建 application-dev.yml
# 见 src/main/resources/application-dev.yml
```

### 3. 启动

```bash
cd tingfeng-agent
mvn package -DskipTests

java -jar target/tingfeng-agent-0.1.0.jar \
  --spring.profiles.active=dev
```

### 4. 诊断

```bash
# 基础健康检查
curl "http://localhost:8081/tingfeng/chat?msg=检查Redis健康状况"

# 慢查询分析
curl "http://localhost:8081/tingfeng/chat?msg=帮我看看最近有没有慢查询"

# 性能排查
curl "http://localhost:8081/tingfeng/chat?msg=系统最近变慢了帮我看看什么原因"
```

### Agent 工作流示例

```
用户: "检查 Redis 健康状况"

TingFeng Agent (内部):
  1. 理解意图 → "用户想看 Redis 整体状况"
  2. 调用 getRedisMetrics()   → 命中率 100%，内存 0.7MB
  3. 调用 getRedisSlowLog(10)  → 无慢查询
  4. 分析 → "状态良好，但 maxmemory 未设置，建议配置"
  5. 返回结构化诊断报告
```

---

## 配置参考

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `AI_API_KEY` | (必填) | LLM API Key |
| `AI_BASE_URL` | `https://api.deepseek.com` | LLM 接口地址 |
| `AI_MODEL` | `deepseek-v4-flash` | 模型名称 |
| `REDIS_HOST` | `localhost` | 目标 Redis 地址 |
| `REDIS_PORT` | `6379` | 目标 Redis 端口 |
| `REDIS_PASSWORD` | (空) | Redis 密码 |
| `REDIS_DATABASE` | `0` | Redis 数据库编号 |
| `SERVER_PORT` | `8081` | Agent 服务端口 |

---

## 项目结构

```
tingfeng/
├── README.md
└── tingfeng-agent/             # 独立部署的 Agent 进程
    ├── Dockerfile
    ├── pom.xml
    └── src/main/java/com/tingfeng/agent/
        ├── TingFengAgentApplication.java
        ├── config/
        │   ├── TingFengConfig.java
        │   └── TingFengProperties.java
        ├── agent/
        │   └── TingFengOpsAgent.java
        ├── controller/
        │   └── TingFengChatController.java
        ├── tool/
        │   └── RedisDiagnosticTools.java
        └── http/
            └── DeepSeekHttpClient.java
```

---

## Docker 部署

```bash
cd tingfeng-agent
mvn package -DskipTests
docker build -t tingfeng-agent .

docker run -d -p 8081:8081 \
  -e REDIS_HOST=host.docker.internal \
  -e REDIS_PORT=6379 \
  -e REDIS_PASSWORD=yourpassword \
  -e AI_API_KEY=sk-xxx \
  tingfeng-agent
```

---

## 如何贡献

- **新增探针** —— 数据库 (MySQL/PostgreSQL)、JVM (GC/堆内存)、消息队列 (Kafka/RabbitMQ)
- **增强 Agent** —— 优化 System Prompt、多轮对话、诊断策略模板
- **多模型适配** —— Ollama / Qwen / Llama 等本地模型

---

## License

Apache License 2.0
