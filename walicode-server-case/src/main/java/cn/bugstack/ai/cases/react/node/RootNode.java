package cn.bugstack.ai.cases.react.node;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.react.AbstractAIAgentReActSupport;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.bugstack.ai.domain.agent.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.agent.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.bugstack.ai.domain.agent.service.IIntentService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct Root Node（根节点）
 *
 * <p>职责：
 * 1. 从 ChatRequestDTO 提取会话参数
 * 2. 初始化 DynamicContext
 * 3. 绑定终端会话 ID（ThreadLocal）
 * 4. 路由到 AiCallNode
 *
 * <p>节点链：
 * RootNode → AiCallNode → ToolCallNode → ToolResultNode → LoopDecisionNode
 *                                                   ↑__________________|
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4 13:57
 */
@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private IIntentService intentService;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;
    private static final int DEFAULT_MAX_AI_RETRIES = 2;
    private static final long DEFAULT_TOOL_TIMEOUT_MS = 60_000L;
    private static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 8000;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct RootNode - 初始化上下文");

        // 1. 提取会话参数
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        String message = requestParameter.getMessage();

        // 2. 绑定终端会话（ThreadLocal + sessionMapping 双通道）
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            setCurrentTerminalSession(terminalSessionId);
            bindTerminalSession(sessionId, terminalSessionId);
        } else {
            // 尝试从会话绑定中获取
            String boundTerminal = getTerminalSession(sessionId);
            if (boundTerminal != null) {
                setCurrentTerminalSession(boundTerminal);
            }
        }

        // [Phase 5] 自动创建/更新会话元数据
        ChatSessionEntity existingSession = chatHistoryRepository.getSession(sessionId);
        if (existingSession == null) {
            chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                    .id(sessionId)
                    .agentId(agentId)
                    .userId(userId)
                    .title(message != null && message.length() > 50 ? message.substring(0, 50) : message)
                    .messageCount(0)
                    .build());
            log.info("[Phase 5] 自动创建会话记录: sessionId={}, agentId={}", sessionId, agentId);
        }

        // 3. 初始化上下文
        dynamicContext.setSessionId(sessionId);
        dynamicContext.setUserId(userId);
        dynamicContext.setAgentId(agentId);
        dynamicContext.setTerminalSessionId(terminalSessionId);
        dynamicContext.setProjectContext(requestParameter.getProjectContext());
        dynamicContext.setCurrentToolCalls(new java.util.ArrayList<>());
        dynamicContext.setCurrentToolResults(new java.util.ArrayList<>());

        // [Phase 5] 从数据库加载历史消息
        java.util.List<java.util.Map<String, Object>> history = new java.util.ArrayList<>();
        java.util.List<ChatMessageEntity> recentMessages = chatHistoryRepository.getRecentMessages(sessionId, 50);
        for (ChatMessageEntity msg : recentMessages) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("role", msg.getRole());
            map.put("content", msg.getContent() != null ? msg.getContent() : "");
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                map.put("tool_call_id", msg.getToolCallId());
                map.put("name", msg.getToolName());
            }
            history.add(map);
        }
        dynamicContext.setMessageHistory(history);
        dynamicContext.setCurrentStep(new java.util.concurrent.atomic.AtomicInteger(0));

        IntentResultVO intentResult = classifyIntent(sessionId, userId, message);
        ExecutionPolicy executionPolicy = resolveExecutionPolicy(intentResult, message, agentId);

        dynamicContext.setCurrentIntent(intentResult.getIntent().name());
        dynamicContext.setCurrentIntentConfidence(intentResult.getConfidence());
        dynamicContext.setMaxSteps(executionPolicy.maxSteps());
        dynamicContext.setMaxToolCalls(executionPolicy.maxToolCalls());
        dynamicContext.setMaxToolCallsPerRound(executionPolicy.maxToolCallsPerRound());
        dynamicContext.setMaxAiRetries(executionPolicy.maxAiRetries());
        dynamicContext.setToolTimeoutMs(executionPolicy.toolTimeoutMs());
        dynamicContext.setContextTokenBudget(executionPolicy.contextTokenBudget());
        dynamicContext.resetAiRetryCount();

        log.info("ReAct 执行预算: intent={}, confidence={}, maxSteps={}, maxToolCalls={}, perRound={}, maxAiRetries={}, toolTimeoutMs={}, contextTokenBudget={}",
                intentResult.getIntent().name(),
                intentResult.getConfidence(),
                executionPolicy.maxSteps(),
                executionPolicy.maxToolCalls(),
                executionPolicy.maxToolCallsPerRound(),
                executionPolicy.maxAiRetries(),
                executionPolicy.toolTimeoutMs(),
                executionPolicy.contextTokenBudget());

        // 4. 初始化结果 DTO
        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        dynamicContext.setResult(result);

        // 5. 追加用户消息到历史
        dynamicContext.appendUserMessage(message);

        log.info("ReAct RootNode - 初始化完成 sessionId={}, userId={}, agentId={}, terminalSessionId={}",
                sessionId, userId, agentId, terminalSessionId);

        // 6. 路由到 AI 调用节点
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // RootNode → TaskBreakdownNode（检测是否需要拆解）→ AiCallNode
        return getBean("reactTaskBreakdownNode");
    }

    private IntentResultVO classifyIntent(String sessionId, String userId, String message) {
        if (message == null || message.isBlank()) {
            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN)
                    .confidence(0.0D)
                    .rawResponse("empty_message")
                    .build();
        }

        try {
            return intentService.classify(sessionId, userId, message);
        } catch (Exception e) {
            log.warn("RootNode 意图识别失败，回退到 UNKNOWN: sessionId={}", sessionId, e);
            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN)
                    .confidence(0.0D)
                    .rawResponse("classify_failed")
                    .build();
        }
    }

    private ExecutionPolicy resolveExecutionPolicy(IntentResultVO intentResult, String message, String agentId) {
        // 1. 优先从 agent YAML 配置读取 reactBudget
        AiAgentConfigTableVO.Module.ReactBudget budget = resolveReactBudget(agentId);
        if (budget != null) {
            log.info("使用 agent 配置的 reactBudget: maxSteps={}, maxToolCalls={}, perRound={}, maxAiRetries={}, toolTimeoutMs={}, contextTokenBudget={}",
                    budget.getMaxSteps(), budget.getMaxToolCalls(), budget.getMaxToolCallsPerRound(),
                    budget.getMaxAiRetries(), budget.getToolTimeoutMs(), budget.getContextTokenBudget());
            return new ExecutionPolicy(
                    budget.getMaxSteps() != null ? budget.getMaxSteps() : DEFAULT_MAX_STEPS,
                    budget.getMaxToolCalls() != null ? budget.getMaxToolCalls() : DEFAULT_MAX_TOOL_CALLS,
                    budget.getMaxToolCallsPerRound() != null ? budget.getMaxToolCallsPerRound() : DEFAULT_MAX_TOOL_CALLS_PER_ROUND,
                    budget.getMaxAiRetries() != null ? budget.getMaxAiRetries() : DEFAULT_MAX_AI_RETRIES,
                    budget.getToolTimeoutMs() != null ? budget.getToolTimeoutMs() : DEFAULT_TOOL_TIMEOUT_MS,
                    budget.getContextTokenBudget() != null && budget.getContextTokenBudget() > 0 ? budget.getContextTokenBudget() : DEFAULT_CONTEXT_TOKEN_BUDGET
            );
        }

        // 2. 无配置时，按意图分级使用默认策略
        IntentTypeEnumVO intent = intentResult != null && intentResult.getIntent() != null
                ? intentResult.getIntent()
                : IntentTypeEnumVO.UNKNOWN;
        int messageLength = message == null ? 0 : message.length();

        return switch (intent) {
            case DIAGNOSE, CONFIGURE -> new ExecutionPolicy(70, 240, 30, 3, 90_000L, 12000);
            case DEPLOY, SECURITY, BACKUP -> new ExecutionPolicy(80, 280, 30, 3, 120_000L, 16000);
            case EXECUTE, MONITOR -> new ExecutionPolicy(40, 160, 24, 2, 60_000L, 10000);
            case SEARCH, EXPLAIN -> new ExecutionPolicy(30, 120, 24, 2, 45_000L, 10000);
            case CHAT, CONTINUE -> new ExecutionPolicy(20, 50, 16, 1, 25_000L, 8000);
            case UNKNOWN -> messageLength > 200
                    ? new ExecutionPolicy(60, 220, 24, 2, DEFAULT_TOOL_TIMEOUT_MS, 10000)
                    : new ExecutionPolicy(DEFAULT_MAX_STEPS, DEFAULT_MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS_PER_ROUND, DEFAULT_MAX_AI_RETRIES, DEFAULT_TOOL_TIMEOUT_MS, DEFAULT_CONTEXT_TOKEN_BUDGET);
        };
    }

    /**
     * 从 agent YAML 配置读取 reactBudget
     */
    private AiAgentConfigTableVO.Module.ReactBudget resolveReactBudget(String agentId) {
        if (agentId == null || aiAgentAutoConfigProperties.getTables() == null) {
            return null;
        }
        for (AiAgentConfigTableVO table : aiAgentAutoConfigProperties.getTables().values()) {
            if (table.getAgent() != null && agentId.equals(table.getAgent().getAgentId())) {
                if (table.getModule() != null && table.getModule().getRunner() != null) {
                    return table.getModule().getRunner().getReactBudget();
                }
            }
        }
        return null;
    }

    private record ExecutionPolicy(
            int maxSteps,
            int maxToolCalls,
            int maxToolCallsPerRound,
            int maxAiRetries,
            long toolTimeoutMs,
            int contextTokenBudget
    ) {
    }

}
