package cn.bugstack.ai.domain.agent.service.armory.node;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.Runner;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * Runner 事件压缩配置注入工具
 *
 * <p>ADK 1.2 的 InMemoryRunner 默认不启用事件压缩（EventsCompactionConfig 为 null），
 * 导致长对话时 Session 事件无限增长，最终超出模型 token 上限。
 *
 * <p>本工具通过反射注入 EventsCompactionConfig，让 Runner 在每次 runAsync 后
 * 自动触发 SlidingWindowEventCompactor 压缩旧事件。
 *
 * <p>压缩策略：
 * - compactionInterval: 每累积 N 条事件触发一次压缩
 * - overlapSize: 压缩时保留最近 N 条不压缩（重叠区）
 * - tokenThreshold: token 超过阈值时也触发压缩
 * - eventRetentionSize: 压缩后保留最近 N 条事件
 *
 * @author walicode-java
 * 2026/6/21
 */
@Slf4j
public class RunnerCompactionInjector {

    /** 默认每 50 条事件触发一次压缩 */
    private static final int DEFAULT_COMPACTION_INTERVAL = 50;

    /** 默认保留最近 10 条事件不压缩 */
    private static final int DEFAULT_OVERLAP_SIZE = 10;

    /** 默认 token 阈值（超过 60000 触发压缩） */
    private static final int DEFAULT_TOKEN_THRESHOLD = 60_000;

    /** 默认压缩后保留 20 条事件 */
    private static final int DEFAULT_EVENT_RETENTION_SIZE = 20;

    /**
     * 为 Runner 注入事件压缩配置
     *
     * <p>条件：
     * 1. Runner 是 InMemoryRunner 或其子类
     * 2. agent 是 LlmAgent 且有可用的 model
     *
     * @param runner    ADK Runner
     * @param baseAgent Runner 关联的 agent
     * @return true=注入成功
     */
    public static boolean injectCompaction(Runner runner, BaseAgent baseAgent) {
        return injectCompaction(runner, baseAgent,
                DEFAULT_COMPACTION_INTERVAL,
                DEFAULT_OVERLAP_SIZE,
                DEFAULT_TOKEN_THRESHOLD,
                DEFAULT_EVENT_RETENTION_SIZE);
    }

    /**
     * 为 Runner 注入事件压缩配置（自定义参数）
     */
    public static boolean injectCompaction(Runner runner, BaseAgent baseAgent,
                                           int compactionInterval,
                                           int overlapSize,
                                           int tokenThreshold,
                                           int eventRetentionSize) {
        if (runner == null || baseAgent == null) {
            log.warn("Runner 或 BaseAgent 为 null，跳过压缩配置注入");
            return false;
        }

        // 从 LlmAgent 提取 BaseLlm
        BaseLlm baseLlm = extractBaseLlm(baseAgent);
        if (baseLlm == null) {
            log.warn("Agent {} 不是 LlmAgent 或无可用的 model，跳过压缩配置注入", baseAgent.name());
            return false;
        }

        // 构建 EventsCompactionConfig
        LlmEventSummarizer summarizer = new LlmEventSummarizer(baseLlm);
        EventsCompactionConfig config = new EventsCompactionConfig(
                compactionInterval,
                overlapSize,
                summarizer,
                tokenThreshold,
                eventRetentionSize
        );

        // 反射注入到 Runner.eventsCompactionConfig 字段
        try {
            Field field = Runner.class.getDeclaredField("eventsCompactionConfig");
            field.setAccessible(true);
            field.set(runner, config);
            log.info("Runner 事件压缩配置注入成功: agent={}, compactionInterval={}, overlapSize={}, tokenThreshold={}, eventRetentionSize={}",
                    baseAgent.name(), compactionInterval, overlapSize, tokenThreshold, eventRetentionSize);
            return true;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.error("Runner 事件压缩配置注入失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从 BaseAgent 提取 BaseLlm
     *
     * <p>如果 agent 是 LlmAgent，直接提取；
     * 如果是 SequentialAgent 等复合 agent，递归子 agent 查找第一个有 model 的 LlmAgent。
     * LlmAgent.model() 返回 Optional<Model>，Model.model() 返回 Optional<BaseLlm>。
     */
    private static BaseLlm extractBaseLlm(BaseAgent baseAgent) {
        if (baseAgent == null) {
            return null;
        }

        // 如果是 LlmAgent，直接提取
        if (baseAgent instanceof LlmAgent llmAgent) {
            BaseLlm llm = extractFromLlmAgent(llmAgent);
            if (llm != null) {
                return llm;
            }
        }

        // 递归子 agent
        List<? extends BaseAgent> subAgents = baseAgent.subAgents();
        if (subAgents != null) {
            for (BaseAgent sub : subAgents) {
                BaseLlm llm = extractBaseLlm(sub);
                if (llm != null) {
                    return llm;
                }
            }
        }

        return null;
    }

    /**
     * 从 LlmAgent 提取 BaseLlm
     */
    private static BaseLlm extractFromLlmAgent(LlmAgent llmAgent) {
        // 优先从 model() 获取
        Optional<com.google.adk.models.Model> modelOpt = llmAgent.model();
        if (modelOpt != null && modelOpt.isPresent()) {
            Optional<BaseLlm> llmOpt = modelOpt.get().model();
            if (llmOpt != null && llmOpt.isPresent()) {
                return llmOpt.get();
            }
        }

        // 回退到 resolvedModel()
        com.google.adk.models.Model resolvedModel = llmAgent.resolvedModel();
        if (resolvedModel != null) {
            Optional<BaseLlm> llmOpt = resolvedModel.model();
            if (llmOpt != null && llmOpt.isPresent()) {
                return llmOpt.get();
            }
        }

        return null;
    }
}
