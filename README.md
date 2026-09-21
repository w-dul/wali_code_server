# WaLiCode Server - ai coding 辅助编码

> 先启动服务端，SSH 操作和 AI 操作统一放在服务端，可以更有效地管控风险（企业常见做法）。如果都在客户端，可能有人误操作执行危险命令，或泄露核心服务器信息。

---

## 1. 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.4.3 |
| 构建 | Maven | 3.8.x |
| 数据库 | MySQL | 8.0.x |
| ORM | MyBatis | 3.0.4 |
| AI 框架 | Google ADK | 1.2.0 |
| AI 框架 | Spring AI | 1.1.5 |
| AI 框架 | LangChain4j | 1.4.0 |
| 设计模式 | xfg-wrench 策略树框架 | 3.0.0 |
| 工具库 | Lombok / Fastjson2 / Guava / HikariCP | — |

---

## 2. 工程结构

```
walicode-server
├── pom.xml                          # 父 POM（依赖管理 + 模块声明）
├── docs/
│   └── dev-ops/                     # Docker 部署文件
│       ├── docker-compose-environment.yml    # 基础环境（MySQL + Redis + phpMyAdmin + RedisAdmin）
│       ├── docker-compose-environment-aliyun.yml  # 阿里云镜像版
│       └── docker-compose-app.yml            # 应用部署
│       └── mysql/sql/walissh.sql             # 建库建表 SQL
├── walicode-server-app/             # 启动模块（配置、入口、Agent YAML）
├── walicode-server-api/             # 对外接口定义
├── walicode-server-domain/          # 领域层（Agent 装配、对话、意图识别）
├── walicode-server-infrastructure/  # 基础设施层（持久化、外部适配）
├── walicode-server-trigger/         # 触发器层（HTTP Controller、Job）
├── walicode-server-case/            # 用例层（ReAct 执行引擎、SSE 流式输出）
└── walicode-server-types/           # 通用类型定义
```

### 模块职责

| 模块 | 职责 |
|------|------|
| **app** | Spring Boot 启动入口、YAML 配置、Agent 定义文件 |
| **api** | 对外暴露的接口契约 |
| **domain** | 核心领域逻辑：Agent 装配（ArmoryService）、对话（ChatService）、意图识别 |
| **infrastructure** | 数据库访问、MyBatis Mapper、外部服务适配 |
| **trigger** | HTTP 接口层（SSH 连接、终端、AI 对话等 Controller） |
| **case** | ReAct 执行引擎、SSE 流式输出、工具调用节点 |
| **types** | 通用常量、异常、基础 VO |

---

## 3. 导入库表

### 3.1 自动导入（推荐）

使用 Docker Compose 启动 MySQL 时，建表 SQL 会自动执行：

```bash
cd docs/dev-ops
docker-compose -f docker-compose-environment.yml up -d
```

`docker-compose-environment.yml` 中配置了：

```yaml
volumes:
  - ./mysql/sql:/docker-entrypoint-initdb.d   # 自动执行 SQL
```

MySQL 启动后会自动创建 `walissh` 库并导入所有表。

### 3.2 手动导入

如果不用 Docker 或需要手动建表：

```bash
mysql -u root -p < docs/dev-ops/mysql/sql/walissh.sql
```

SQL 文件会自动 `CREATE DATABASE IF NOT EXISTS walissh`，无需手动建库。

### 3.3 库表说明

| 表名 | 用途 |
|------|------|
| `chat_message` | 对话消息记录（角色、内容、Token 计数、工具调用信息） |
| `chat_session` | 对话会话 |
| `chat_milestone` | 对话里程碑标记 |
| `ssh_connection` | SSH 连接信息 |
| `ssh_connection_config` | SSH 连接配置 |

---

## 4. 配置大模型

大模型配置有两处：**Agent YAML** 和 **application-dev.yml**。

### 4.1 Agent YAML（主要）

Agent YAML 定义了每个智能体使用的大模型。文件位于 `walicode-server-app/src/main/resources/agent/` 下：

