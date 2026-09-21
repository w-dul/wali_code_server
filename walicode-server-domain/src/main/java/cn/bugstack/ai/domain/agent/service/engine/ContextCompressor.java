package cn.bugstack.ai.domain.agent.service.engine;

import cn.bugstack.ai.domain.agent.service.context.reducer.HybridReducer;
import cn.bugstack.ai.domain.agent.service.context.reducer.MessageReducer;
import cn.bugstack.ai.domain.agent.service.context.reducer.PriorityReducer;
import cn.bugstack.ai.domain.agent.service.context.reducer.SlidingWindowReducer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩器
 * <p>对标 WaLiCode contextCompressor.ts
 * <p>
 * 触发条件：
 * 1. 413 Payload Too Large 错误
 * 2. Token 预算超过 80%
 * 3. 消息历史超过 50 条
 * <p>
 * 压缩策略（三级降级）：
 * 1. 优先级裁剪：PriorityReducer 保留 CRITICAL/HIGH
 * 2. 滑动窗口：SlidingWindowReducer 保留最近 N 条
 * 3. 混合裁剪：HybridReducer 取并集（已修复）
 * <p>
 * 注意：AI 摘要压缩（Level 3）需要 ChatClient 支持，Phase 2 实现
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class ContextCompressor {

    @Resource
    private PriorityReducer priorityReducer;

    @Resource
    private SlidingWindowReducer slidingWindowReducer;

    @Resource
    private HybridReducer hybridReducer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatModel chatModel;

    /** 触发压缩的 token 阈值（80% of 200k） */
    private static final long COMPRESS_TOKEN_THRESHOLD = 160_000L;

    /** 触发压缩的消息条数阈值 */
    private static final int COMPRESS_MESSAGE_THRESHOLD = 50;

    /**
     * 判断是否需要压缩
     */
    public boolean needsCompression(long currentTokens, int messageCount) {
        return currentTokens > COMPRESS_TOKEN_THRESHOLD
                || messageCount > COMPRESS_MESSAGE_THRESHOLD;
    }

    /**
     * 压缩上下文（三级降级）
     *
     * @param history       原始消息历史
     * @param currentTokens 当前 token 数
     * @return 压缩后的消息历史
     */
    public List<Map<String, Object>> compress(List<Map<String, Object>> history, long currentTokens) {
        if (history == null || history.isEmpty()) {
            return history;
        }

        int originalSize = history.size();
        long originalTokens = currentTokens;
        log.info("开始上下文压缩: messages={}, tokens={}", originalSize, originalTokens);

        // 目标 token 数：压缩到当前的 50%
        int targetTokens = (int) (currentTokens / 2);

        // Level 1: PriorityReducer（保留 CRITICAL/HIGH）
        List<Map<String, Object>> afterPriority = priorityReducer.reduce(history, targetTokens);
        long afterPriorityTokens = LoopState.estimateTokens(afterPriority);
        log.info("Level 1 PriorityReducer: {} → {} messages, {} → {} tokens",
                originalSize, afterPriority.size(), originalTokens, afterPriorityTokens);

        if (afterPriorityTokens <= targetTokens) {
            log.info("Level 1 压缩达标");
            return afterPriority;
        }

        // Level 2: SlidingWindowReducer（滑动窗口）
        List<Map<String, Object>> afterSliding = slidingWindowReducer.reduce(history, targetTokens);
        long afterSlidingTokens = LoopState.estimateTokens(afterSliding);
        log.info("Level 2 SlidingWindowReducer: {} → {} messages, {} → {} tokens",
                originalSize, afterSliding.size(), afterPriorityTokens, afterSlidingTokens);

        if (afterSlidingTokens <= targetTokens) {
            log.info("Level 2 压缩达标");
            return afterSliding;
        }

        // Level 3: AI 摘要压缩（对旧消息生成摘要，保留最近 8 条原样）
        List<Map<String, Object>> afterSummary = summarizeOldMessages(history, 8);
        long afterSummaryTokens = LoopState.estimateTokens(afterSummary);
        log.info("Level 3 AI摘要: {} → {} messages, {} → {} tokens",
                originalSize, afterSummary.size(), afterSlidingTokens, afterSummaryTokens);

        if (afterSummaryTokens <= targetTokens) {
            log.info("Level 3 AI摘要压缩达标");
            return afterSummary;
        }

        // Level 4: HybridReducer（并集 — 兜底）
        List<Map<String, Object>> afterHybrid = hybridReducer.reduce(history, targetTokens);
        long afterHybridTokens = LoopState.estimateTokens(afterHybrid);
        log.info("Level 4 HybridReducer: {} → {} messages, {} → {} tokens",
                originalSize, afterHybrid.size(), afterSummaryTokens, afterHybridTokens);

        return afterHybrid;
    }

    // ═══════════════════════════════════════════════════════════════
    //  AI 摘要压缩
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对旧消息生成 AI 摘要，保留最近 keepRecent 条原样
     *
     * @param history     原始消息历史
     * @param keepRecent  保留最近 N 条原样（不压缩）
     * @return 摘要 + 最近消息 的合并列表
     */
    private List<Map<String, Object>> summarizeOldMessages(List<Map<String, Object>> history, int keepRecent) {
        if (chatModel == null || history.size() <= keepRecent + 2) {
            return history;
        }

        // 分割：旧消息（需要摘要） vs 最近消息（保留原样）
        int splitIndex = history.size() - keepRecent;
        List<Map<String, Object>> oldMessages = history.subList(0, splitIndex);
        List<Map<String, Object>> recentMessages = history.subList(splitIndex, history.size());

        try {
            // 格式化旧消息为可读文本
            StringBuilder transcript = new StringBuilder();
            for (Map<String, Object> msg : oldMessages) {
                String role = String.valueOf(msg.getOrDefault("role", "unknown"));
                String content = String.valueOf(msg.getOrDefault("content", ""));
                // 截断过长内容
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                switch (role) {
                    case "user" -> transcript.append("用户: ").append(content).append("\n\n");
                    case "assistant" -> transcript.append("AI: ").append(content).append("\n\n");
                    case "tool" -> transcript.append("工具结果: ").append(content).append("\n\n");
                    case "system" -> transcript.append("系统: ").append(content).append("\n\n");
                    default -> transcript.append(role).append(": ").append(content).append("\n\n");
                }
            }

            // [P1-4] 结构化摘要 prompt：4段输出格式
            String summaryPrompt = "请将以下对话历史压缩为结构化摘要，严格按以下4段格式输出。\n\n" +
                    "## 需求与目标\n用户的核心需求、目标、待解决的问题（用要点列表）\n\n" +
                    "## 变更与操作\n已执行的关键操作、代码变更、SSH 命令及结果（用要点列表）\n\n" +
                    "## 问题与障碍\n遇到的错误、异常、阻塞问题及排查进展（用要点列表）\n\n" +
                    "## 进度与决策\n当前进度、关键决策、未完成事项、下一步计划（用要点列表）\n\n" +
                    "要求：\n" +
                    "1. 保留所有 SSH 命令结果、文件路径、技术术语\n" +
                    "2. 每段控制在 300 字以内，总字数不超过 1500 字\n" +
                    "3. 只输出摘要内容，不要添加引言或总结\n" +
                    "4. 用中文输出\n\n" +
                    "对话历史:\n" + transcript;

            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("你是一个对话摘要专家，擅长从技术对话中提取关键信息。严格按4段结构化格式输出，不要添加额外内容。"),
                    new UserMessage(summaryPrompt)
            ));

            Generation generation = chatModel.call(prompt).getResult();
            String summary = generation.getOutput().getText();

            if (summary == null || summary.isBlank()) {
                log.warn("AI 摘要生成为空，跳过摘要压缩");
                return history;
            }

            log.info("AI 摘要生成成功: {} 条旧消息 → {} 字摘要", oldMessages.size(), summary.length());

            // 组装：摘要 system message + 最近消息
            Map<String, Object> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "[对话历史摘要 - 以下是之前 " + oldMessages.size() + " 轮对话的压缩摘要]\n\n" + summary);
            summaryMsg.put("priority", "HIGH");

            List<Map<String, Object>> result = new ArrayList<>();
            result.add(summaryMsg);
            result.addAll(recentMessages);
            return result;

        } catch (Exception e) {
            log.error("AI 摘要压缩失败，降级到规则裁剪: {}", e.getMessage());
            return history;
        }
    }

    /**
     * 413 错误的紧急压缩
     * 激进策略：只保留最近 4 条消息 + 所有 CRITICAL 级别
     */
    public List<Map<String, Object>> emergencyCompress(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }

        log.warn("413 紧急压缩: messages={}", history.size());

        // 413 紧急压缩也先尝试 AI 摘要（如果可用）
        if (chatModel != null) {
            List<Map<String, Object>> withSummary = summarizeOldMessages(history, 4);
            long summaryTokens = LoopState.estimateTokens(withSummary);
            if (summaryTokens < LoopState.estimateTokens(history) * 0.7) {
                log.warn("413 AI摘要压缩成功: {} → {} messages, {} tokens", history.size(), withSummary.size(), summaryTokens);
                return withSummary;
            }
        }

        // AI 摘要不可用或效果不佳，用极小的 token 预算强制 PriorityReducer 只保留 CRITICAL
        List<Map<String, Object>> afterPriority = priorityReducer.reduce(history, 500);

        // 至少保留最近 4 条
        int minKeep = Math.min(4, history.size());
        if (afterPriority.size() < minKeep) {
            List<Map<String, Object>> tail = history.subList(history.size() - minKeep, history.size());
            // 合并去重（按 content 粗略判断）
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Map<String, Object> msg : afterPriority) {
                seen.add(String.valueOf(msg.get("content")));
            }
            List<Map<String, Object>> result = new java.util.ArrayList<>(afterPriority);
            for (Map<String, Object> msg : tail) {
                String key = String.valueOf(msg.get("content"));
                if (seen.add(key)) {
                    result.add(msg);
                }
            }
            log.warn("413 紧急压缩完成: {} → {} messages", history.size(), result.size());
            return result;
        }

        log.warn("413 紧急压缩完成: {} → {} messages", history.size(), afterPriority.size());
        return afterPriority;
    }
}
