package cn.bugstack.ai.domain.agent.service.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 循环配置
 * <p>对标 WaLiCode streamingAgent.ts 的 AgentLoopConfig
 * <p>由 RootNode 根据 agent YAML 配置 + 意图分级构建
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentLoopConfig {

    /** 模式：chat（对话）/ agent（自主） */
    @Builder.Default
    private AgentMode mode = AgentMode.AGENT;

    /** 最大循环轮数（chat=10, agent=20） */
    @Builder.Default
    private int maxRounds = 20;

    /** 每轮最大工具调用数 */
    @Builder.Default
    private int maxToolCallsPerRound = 5;

    /** 全局最大工具调用总数 */
    @Builder.Default
    private int maxTotalToolCalls = 200;

    /** AI 调用重试次数 */
    @Builder.Default
    private int maxAiRetries = 3;

    /** 工具执行重试次数 */
    @Builder.Default
    private int maxToolRetries = 3;

    /** 重试延迟基数（毫秒），指数退避：base * 2^attempt */
    @Builder.Default
    private long retryDelayBaseMs = 1000L;

    /** 空闲超时（毫秒），默认 10 分钟 */
    @Builder.Default
    private long idleTimeoutMs = 600_000L;

    /** Token 预算上限 */
    @Builder.Default
    private int maxTokenBudget = 200_000;

    /** 是否自动继续（agent 模式默认 true） */
    @Builder.Default
    private boolean autoContinue = true;

    /** 死循环检测阈值：连续 N 轮工具签名相同则终止 */
    @Builder.Default
    private int diminishingReturnsThreshold = 2;

    public enum AgentMode {
        /** 对话模式：轻量工具调用 */
        CHAT,
        /** 自主模式：多轮工具调用 + 任务分解 */
        AGENT
    }
}