| 文件 | 用途 | 默认模型 |
|------|------|---------|
| `ssh-agent.yml` | SSH 运维助手 | `agnes-2.0-flash`（via apihub.agnes-ai.com） |
| `code-agent.yml` | AI 编程助手 | `agnes-2.0-flash`（via apihub.agnes-ai.com） |
| `demo.yml` | 演示模板 | `gpt-4.1`（via apis.itedus.cn） |
| `only-one-agent.yml` | 单智能体 | — |
| `test-agent.yml` | 测试用 | — |
| `parallel_research_app.yml` | 并行研究 | — |

**切换/配置模型**：修改对应 Agent YAML 中的 `ai-api` 和 `chat-model` 节：

```yaml
ai:
  agent:
    config:
      tables:
        sshAgent:
          module:
            ai-api:
              base-url: https://apihub.agnes-ai.com        # API 地址
              api-key: sk-xxxxx                              # API Key
              completions-path: v1/chat/completions          # 对话路径
              embeddings-path: v1/embeddings                 # 向量化路径
            chat-model:
              model: agnes-2.0-flash                         # 模型名称
```

**已验证可用的模型配置示例**：

| 平台 | base-url | 模型 |
|------|----------|------|
| Agnes AI | `https://apihub.agnes-ai.com` | `agnes-2.0-flash` |
| 讯飞星火 | `https://maas-coding-api.cn-huabei-1.xf-yun.com` | `xopglm51`（需用 `/v2/` 路径） |
| 通用 OpenAI 兼容 | `https://apis.itedus.cn` | `gpt-4.1`、`gpt-5.1` 等 |

### 4.2 application-dev.yml（意图识别）

`application-dev.yml` 中的 `intent-ai-api` 用于意图识别模块，独立于 Agent 模型：

```yaml
intent-ai-api:
  base-url: https://maas-coding-api.cn-huabei-1.xf-yun.com
  api-key: xxxx:xxxx
  completions-path: v2/chat/completions
  embeddings-path: v2/embeddings
  chat-model:
    model: xopglm51
```

### 4.3 启用哪个 Agent

在 `application-dev.yml` 中通过 `spring.config.import` 指定：

```yaml
spring:
  config:
    import:
      - classpath:agent/code-agent.yml    # 当前启用的 Agent
      # - classpath:agent/ssh-agent.yml   # 取消注释切换为 SSH Agent
      # - classpath:agent/demo.yml        # 取消注释切换为 Demo
```

**一次只能启用一个 Agent**，注释掉其他的即可。

---

## 5. 启动步骤

### 5.1 前置条件

- JDK 17+
- Maven 3.8.x
- Docker & Docker Compose（用于启动 MySQL/Redis）

### 5.2 启动基础环境

```bash
cd docs/dev-ops

# 启动 MySQL + Redis + 管理面板
docker-compose -f docker-compose-environment.yml up -d
```

启动后：
- MySQL：`localhost:13306`，用户 `root`，密码 `123456`，数据库 `walissh`
- phpMyAdmin：`localhost:8899`
- Redis：`localhost:16379`
- RedisAdmin：`localhost:8081`（用户 `admin`，密码 `admin`）

### 5.3 修改配置

编辑 `walicode-server-app/src/main/resources/application-dev.yml`：

1. **数据库连接**（如不用 Docker 默认端口，需修改）：
   ```yaml
   spring:
     datasource:
       url: jdbc:mysql://192.168.1.108:13306/walissh?useUnicode=true&characterEncoding=utf8&autoReconnect=true&zeroDateTimeBehavior=convertToNull&serverTimezone=UTC&useSSL=true
       username: root
       password: 123456
   ```

2. **Agent 模型配置**：编辑对应的 `agent/*.yml`，填入你的 API Key 和模型名称。

3. **意图识别模型**：编辑 `application-dev.yml` 中的 `intent-ai-api` 节。

4. **选择启用的 Agent**：修改 `spring.config.import` 注释/取消注释对应行。

### 5.4 编译启动

```bash
# 在项目根目录
mvn clean install -DskipTests

# 启动应用
cd walicode-server-app
mvn spring-boot:run
```

或直接运行主类 `cn.bugstack.ai.Application`。

启动成功后服务监听 **8091** 端口。

