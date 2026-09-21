package cn.bugstack.ai.domain.agent.service.intent;

import cn.bugstack.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import cn.bugstack.ai.domain.agent.service.IIntentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class IntentService implements IIntentService {

    @Resource
    private RuleIntentClassifier ruleClassifier;

    @Resource
    private LLMIntentClassifier llmClassifier;

    @Resource
    private ContextTracker contextTracker;

    // 简单的 LRU 缓存，最大 200 个条目
    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > 200;
            }
        });

    private static class CacheEntry {
        IntentResultVO result;
        long expireTime;
        CacheEntry(IntentResultVO result, long expireTime) {
            this.result = result;
            this.expireTime = expireTime;
        }
    }

    @Override
    public IntentResultVO classify(String sessionId, String userId, String message) {
        // [Phase 3 修复] 缓存键使用 SHA-256 替代 hashCode，避免碰撞
        String cacheKey = sessionId + ":" + sha256(message);
        
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireTime > System.currentTimeMillis()) {
            log.debug("意图分类缓存命中: session={}", sessionId);
            return cached.result;
        }

        ConversationContextVO context = contextTracker.getContext(sessionId);

        // 第1层：规则分类（< 1ms）
        IntentResultVO ruleResult = ruleClassifier.classify(message, context);
        if (ruleResult.getConfidence() >= 0.8) {
            recordAndCache(sessionId, cacheKey, ruleResult, context);
            return ruleResult;
        }

        // 第2层：LLM 分类（100-500ms）
        IntentResultVO llmResult = llmClassifier.classify(message, context);
        IntentResultVO finalResult = llmResult.getConfidence() >= 0.5 ? llmResult : ruleResult;

        recordAndCache(sessionId, cacheKey, finalResult, context);
        return finalResult;
    }

    private void recordAndCache(String sessionId, String cacheKey, IntentResultVO result,
                                  ConversationContextVO context) {
        contextTracker.updateContext(sessionId, result);
        
        // [Phase 3] 记录实体到上下文（供指代消解使用）
        if (context != null && result.getEntities() != null) {
            result.getEntities().forEach(context::putEntity);
        }
        
        // 缓存 5 分钟
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis() + 5 * 60 * 1000));
    }

    /**
     * [Phase 3 修复] SHA-256 替代 hashCode，避免缓存键碰撞
     */
    private String sha256(String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16); // 前16位足够
        } catch (Exception e) {
            // 兜底：使用 hashCode
            return Integer.toHexString(message.hashCode());
        }
    }
}
