package cn.bugstack.ai.domain.agent.service.subagent;

import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子代理管理器（对标 WaLiCode agentService.ts + executeSubAgent）
 * <p>
 * 三类内置子代理：
 * - Explore：只读探索（搜索/阅读代码，不修改文件）
 * - Verification：验证代理（检查实现，可执行命令但不修改文件）
 * - General：通用代理（完整工具访问）
 * <p>
 * 核心约束：
 * - 最多 3 轮（MAX_ROUNDS=3），防止无限循环
 * - 独立 context，不污染父对话
 * - 只读代理禁止写文件/删除操作
 * - 结果通过 tool result 返回给父对话
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class SubAgentManager {

    @Lazy
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    /** 最大执行轮数 */
    private static final int MAX_ROUNDS = 3;

    /** 子代理最大执行时间（毫秒） */
    private static final long MAX_DURATION_MS = 120_000L;

    /** 运行中的子代理 */
    private final Map<String, RunningAgent> runningAgents = new ConcurrentHashMap<>();

    private int agentIdCounter = 0;

    // ═══════════════════════════════════════════════════════════════
    //  内置代理定义
    // ═══════════════════════════════════════════════════════════════

    public enum AgentType {
        EXPLORE("只读代码探索代理"),
        VERIFICATION("验证代理，检查实现是否符合要求"),
        GENERAL("通用代理，完整工具访问");

        private final String description;

        AgentType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AgentDefinition {
        private AgentType agentType;
        private String whenToUse;
        private String description;
        private List<String> allowedTools;
        private List<String> disallowedTools;
        private boolean readOnly;
    }

    /** 内置代理定义 */
    private static final List<AgentDefinition> BUILT_IN_AGENTS = List.of(
            AgentDefinition.builder()
                    .agentType(AgentType.EXPLORE)
                    .whenToUse("探索代码库、搜索文件、阅读代码、理解架构，不修改任何文件")
                    .description("只读代码探索代理")
                    .allowedTools(List.of("readFile", "listFiles", "searchFiles", "searchCode",
                            "GlobTool", "GrepTool", "WebFetchTool", "WebSearchTool"))
                    .disallowedTools(List.of("writeFile", "FileEditTool", "deleteFile",
                            "executeCommand", "executeLocalCommand", "ssh_write_file", "ssh_edit_file"))
                    .readOnly(true)
                    .build(),
            AgentDefinition.builder()
                    .agentType(AgentType.VERIFICATION)
                    .whenToUse("验证实现是否正确，进行对抗性测试，检查边界情况")
                    .description("验证代理，检查实现是否符合要求")
                    .allowedTools(List.of("readFile", "listFiles", "searchFiles", "searchCode",
                            "GlobTool", "GrepTool", "executeLocalCommand"))
                    .disallowedTools(List.of("writeFile", "FileEditTool", "deleteFile"))
                    .readOnly(true)
                    .build(),
            AgentDefinition.builder()
                    .agentType(AgentType.GENERAL)
                    .whenToUse("通用任务代理，可读写文件、执行命令，用于复杂多步骤任务")
                    .description("通用代理，完整工具访问")
                    .allowedTools(List.of()) // 空列表表示全部可用
                    .disallowedTools(List.of())
                    .readOnly(false)
                    .build()
    );

    // ═══════════════════════════════════════════════════════════════
    //  运行中代理管理
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RunningAgent {
        private String id;
        private AgentType agentType;
        private String name;
        private long startTime;
        private volatile boolean aborted;
    }

    /**
     * 启动子代理
     *
     * @param agentType  代理类型
     * @param prompt     任务描述
     * @param name       代理名称（可选）
     * @param agentId    父 Agent ID（用于获取 Runner）
     * @param userId     用户 ID
     * @param sessionId  会话 ID（子代理使用独立 sessionId）
     * @return 执行结果
     */
    public AgentResult execute(AgentType agentType, String prompt, String name,
                                String agentId, String userId, String sessionId) {
        long startTime = System.currentTimeMillis();

        // 1. 查找代理定义
        AgentDefinition agentDef = BUILT_IN_AGENTS.stream()
                .filter(a -> a.getAgentType() == agentType)
                .findFirst()
                .orElse(null);
        if (agentDef == null) {
            return AgentResult.failure(agentType, name, "未找到代理定义: " + agentType, 0);
        }

        // 2. 生成子代理 ID 和 sessionId
        String subAgentId = generateAgentId();
        String subSessionId = sessionId + "_sub_" + subAgentId;
        String agentName = name != null ? name : agentType.name().toLowerCase();

        RunningAgent running = RunningAgent.builder()
                .id(subAgentId)
                .agentType(agentType)
                .name(agentName)
                .startTime(startTime)
                .aborted(false)
                .build();
        runningAgents.put(subAgentId, running);

        log.info("子代理启动: id={}, type={}, name={}, session={}", subAgentId, agentType, agentName, subSessionId);

        try {
            // 3. 获取父 Agent 的 Runner（复用 ADK Runner）
            AiAgentRegisterVO register = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
            if (register == null) {
                return AgentResult.failure(agentType, agentName, "父 Agent 未注册: " + agentId,
                        System.currentTimeMillis() - startTime);
            }
            Runner runner = register.getRunner();

            // 4. 构建子代理 system prompt
            String systemPrompt = buildAgentSystemPrompt(agentDef);

            // 5. 构建初始消息
            String initialMessage = systemPrompt + "\n\n## 任务\n" + prompt;
            Content userContent = Content.builder()
                    .role("user")
                    .parts(Part.builder().text(initialMessage).build())
                    .build();

            // 6. 执行子代理循环（最多 MAX_ROUNDS 轮）
            StringBuilder resultBuilder = new StringBuilder();
            int totalToolCalls = 0;
            int round;

            for (round = 0; round < MAX_ROUNDS; round++) {
                // 超时检查
                if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                    log.warn("子代理超时: id={}, rounds={}", subAgentId, round);
                    resultBuilder.append("(Agent 超时)");
                    break;
                }

                // 取消检查
                if (running.isAborted()) {
                    log.info("子代理被取消: id={}", subAgentId);
                    resultBuilder.append("(Agent 已取消)");
                    break;
                }

                // 调用 ADK Runner
                String roundText = "";
                int roundToolCalls = 0;

                try {
                    Iterator<Event> events = runner.runAsync(
                            userId,
                            subSessionId,
                            userContent,
                            RunConfig.builder()
                                    .streamingMode(RunConfig.StreamingMode.SSE)
                                    .autoCreateSession(true)
                                    .build()
                    ).blockingIterable().iterator();

                    while (events.hasNext()) {
                        if (running.isAborted()) break;

                        Event event = events.next();
                        String text = event.stringifyContent();
                        if (!text.isBlank()) {
                            roundText += text;
                        }

                        // 检测工具调用
                        var functionCalls = event.functionCalls();
                        if (functionCalls != null && !functionCalls.isEmpty()) {
                            roundToolCalls += functionCalls.size();
                        }
                    }
                } catch (Exception e) {
                    log.error("子代理 ADK Runner 调用失败: round={}, error={}", round, e.getMessage());
                    if (round == 0) {
                        return AgentResult.failure(agentType, agentName,
                                "ADK Runner error: " + e.getMessage(),
                                System.currentTimeMillis() - startTime);
                    }
                    break;
                }

                totalToolCalls += roundToolCalls;

                // 无工具调用 → 返回文本
                if (roundToolCalls == 0) {
                    resultBuilder.append(roundText.isEmpty() ? "(无输出)" : roundText);
                    break;
                }

                // 有工具调用 → 记录文本，继续下一轮
                if (!roundText.isEmpty()) {
                    resultBuilder.append(roundText).append("\n");
                }

                // 最后一轮仍有工具调用
                if (round == MAX_ROUNDS - 1) {
                    resultBuilder.append("(Agent 达到最大轮数限制)");
                    log.warn("子代理达到最大轮数: id={}, maxRounds={}", subAgentId, MAX_ROUNDS);
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;
            String output = resultBuilder.toString().trim();

            log.info("子代理完成: id={}, type={}, rounds={}, toolCalls={}, durationMs={}, outputLen={}",
                    subAgentId, agentType, round + 1, totalToolCalls, durationMs, output.length());

            return AgentResult.success(agentType, agentName, output, durationMs, totalToolCalls, round + 1);

        } catch (Exception e) {
            log.error("子代理执行异常: id={}, error={}", subAgentId, e.getMessage(), e);
            return AgentResult.failure(agentType, agentName,
                    "Agent error: " + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        } finally {
            runningAgents.remove(subAgentId);
        }
    }

    /**
     * 取消子代理
     */
    public boolean abort(String agentId) {
        RunningAgent agent = runningAgents.get(agentId);
        if (agent != null) {
            agent.setAborted(true);
            log.info("子代理取消请求: id={}", agentId);
            return true;
        }
        return false;
    }

    /**
     * 获取运行中的子代理列表
     */
    public List<RunningAgent> getRunningAgents() {
        return new ArrayList<>(runningAgents.values());
    }

    /**
     * 获取内置代理定义列表
     */
    public List<AgentDefinition> getBuiltInAgents() {
        return BUILT_IN_AGENTS;
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    private String generateAgentId() {
        agentIdCounter++;
        return "agent_" + System.currentTimeMillis() + "_" + agentIdCounter;
    }

    /**
     * 构建子代理 system prompt
     */
    private String buildAgentSystemPrompt(AgentDefinition agentDef) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 WaLiCode 的").append(agentDef.getDescription()).append("。你正在执行一个子任务。\n\n");
        sb.append("## 行为准则\n");
        sb.append("- 专注于分配给你的任务，不要偏离\n");
        sb.append("- 结果要精炼、有结构，便于主对话整合\n");
        sb.append("- 如果发现任务无法完成，明确说明原因\n");
        sb.append("- 不要重复主对话中已知的背景信息\n");

        if (agentDef.isReadOnly()) {
            sb.append("\n## 只读限制\n");
            sb.append("你是只读代理，不能修改任何项目文件。");
            sb.append("如果任务需要修改文件，请报告这个限制并描述需要做什么修改。\n");
        }

        sb.append("\n## 轮次限制\n");
        sb.append("你最多有 ").append(MAX_ROUNDS).append(" 轮交互机会，请高效利用。\n");

        return sb.toString();
    }

    /**
     * 格式化代理列表为 AI prompt 中的描述
     */
    public String formatAgentListForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (AgentDefinition agent : BUILT_IN_AGENTS) {
            String toolsDesc = agent.getAllowedTools().isEmpty()
                    ? "全部工具"
                    : String.join(", ", agent.getAllowedTools());
            sb.append("- ").append(agent.getAgentType().name())
                    .append(": ").append(agent.getWhenToUse())
                    .append(" (Tools: ").append(toolsDesc).append(")\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  结果数据结构
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AgentResult {
        private AgentType agentType;
        private String name;
        private boolean success;
        private String output;
        private long durationMs;
        private int totalToolCalls;
        private int roundsUsed;

        public static AgentResult success(AgentType type, String name, String output,
                                           long durationMs, int toolCalls, int rounds) {
            return AgentResult.builder()
                    .agentType(type)
                    .name(name)
                    .success(true)
                    .output(output)
                    .durationMs(durationMs)
                    .totalToolCalls(toolCalls)
                    .roundsUsed(rounds)
                    .build();
        }

        public static AgentResult failure(AgentType type, String name, String error, long durationMs) {
            return AgentResult.builder()
                    .agentType(type)
                    .name(name)
                    .success(false)
                    .output(error)
                    .durationMs(durationMs)
                    .totalToolCalls(0)
                    .roundsUsed(0)
                    .build();
        }

        /**
         * 格式化为 tool result 返回给父对话
         */
        public String toToolResult() {
            return String.format("[Agent: %s (%s)] %s in %dms, %d tool calls, %d rounds\n\n%s",
                    name, agentType, success ? "completed" : "failed",
                    durationMs, totalToolCalls, roundsUsed, output);
        }
    }
}
