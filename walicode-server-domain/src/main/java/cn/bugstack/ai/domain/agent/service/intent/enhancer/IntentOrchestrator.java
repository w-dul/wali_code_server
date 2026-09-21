package cn.bugstack.ai.domain.agent.service.intent.enhancer;

import cn.bugstack.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 意图增强编排器
 * <p>
 * 协调 IntentService（分类）+ IntentEnhancer（上下文增强）+ SignalExtractor（信号提取）
 * <p>
 * 对标 WaLiCode 的 IntentService 完整流程：
 * 1. 规则快速路径 → 高置信度直接返回
 * 2. 异步模型预分析 → LLM 分类
 * 3. 信号提取 → 结构化信息
 * 4. 指代消解 → "它"/"这个" → 具体实体
 * 5. 语义检索 → 查找相关上下文
 * 6. 上下文追踪 → 维护对话历史
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class IntentOrchestrator {

    @Resource
    private SignalExtractor signalExtractor;

    @Resource
    private IntentEnhancer intentEnhancer;

    /**
     * 指代消解：将代词替换为具体实体
     * <p>
     * 示例：
     * - "它怎么挂了" → "nginx 怎么挂了"（从上下文提取）
     * - "这个文件" → "src/App.tsx 这个文件"
     * - "再试一次" → 重复上一个命令
     */
    public String resolveCoreference(String userInput, ConversationContextVO context,
                                      SignalExtractor.ExtractedSignals signals) {
        if (context == null || userInput == null) return userInput;

        String resolved = userInput;
        String lowerInput = userInput.toLowerCase();

        // 检测代词
        boolean hasPronoun = lowerInput.contains("它") || lowerInput.contains("这个")
                || lowerInput.contains("那个") || lowerInput.contains("该")
                || lowerInput.matches(".*\\b(it|this|that)\\b.*");

        if (!hasPronoun) return resolved;

        log.info("检测到代词，开始指代消解: input={}", userInput);

        // 从最近上下文提取实体
        String lastService = context.getLastEntity("service");
        String lastFile = context.getLastEntity("file");
        String lastCommand = context.getLastEntity("command");

        // 替换代词
        if (lowerInput.contains("它") && lastService != null) {
            resolved = resolved.replace("它", lastService);
            log.info("指代消解: 它 → {}", lastService);
        }
        if ((lowerInput.contains("这个") || lowerInput.contains("该")) && lastFile != null) {
            resolved = resolved.replace("这个", lastFile).replace("该", lastFile);
            log.info("指代消解: 这个/该 → {}", lastFile);
        }
        if (lowerInput.contains("再试一次") && lastCommand != null) {
            resolved = lastCommand;
            log.info("指代消解: 再试一次 → {}", lastCommand);
        }

        return resolved;
    }

    /**
     * 构建增强的意图结果
     * <p>
     * 将意图分类 + 信号提取 + 上下文增强组合成最终结果
     */
    /**
     * 构建增强的意图结果（含项目文件搜索 fallback）
     * [P2-5] 增加 projectRootPath 参数，传递给 IntentEnhancer 用于文件搜索
     */
    public EnhancedIntentResult buildEnhancedResult(IntentResultVO intentResult,
                                                     String userInput,
                                                     String sessionId,
                                                     ConversationContextVO context,
                                                     String projectRootPath) {
        // 1. 提取信号
        SignalExtractor.ExtractedSignals signals = signalExtractor.extract(userInput);

        // 2. 指代消解
        String resolvedInput = resolveCoreference(userInput, context, signals);

        // 3. 如果消解后的输入与原始不同，重新提取信号
        if (!resolvedInput.equals(userInput)) {
            signals = signalExtractor.extract(resolvedInput);
            log.info("指代消解后重新提取信号: resolved={}", resolvedInput);
        }

        // 4. 意图增强（[P2-5] 传入 projectRootPath 支持 fallback 搜索）
        IntentEnhancer.EnhanceResult enhanceResult = intentEnhancer.enhance(resolvedInput, sessionId, projectRootPath);

        // 5. 合并实体
        java.util.Map<String, String> mergedEntities = new java.util.HashMap<>();
        if (intentResult.getEntities() != null) {
            mergedEntities.putAll(intentResult.getEntities());
        }
        // 用信号补充实体
        if (!signals.getCommandHints().isEmpty()) {
            mergedEntities.putIfAbsent("service", signals.getCommandHints().get(0));
        }
        if (!signals.getFilePaths().isEmpty()) {
            mergedEntities.putIfAbsent("file", signals.getFilePaths().get(0));
        }

        return EnhancedIntentResult.builder()
                .intent(intentResult.getIntent())
                .confidence(intentResult.getConfidence())
                .entities(mergedEntities)
                .resolvedInput(resolvedInput)
                .originalInput(userInput)
                .signals(signals)
                .enhancedContext(enhanceResult.getEnhancedContext())
                .searchFallback(enhanceResult.isSearchFallback())
                .build();
    }

    /**
     * 兼容方法（不含 projectRootPath）
     */
    public EnhancedIntentResult buildEnhancedResult(IntentResultVO intentResult,
                                                     String userInput,
                                                     String sessionId,
                                                     ConversationContextVO context) {
        return buildEnhancedResult(intentResult, userInput, sessionId, context, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  数据结构
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EnhancedIntentResult {
        private cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO intent;
        private double confidence;
        private java.util.Map<String, String> entities;
        private String resolvedInput;
        private String originalInput;
        private SignalExtractor.ExtractedSignals signals;
        private String enhancedContext;
        /** 是否使用了项目文件搜索 fallback [P2-5] */
        private boolean searchFallback;

        public boolean hasEnhancement() {
            return enhancedContext != null && !enhancedContext.isBlank();
        }
    }
}
