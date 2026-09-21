package cn.bugstack.ai.domain.agent.service.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 循环运行时状态
 * <p>对标 WaLiCode streamingAgent.ts 中的 local variables
 * <p>每个 Agent 循环拥有独立的 LoopState，线程安全
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Data
public class LoopState {

    private final AgentLoopConfig config;

    /** 当前轮次（从 0 开始） */
    private int round = 0;

    /** 总工具调用次数 */
    private final AtomicInteger totalToolCalls = new AtomicInteger(0);

    /** 估算的总 token 数 */
    private volatile long totalTokens = 0;

    /** 终止原因 */
    private volatile String stopReason;

    /** 上一轮 AI 响应是否被 max_tokens 截断 */
    private volatile boolean wasTruncated;

    /** 最后活动时间戳（用于空闲超时检测） */
    private volatile long lastActivityTime;

    /** 死循环检测：每轮工具调用签名 */
    private final List<String> roundSignatures = new ArrayList<>();

    /** 消息历史（与 DynamicContext.messageHistory 共享引用） */
    private List<Map<String, Object>> messageHistory;

    /** 是否已压缩过上下文（防止无限压缩） */
    private volatile boolean contextCompressed = false;

    /** 413 错误连续出现次数 */
    private final AtomicInteger consecutive413Errors = new AtomicInteger(0);

    /** 外部控制：取消标志 */
    private volatile boolean cancelled = false;

    /** 取消原因 */
    private volatile String cancelReason;

    public LoopState(AgentLoopConfig config) {
        this.config = config;
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * 初始化
     */
    public static LoopState init(AgentLoopConfig config) {
        return new LoopState(config);
    }

    /**
     * 是否应该继续循环
     */
    public boolean shouldContinue() {
        if (cancelled) return false;
        if (stopReason != null) return false;
        return round < config.getMaxRounds();
    }

    public void incrementRound() {
        round++;
        touch();
    }

    public void decrementRound() {
        if (round > 0) round--;
    }

    public void incrementToolCalls() {
        totalToolCalls.incrementAndGet();
        touch();
    }

    public int getTotalToolCalls() {
        return totalToolCalls.get();
    }

    public void touch() {
        lastActivityTime = System.currentTimeMillis();
    }

    public boolean isIdle() {
        return System.currentTimeMillis() - lastActivityTime > config.getIdleTimeoutMs();
    }

    public void markCancelled(String reason) {
        this.cancelled = true;
        this.cancelReason = reason;
        this.stopReason = "user_stop";
        log.info("Agent 循环已取消: reason={}", reason);
    }

    public void setStopReason(String reason) {
        this.stopReason = reason;
        log.info("Agent 循环停止: reason={}", reason);
    }

    /**
     * 添加轮次签名（用于死循环检测）
     */
    public void addRoundSignature(String signature) {
        roundSignatures.add(signature);
    }

    /**
     * 检测是否出现递减收益（死循环）
     * 连续 N 轮工具签名完全相同
     */
    public boolean isDiminishingReturns() {
        int threshold = config.getDiminishingReturnsThreshold();
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

    /**
     * 检查 token 预算
     */
    public boolean isTokenBudgetExceeded() {
        return totalTokens > config.getMaxTokenBudget();
    }

    /**
     * 估算消息历史的 token 数
     */
    public static long estimateTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        long total = 0;
        for (Map<String, Object> msg : messages) {
            String content = String.valueOf(msg.get("content"));
            total += content != null ? content.length() / 2L : 0;
        }
        return total;
    }

    /**
     * 刷新 token 估算
     */
    public void refreshTokenEstimate() {
        this.totalTokens = estimateTokens(messageHistory);
    }
}
