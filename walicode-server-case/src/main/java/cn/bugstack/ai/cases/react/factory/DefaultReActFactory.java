package cn.bugstack.ai.cases.react.factory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 动态上下文
 *
 * <p>参考 DefaultArmoryFactory.DynamicContext，为 ReAct 执行链路提供状态存储：
 * - 会话信息（sessionId, userId, agentId）
 * - 消息历史（messages + toolResults）
 * - ReAct 循环状态（步数、工具调用计数）
 * - SSE 发射器
 * - 工具定义（ToolCallback[]）
 * - ADK Runner / Session
 *
 * <p>ReAct 循环数据流：
 * <pre>
 * RootNode         → 初始化上下文，绑定 SSE emitter
 * AiCallNode       → 构建 AI 请求，追加用户消息到 history
 * ToolCallNode     → 解析 tool_calls，执行工具，追加结果到 history
 * LoopDecisionNode → 判断是否继续（max_steps / finish / 无工具调用）
 * UserFeedbackNode → 发送最终结果，完成 SSE
 * </pre>
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4 14:26
 */
public class DefaultReActFactory {

    /**
     * 动态上下文
     */
    @Slf4j
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // ══════════════════════════════════════════════════════════
        //  会话基本信息
        // ══════════════════════════════════════════════════════════

        /** 对话会话 ID */
        private String sessionId;

        /** 用户 ID */
        private String userId;

        /** 智能体 ID */
        private String agentId;

        /** SSH 终端会话 ID */
        private String terminalSessionId;

        /** 当前工程上下文（由前端 ChatRequestDTO 透传） */
        private cn.bugstack.ai.api.dto.ProjectContextDTO projectContext;

        /** SSE 事件发射器 */
        private ResponseBodyEmitter emitter;

        // ══════════════════════════════════════════════════════════
        //  消息历史（参考 WaLiCode streamingAgent.ts）
        //  每轮 ReAct 循环结束后，将 toolCalls + toolResults 追加到这里
        // ══════════════════════════════════════════════════════════

        /**
         * 消息历史
         * 格式：{ role: "user"/"assistant"/"tool", content: "...", tool_call_id?: "..." }
         */
        @Builder.Default
        private List<Map<String, Object>> messageHistory = new ArrayList<>();

        /**
         * 当前轮次的工具调用列表
         */
        @Builder.Default
        private List<Map<String, Object>> currentToolCalls = new ArrayList<>();

        /**
         * 当前轮次的工具执行结果列表
         */
        @Builder.Default
        private List<Map<String, Object>> currentToolResults = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  ReAct 循环状态
        // ══════════════════════════════════════════════════════════

        /** 当前步数 */
        @Builder.Default
        private AtomicInteger currentStep = new AtomicInteger(0);

        /** 最大步数 */
        private int maxSteps;

        /** 最大工具调用次数（总计） */
        private int maxToolCalls;

        /** 每轮最大工具调用次数 */
        private int maxToolCallsPerRound;

        /** AI 调用最大重试次数 */
        @Builder.Default
        private int maxAiRetries = 2;

        /** 工具执行默认超时（毫秒） */
        @Builder.Default
        private long toolTimeoutMs = 60_000L;

        /** 消息历史 token 预算 */
        @Builder.Default
        private int contextTokenBudget = 8000;

        /** 总工具调用次数 */
        @Builder.Default
        private AtomicInteger totalToolCallCount = new AtomicInteger(0);

        /** 当前轮工具调用次数 */
        @Builder.Default
        private AtomicInteger roundToolCallCount = new AtomicInteger(0);

        /** 本轮因 per-round 上限被截断的未处理工具数（用于日志和诊断） */
        private int overflowToolCount;

        // ══════════════════════════════════════════════════════════
        //  AI 响应缓冲（用于累积流式文本）
        // ══════════════════════════════════════════════════════════

        /** 累积的文本响应 */
        @Builder.Default
        private StringBuilder assistantContent = new StringBuilder();

        /** 累积的 reasoning_content */
        @Builder.Default
        private StringBuilder assistantReasoning = new StringBuilder();

        /** 上一轮次收到的 reasoning_content（需要回传给 API） */
        private String lastReasoningContent;

        // ══════════════════════════════════════════════════════════
        //  中断状态
        // ══════════════════════════════════════════════════════════

        /** 中断原因：user_stop / idle_timeout / max_steps / client_disconnect */
        private String stopReason;

        /** 错误消息（如有） */
        private String errorMessage;

        /** 客户端断开/任务取消标志（volatile 保证多线程可见性） */
        @Builder.Default
        private volatile boolean cancelled = false;

        /** 取消原因 */
        private String cancelReason;

        // ══════════════════════════════════════════════════════════
        //  结果对象（供 UserFeedbackNode 使用）
        // ══════════════════════════════════════════════════════════

        /** 最终结果 DTO */
        private cn.bugstack.ai.api.dto.ReActResultDTO result;

        // ══════════════════════════════════════════════════════════
        //  工具定义（参考 WaLiCode ai.ts）
        // ══════════════════════════════════════════════════════════

        /**
         * 工具回调列表（从 ArmoryService 装配链路获取）
         */
        private org.springframework.ai.tool.ToolCallback[] toolCallbacks;

        /**
         * 是否使用 Anthropic 格式（tool_call_id vs tool_use_id）
         */
        private boolean useAnthropicFormat;

