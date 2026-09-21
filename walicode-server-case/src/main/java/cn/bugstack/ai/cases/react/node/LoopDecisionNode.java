package cn.bugstack.ai.cases.react.node;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.react.AbstractAIAgentReActSupport;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.service.engine.ContextCompressor;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ReAct 循环决策节点
 *
 * <p>职责：
 * 1. 检查终止条件（错误、最大步数、用户停止）
 * 2. 决定是否继续循环（回到 AiCallNode）或路由到 UserFeedbackNode
 *
 * <p>终止条件（参考 WaLiCode streamingAgent.ts）：
 * - AI 返回 finish 终止指令
 * - 达到最大步数 (maxSteps)
 * - 达到最大工具调用次数 (maxToolCalls)
 * - 发生错误
 * - 用户主动停止 (user_stop)
 *
 * <p>循环条件：
 * - 上一轮有工具调用（继续对话）
 * - AI 未返回终止指令
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    @Resource
    private ContextCompressor contextCompressor;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct LoopDecisionNode - 循环决策，当前步数: {}/{}", 
                dynamicContext.getStep(), dynamicContext.getMaxSteps());

        // 1. 检查是否已有终止原因
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("已设置终止原因: {}", stopReason);
            return router(requestParameter, dynamicContext);
        }

        // 2. 检查最大步数
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("达到最大步数: {}, 终止循环", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason("max_steps");
            dynamicContext.getResult().setMaxStepsReached(true);
            return router(requestParameter, dynamicContext);
        }

        // 3. 检查最大工具调用次数
        if (dynamicContext.getResult().getTotalToolCalls() >= dynamicContext.getMaxToolCalls()) {
            log.info("达到最大工具调用次数: {}, 终止循环",
                    dynamicContext.getResult().getTotalToolCalls());
            dynamicContext.setStopReason("max_tool_calls");
            return router(requestParameter, dynamicContext);
        }

        // 4. 检查 assistant 消息是否包含终止指令
        String assistantContent = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";

        if (containsFinishCommand(assistantContent)) {
            log.info("AI 返回 finish 指令，终止循环");
            dynamicContext.setStopReason("finish");
            return router(requestParameter, dynamicContext);
        }

        // 5. 检查错误
        if (dynamicContext.getErrorMessage() != null) {
            log.info("发生错误: {}, 终止循环", dynamicContext.getErrorMessage());
            dynamicContext.setStopReason("error");
            return router(requestParameter, dynamicContext);
        }

        // 6. [Phase 1] 死循环检测（递减收益）
        //    构建当前轮次的工具调用签名，检测连续重复
        String currentSignature = buildRoundSignature(dynamicContext);
        dynamicContext.addRoundSignature(currentSignature);

        if (dynamicContext.isDiminishingReturns()) {
            log.warn("检测到死循环：连续 {} 轮工具签名相同: {}",
                    dynamicContext.getDiminishingReturnsThreshold(), currentSignature);
            sendWarningEvent(dynamicContext.getEmitter(),
                    "检测到重复操作模式，自动终止以避免死循环", dynamicContext);
            dynamicContext.setStopReason("diminishing_returns");
            return router(requestParameter, dynamicContext);
        }

        // 7. [Phase 1] 上下文压缩检查
        //    估算当前 token 数，超阈值时触发压缩
        long currentTokens = estimateTokens(dynamicContext.getMessageHistory());
        int messageCount = dynamicContext.getMessageHistory().size();

        if (contextCompressor.needsCompression(currentTokens, messageCount)) {
            log.info("触发上下文压缩: tokens={}, messages={}", currentTokens, messageCount);
            sendWarningEvent(dynamicContext.getEmitter(),
                    "上下文较长，正在自动压缩...", dynamicContext);

            List<Map<String, Object>> compressed = contextCompressor.compress(
                    dynamicContext.getMessageHistory(), currentTokens);
            dynamicContext.setMessageHistory(new java.util.ArrayList<>(compressed));

            long afterTokens = estimateTokens(dynamicContext.getMessageHistory());
            log.info("上下文压缩完成: {} → {} messages, {} → {} tokens",
                    messageCount, compressed.size(), currentTokens, afterTokens);
        }

        // 8. 检查上一轮是否有工具调用（继续 ReAct 循环的条件）
        List<Map<String, Object>> currentToolCalls = dynamicContext.getCurrentToolCalls();
        if (currentToolCalls != null && !currentToolCalls.isEmpty()) {
            log.info("上一轮有 {} 个工具调用，继续 ReAct 循环", currentToolCalls.size());
            dynamicContext.resetRoundBuffers();
            return router(requestParameter, dynamicContext);
        }

        // 9. 无工具调用且无终止指令 → 循环完成
        log.info("ReAct 循环完成，无更多工具调用");
        dynamicContext.setStopReason("completed");
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        String stopReason = dynamicContext.getStopReason();

        // 有终止原因 → 路由到 UserFeedbackNode
        if (stopReason != null) {
            switch (stopReason) {
                case "user_stop":
                    dynamicContext.getResult().setUserStopped(true);
                    break;
                case "idle_timeout":
                    dynamicContext.getResult().setIdleTimeout(true);
                    break;
                case "error":
                    break;
                case "max_steps":
                    dynamicContext.getResult().setMaxStepsReached(true);
                    break;
                case "max_tool_calls":
                    break;
                case "completed":
                case "finish":
                case "diminishing_returns":
                default:
                    break;
            }
            return getBean("reactUserFeedbackNode");
        }

        // 无终止原因 → 继续 ReAct 循环，回到 AiCallNode
        log.info("继续 ReAct 循环，路由到 AiCallNode");
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建当前轮次的工具调用签名
     * <p>用于死循环检测：如果连续 N 轮调用完全相同的工具序列，判定为死循环
     * <p>签名格式："tool1(args_hash)|tool2(args_hash)|..."
     */
    private String buildRoundSignature(DefaultReActFactory.DynamicContext dynamicContext) {
        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "empty";
        }

        return toolCalls.stream()
                .map(tc -> {
                    String name = String.valueOf(tc.getOrDefault("name", "unknown"));
                    String args = String.valueOf(tc.getOrDefault("args", ""));
                    // 只取 args 的长度和前 20 字符作为签名，避免签名过长
                    String argsSign = args.length() + ":" +
                            (args.length() > 20 ? args.substring(0, 20) : args);
                    return name + "(" + argsSign + ")";
                })
                .collect(Collectors.joining("|"));
    }

    /**
     * 估算消息历史的 token 数
     */
    private long estimateTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        long total = 0;
        for (Map<String, Object> msg : messages) {
            String content = String.valueOf(msg.get("content"));
            total += content != null ? content.length() / 2L : 0;
        }
        return total;
    }

    /**
     * 检查是否包含终止指令
     * 参考 WaLiCode streamingAgent.ts 的终止条件判断
     */
    private boolean containsFinishCommand(String content) {
        if (content == null || content.isBlank()) return false;

        String lower = content.toLowerCase();

        // DSL 风格: finish(message=...)
        if (lower.contains("finish(") || lower.contains("finish (")) {
            return true;
        }

        // JSON 风格: {"action": "finish"}
        if (lower.contains("\"action\"") && lower.contains("\"finish\"")) {
            return true;
        }

        // 标签风格: <answer>finish(...)</answer>
        if (lower.contains("<answer>") && lower.contains("finish")) {
            return true;
        }

        // 结构化输出标记：📋 本次改动摘要（表示任务完成）
        if (content.contains("📋") && (content.contains("改动摘要") || content.contains("变更摘要") || content.contains("本次变更"))) {
            return true;
        }

        return false;
    }

}