### 5.5 Docker 部署（生产/测试环境）

```bash
cd docs/dev-ops

# 先启动基础环境
docker-compose -f docker-compose-environment.yml up -d

# 再启动应用
docker-compose -f docker-compose-app.yml up -d
```

`docker-compose-app.yml` 中通过环境变量覆盖配置（API Key、数据库地址等），无需修改代码。

---

## 6. 核心架构

### 6.1 DDD 分层

```
Trigger（HTTP/Job）
    ↓
App（配置/启动）
    ↓
Domain（核心业务）
  ├── Agent 装配（ArmoryService）— 读取 YAML → 构建智能体
  ├── 对话管理（ChatService）— 会话 + 消息持久化
  └── 意图识别 — 分级路由到不同 Agent
    ↓
Case（ReAct 执行引擎）
  ├── RootNode → TaskBreakdownNode → LoopDecisionNode
  ├── AiCallNode → ToolCallNode → UserFeedbackNode
  └── SSE 流式输出
    ↓
Infrastructure（MyBatis + 外部适配）
```

### 6.2 Agent 运行机制

1. **装配阶段**：`ArmoryService` 读取 Agent YAML → 策略树构建智能体
2. **对话阶段**：`ChatService` 接收用户消息 → 持久化 → 提交到 ReAct 引擎
3. **执行阶段**：ReAct 引擎循环执行（分析 → 规划 → 调用工具 → 反思）→ SSE 流式输出
4. **工具调用**：`SshExecuteMcpService`（SSH 命令）/ `SshExecuteAdkTool`（ADK 工具）执行实际操作

### 6.3 Agent YAML 配置结构

```yaml
ai:
  agent:
    config:
      tables:
        <agentName>:              # Agent 名称
          app-name: <name>        # 应用名
          agent:                  # 基础信息（ID、名称、描述）
          module:
            ai-api: ...           # 大模型 API 配置
            chat-model: ...       # 模型选择 + MCP 工具 + Skills
            agents: [...]         # 子智能体定义（指令 + 输出键）
            agent-workflows: [...] # 工作流编排（loop/parallel/sequential）
          runner:
            agent-name: <name>    # 入口智能体
            react-budget: ...     # ReAct 执行预算（步数、超时等）
```

### 6.4 已内置的 MCP 工具

| Bean 名称 | 类 | 用途 |
|-----------|-----|------|
| `myToolCallbackProvider` | `MyTestMcpService` | 测试工具（大小写转换） |
| `sshToolCallbackProvider` | `SshExecuteMcpService` | SSH 命令执行 |

---

## 7. API 端口

服务启动后监听 `8091` 端口，主要 Controller：

| Controller | 路径前缀 | 用途 |
|-----------|---------|------|
| `AgentServiceController` | `/api/agent` | 智能体配置查询 |
| `SshAgentController` | `/api/ssh/agent` | SSH AI 对话 |
| `SshConnectionController` | `/api/ssh/connection` | SSH 连接管理 |
| `SshTerminalController` | `/api/ssh/terminal` | SSH 终端操作 |
| `SshFileController` | `/api/ssh/file` | SSH 文件操作 |
| `PermissionResolveController` | `/api/permission` | 权限确认 |

---

## 8. 常见问题

**Q: 如何切换大模型？**
编辑对应 `agent/*.yml` 的 `ai-api` 和 `chat-model` 节，修改 `base-url`、`api-key`、`model` 即可。

**Q: 如何新增一个 Agent？**
1. 在 `resources/agent/` 下新建 YAML 文件，参考 `demo.yml` 模板
2. 在 `application-dev.yml` 的 `spring.config.import` 中引入
3. 如需新的 MCP 工具，在 `Application.java` 中注册 `@Bean`

**Q: 数据库连接报错？**
检查 `application-dev.yml` 中的 `spring.datasource.url`，确保 MySQL 地址和端口正确。Docker 默认映射到 `13306` 端口。

**Q: 意图识别和 Agent 模型可以不一样吗？**
可以。`intent-ai-api` 和 Agent YAML 中的 `ai-api` 是独立配置，可以用不同平台和模型。