        // ══════════════════════════════════════════════════════════
        //  上下文记忆（Phase 1: 动态 Prompt 构建）
        // ══════════════════════════════════════════════════════════

        /** 最近执行的命令记录（用于注入到动态 Prompt 中） */
        @Builder.Default
        private List<String> recentCommands = new ArrayList<>();
        
        // ══════════════════════════════════════════════════════════
        //  意图状态（Phase 3: 意图识别系统）
        // ══════════════════════════════════════════════════════════
        
        /** 当前意图名称 */
        private String currentIntent;

        /** 当前意图置信度 */
        private double currentIntentConfidence;

        /** 当前轮 AI 调用重试次数 */
        @Builder.Default
        private AtomicInteger aiRetryCount = new AtomicInteger(0);

        // ══════════════════════════════════════════════════════════
        //  死循环检测（Phase 1: diminishing returns）
        // ══════════════════════════════════════════════════════════

        /** 每轮工具调用签名（用于死循环检测） */
        @Builder.Default
        private List<String> roundSignatures = new ArrayList<>();

        /** 死循环检测阈值：连续 N 轮签名相同则终止 */
        @Builder.Default
        private int diminishingReturnsThreshold = 2;

        // ══════════════════════════════════════════════════════════
        //  流式执行线程（用于中断）
        // ══════════════════════════════════════════════════════════

        /** SSE 流式执行的线程引用 */
        private Thread streamThread;

        /** Agent 循环运行时状态（心跳线程通过此引用调用 touch 刷新活跃时间） */
        private cn.bugstack.ai.domain.agent.service.engine.LoopState loopState;

        // ══════════════════════════════════════════════════════════
        //  辅助方法
        // ══════════════════════════════════════════════════════════

        public void incrementStep() {
            currentStep.incrementAndGet();
        }

        public int getStep() {
            return currentStep.get();
        }

        public void incrementTotalToolCalls() {
            totalToolCallCount.incrementAndGet();
        }

        public void incrementRoundToolCalls() {
            roundToolCallCount.incrementAndGet();
        }

        public void resetRoundToolCalls() {
            roundToolCallCount.set(0);
        }

        public void resetRoundBuffers() {
            currentToolCalls.clear();
            currentToolResults.clear();
            assistantContent.setLength(0);
            assistantReasoning.setLength(0);
        }

        public int incrementAiRetryCount() {
            return aiRetryCount.incrementAndGet();
        }

        public void resetAiRetryCount() {
            aiRetryCount.set(0);
        }

        // ══════════════════════════════════════════════════════════
        //  取消状态管理
        // ══════════════════════════════════════════════════════════

        /**
         * 标记任务已取消
         */
        public synchronized void markCancelled(String reason) {
            if (!this.cancelled) {
                this.cancelled = true;
                this.cancelReason = reason;
                if (this.stopReason == null) {
                    this.stopReason = "user_stop";
                }
                log.info("任务已取消: reason={}", reason);
                interruptStreamThread();
            }
        }

        /**
         * 检查任务是否已取消
         */
        public boolean isCancelled() {
            return cancelled;
        }

        /**
         * 设置流式执行线程引用
         */
        public void setStreamThread(Thread streamThread) {
            this.streamThread = streamThread;
        }

        /**
         * 中断流式执行线程
         */
        public void interruptStreamThread() {
            if (streamThread != null && streamThread.isAlive()) {
                log.info("中断流式执行线程: threadName={}", streamThread.getName());
                streamThread.interrupt();
            }
        }

        public void appendMessage(Map<String, Object> message) {
            messageHistory.add(message);
        }

        public void appendUserMessage(String content) {
            appendMessage(Map.of("role", "user", "content", content));
        }

        public void appendAssistantMessage(String content) {
            appendMessage(Map.of("role", "assistant", "content", content));
        }

        public void appendToolMessage(String toolCallId, String content) {
            Map<String, Object> msg = useAnthropicFormat
                    ? Map.of("type", "tool_result", "tool_use_id", toolCallId, "content", content)
                    : Map.of("role", "tool", "tool_call_id", toolCallId, "content", content);
            messageHistory.add(msg);
        }

        /**
         * 添加"用户确认"占位消息（工具需要交互）
         */
        public void appendUserConfirmationMessage(String question) {
            String content = "User interaction required: " + question;
            appendMessage(Map.of("role", "user", "content", content));
        }

        public void addRecentCommand(String command) {
            if (command == null || command.trim().isEmpty()) return;
            recentCommands.add(command.trim());
            while (recentCommands.size() > 20) {
                recentCommands.remove(0);
            }
        }

        public int getOverflowToolCount() {
            return overflowToolCount;
        }

        public void setOverflowToolCount(int overflowToolCount) {
            this.overflowToolCount = overflowToolCount;
        }

        /**
         * 添加轮次签名（用于死循环检测）
         */
        public void addRoundSignature(String signature) {
            if (signature != null && !signature.isBlank()) {
                roundSignatures.add(signature);
            }
        }

        /**
         * 检测是否出现递减收益（死循环）
         * 连续 N 轮工具签名完全相同
         */
        public boolean isDiminishingReturns() {
            int threshold = diminishingReturnsThreshold;
            if (roundSignatures.size() < threshold) return false;

            int size = roundSignatures.size();
            String latest = roundSignatures.get(size - 1);
            for (int i = size - 2; i >= size - threshold; i--) {
                if (!roundSignatures.get(i).equals(latest)) {
                    return false;
                }
            }
            return true;
        }

    }

}
