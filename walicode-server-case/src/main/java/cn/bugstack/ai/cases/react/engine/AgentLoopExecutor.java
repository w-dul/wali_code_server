package cn.bugstack.ai.cases.react.engine;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.service.engine.AgentLoopConfig;
import cn.bugstack.ai.domain.agent.service.engine.ContextCompressor;
import cn.bugstack.ai.domain.agent.service.engine.LoopState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 循环执行器（Supervisor 模式）
 * <p>对标 WaLiCode streamingAgent.ts
 * <p>
 * 职责（不侵入节点链）：
 * 1. 包装 rootNode.apply()，提供外部监督能力
 * 2. 空闲超时检测（30s 轮询，超时取消节点链执行）
 * 3. 413 Payload Too Large → 触发上下文压缩后重试整个节点链
 * 4. Token 预算追踪与预警
 * 5. 死循环检测（通过 DynamicContext 中的步数和工具调用模式）
 * 6. 外部取消控制（用户停止 / 客户端断开）
 * <p>
 * 与现有 ReAct 节点链的关系：
 * AgentLoopExecutor 是"监督者"，节点链是"执行者"。
 * 节点链（RootNode → AiCallNode → ToolCallNode → LoopDecisionNode → UserFeedbackNode）
 * 保持原有逻辑不变，AgentLoopExecutor 在外层提供：
 * - 异步空闲检测线程
 * - 413 重试（重新执行整个节点链）
 * - Token 预算预警
 * - 统一的错误兜底
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class AgentLoopExecutor {

    /** 空闲检查间隔（秒） */
    private static final long IDLE_CHECK_INTERVAL_SECONDS = 30;

    /** 最大连续 413 错误次数（超过则放弃） */
    private static final int MAX_CONSECUTIVE_413 = 3;

    @Resource
    private ContextCompressor contextCompressor;

    /**
     * 执行 Agent 循环（监督模式）
     * <p>
     * 本方法包装 rootNode.apply()，在异步线程中执行节点链，
     * 同时在主线程进行空闲超时检测和取消控制。
     *
     * @param config         循环配置
     * @param dynamicContext ReAct 动态上下文（已初始化）
     * @param requestDTO     请求参数
     * @param nodeChainTask  节点链执行逻辑（rootNode.apply）
     * @return 最终结果
     */
    public ReActResultDTO executeSupervised(
            AgentLoopConfig config,
            DefaultReActFactory.DynamicContext dynamicContext,
            ChatRequestDTO requestDTO,
            NodeChainTask nodeChainTask) {

        log.info("AgentLoopExecutor 启动监督: mode={}, maxRounds={}, idleTimeoutMs={}, maxTokenBudget={}",
                config.getMode(), config.getMaxRounds(), config.getIdleTimeoutMs(), config.getMaxTokenBudget());

        // 1. 创建循环状态
        LoopState state = LoopState.init(config);
        state.setMessageHistory(dynamicContext.getMessageHistory());
        state.touch();

        // 将 LoopState 暴露给 DynamicContext，使心跳线程可调用 touch() 刷新活跃时间
        dynamicContext.setLoopState(state);

        // 2. 创建回调（桥接到 SSE emitter）
        AgentLoopCallbacks callbacks = createCallbacks(dynamicContext);

        // 3. 启动空闲超时检测线程
        ScheduledExecutorService idleChecker = startIdleChecker(state, dynamicContext, callbacks);

        // 4. 执行节点链（带 413 重试）
        ReActResultDTO result = null;
        int attempt413 = 0;

        try {
            while (attempt413 <= MAX_CONSECUTIVE_413) {
                try {
                    // 执行节点链
                    result = nodeChainTask.execute(requestDTO, dynamicContext);
                    state.touch();

                    // 成功完成
                    break;

                } catch (Exception e) {
                    state.touch();

                    if (is413Error(e) && attempt413 < MAX_CONSECUTIVE_413) {
                        attempt413++;
                        log.warn("413 Payload Too Large, 尝试紧急压缩后重试 ({}/{})",
                                attempt413, MAX_CONSECUTIVE_413);

                        callbacks.onWarning("上下文过长，正在压缩并重试...");

                        // 紧急压缩上下文（使用 ContextCompressor）
                        List<Map<String, Object>> compressed = contextCompressor.emergencyCompress(
                                dynamicContext.getMessageHistory());
                        if (compressed != null && compressed.size() < dynamicContext.getMessageHistory().size()) {
                            dynamicContext.setMessageHistory(new java.util.ArrayList<>(compressed));
                            state.setMessageHistory(dynamicContext.getMessageHistory());
                            // 重置 DynamicContext 的循环状态，准备重试
                            dynamicContext.setCurrentStep(new java.util.concurrent.atomic.AtomicInteger(0));
                            dynamicContext.resetRoundBuffers();
                            dynamicContext.setStopReason(null);
                            dynamicContext.setErrorMessage(null);
                            continue;
                        } else {
                            callbacks.onError("上下文压缩失败，无法恢复");
                            state.setStopReason("compress_failed");
                            break;
                        }
                    }

                    // 非 413 错误，直接终止
                    if (!state.isCancelled()) {
                        callbacks.onError("执行异常: " + e.getMessage());
                        state.setStopReason("error");
                    }
                    break;
                }
            }

            // 5. 如果没有结果（异常退出），构建默认结果
            if (result == null) {
                result = buildFallbackResult(dynamicContext, state);
            }

            // 6. Token 预算预警
            state.refreshTokenEstimate();
            if (state.isTokenBudgetExceeded()) {
                callbacks.onWarning("Token 预算超限 (" + state.getTotalTokens()
                        + " > " + config.getMaxTokenBudget() + ")，建议优化上下文");
            }

            // 7. 死循环检测（基于步数和工具调用比例）
            if (result.getTotalSteps() > 0 && result.getTotalToolCalls() > 0) {
                double toolCallPerStep = (double) result.getTotalToolCalls() / result.getTotalSteps();
                if (toolCallPerStep > config.getMaxToolCallsPerRound() * 2) {
                    log.warn("检测到高工具调用密度: {}/{} = {:.1f}/step",
                            result.getTotalToolCalls(), result.getTotalSteps(), toolCallPerStep);
                    callbacks.onWarning("检测到高工具调用密度，可能存在循环调用");
                }
            }

            // 8. 发送完成回调
            callbacks.onDone(result);

            log.info("AgentLoopExecutor 监督结束: steps={}, toolCalls={}, tokens={}, stopReason={}",
                    result.getTotalSteps(), result.getTotalToolCalls(), state.getTotalTokens(),
                    result.getStopReason());

            return result;

        } finally {
            idleChecker.shutdownNow();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  空闲超时检测
    // ═══════════════════════════════════════════════════════════════

    /**
     * 启动空闲超时检测线程
     */
    private ScheduledExecutorService startIdleChecker(
            LoopState state,
            DefaultReActFactory.DynamicContext dynamicContext,
            AgentLoopCallbacks callbacks) {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-idle-checker-" + dynamicContext.getSessionId());
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (state.isIdle() && !dynamicContext.isCancelled()) {
                    long idleMs = System.currentTimeMillis() - state.getLastActivityTime();
                    log.warn("Agent 空闲超时: {}ms 无活动 (阈值 {}ms)",
                            idleMs, state.getConfig().getIdleTimeoutMs());

                    state.setStopReason("idle_timeout");
                    dynamicContext.setStopReason("idle_timeout");
                    dynamicContext.markCancelled("idle_timeout");

                    callbacks.onWarning("空闲超时 (" + (idleMs / 1000) + "s)，自动终止");
                }
            } catch (Exception e) {
                log.error("空闲检测线程异常", e);
            }
        }, IDLE_CHECK_INTERVAL_SECONDS, IDLE_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

        return scheduler;
    }

    // ═══════════════════════════════════════════════════════════════
    //  回调创建
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建 SSE 回调桥接器
     */
    private AgentLoopCallbacks createCallbacks(DefaultReActFactory.DynamicContext dynamicContext) {
        ResponseBodyEmitter emitter = dynamicContext.getEmitter();

        return new AgentLoopCallbacks() {
            @Override
            public void onText(String chunk, String fullText) {
                // 文本事件由节点链直接发送，这里不重复
            }

            @Override
            public void onToolCall(String toolCallId, String toolName, String args) {
                // 工具调用事件由节点链直接发送
            }

            @Override
            public void onToolProgress(String toolCallId, String progress) {
                // 工具进度事件由 SseToolProgressNotifier 直接发送
            }

            @Override
            public void onToolResult(String toolCallId, String content, String status) {
                // 工具结果事件由节点链直接发送
            }

            @Override
            public void onRoundEnd(int currentRound, int maxRounds, int totalToolCalls) {
                // 轮次结束事件由节点链直接发送
            }

            @Override
            public void onWarning(String message) {
                try {
                    String json = "{\"event\":\"warning\",\"content\":\""
                            + message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                            + "\"}\n";
                    emitter.send(json, org.springframework.http.MediaType.APPLICATION_JSON);
                    log.info("发送 warning 事件: {}", message);
                } catch (Exception e) {
                    log.warn("发送 warning 事件失败: {}", e.getMessage());
                }
            }

            @Override
            public void onError(String message) {
                try {
                    String json = "{\"event\":\"error\",\"content\":\""
                            + message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                            + "\"}\n";
                    emitter.send(json, org.springframework.http.MediaType.APPLICATION_JSON);
                    log.error("发送 error 事件: {}", message);
                } catch (Exception e) {
                    log.warn("发送 error 事件失败: {}", e.getMessage());
                }
            }

            @Override
            public void onDone(ReActResultDTO result) {
                // done 事件由 UserFeedbackNode 直接发送
                log.info("Agent 循环完成: stopReason={}", result.getStopReason());
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 判断是否为 413 错误
     */
    private boolean is413Error(Exception e) {
        if (e == null) return false;
        String message = e.getMessage();
        if (message == null) return false;
        return message.contains("413")
                || message.contains("Payload Too Large")
                || message.contains("Request Entity Too Large")
                || message.contains("content too long")
                || message.contains("context length exceeded");
    }

    /**
     * 构建兜底结果（异常情况）
     */
    private ReActResultDTO buildFallbackResult(
            DefaultReActFactory.DynamicContext dynamicContext,
            LoopState state) {

        String stopReason = state.getStopReason();
        if (stopReason == null) {
            stopReason = dynamicContext.isCancelled() ? "user_stop" : "error";
        }

        String content = dynamicContext.getAssistantContent() != null
                ? cn.bugstack.ai.cases.react.util.MarkdownNormalizer.normalize(
                        dynamicContext.getAssistantContent().toString())
                : "";

        return ReActResultDTO.builder()
                .content(content)
                .totalSteps(dynamicContext.getStep())
                .totalToolCalls(dynamicContext.getResult() != null
                        ? dynamicContext.getResult().getTotalToolCalls() : 0)
                .maxStepsReached("max_steps".equals(stopReason))
                .userStopped("user_stop".equals(stopReason))
                .idleTimeout("idle_timeout".equals(stopReason))
                .stopReason(stopReason)
                .error(state.getCancelReason())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  节点链执行接口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 节点链执行接口（函数式）
     * 调用方实现此接口，执行 rootNode.apply()
     */
    @FunctionalInterface
    public interface NodeChainTask {
        ReActResultDTO execute(ChatRequestDTO requestDTO, DefaultReActFactory.DynamicContext dynamicContext) throws Exception;
    }
}
