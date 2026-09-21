package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.ICoreMemoryRepository;
import cn.bugstack.ai.domain.agent.model.valobj.memory.CoreMemoryVO;
import cn.bugstack.ai.infrastructure.dao.ICoreMemoryDao;
import cn.bugstack.ai.infrastructure.dao.po.CoreMemoryPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 核心记忆仓储实现
 * <p>
 * 相关性匹配逻辑：
 * 1. 将 query 拆分为关键词列表
 * 2. 在 DB 中查询 keywords 字段包含任一关键词的记忆条目
 * 3. 如果无匹配，fallback 返回最近的高优先级记忆
 *
 * @author 教学版-WaLiCode
 * 2026/6/26
 */
@Slf4j
@Repository
public class CoreMemoryRepository implements ICoreMemoryRepository {

    @Resource
    private ICoreMemoryDao coreMemoryDao;

    @Override
    public void addMemory(CoreMemoryVO memory) {
        CoreMemoryPO po = toPO(memory);
        coreMemoryDao.insert(po);
        log.debug("核心记忆已入库: category={}, title={}", memory.getCategory(), memory.getTitle());
    }

    @Override
    public List<CoreMemoryVO> getRelevantMemories(String userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return getAllMemories(userId, limit);
        }

        // 拆分 query 为关键词
        List<String> keywords = splitToKeywords(query);
        if (keywords.isEmpty()) {
            return getAllMemories(userId, limit);
        }

        List<CoreMemoryPO> pos = coreMemoryDao.queryByUserIdAndKeywords(userId, keywords, limit);
        if (pos == null || pos.isEmpty()) {
            // 无匹配时 fallback 返回最近的高优先级记忆
            log.debug("核心记忆无关键词匹配，fallback 到最近记忆: userId={}", userId);
            return getAllMemories(userId, limit);
        }

        return pos.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public List<CoreMemoryVO> getAllMemories(String userId, int limit) {
        List<CoreMemoryPO> pos = coreMemoryDao.queryByUserId(userId, limit);
        if (pos == null || pos.isEmpty()) return Collections.emptyList();
        return pos.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public void touchMemory(Long memoryId) {
        coreMemoryDao.updateUseStats(memoryId, new Date(), 1);  // useCount 增量由 DB 处理
    }

    @Override
    public void evictColdMemories(String userId, int maxMemories) {
        int currentCount = coreMemoryDao.countByUserId(userId);
        if (currentCount <= maxMemories) return;

        // 查找冷记忆：30天未使用 + priority ≤ 3
        List<CoreMemoryPO> coldMemories = coreMemoryDao.queryColdMemories(userId, 30, 3, currentCount - maxMemories);
        for (CoreMemoryPO cold : coldMemories) {
            coreMemoryDao.deleteById(cold.getId());
            log.info("淘汰冷记忆: id={}, title={}, lastUsedAt={}", cold.getId(), cold.getTitle(), cold.getLastUsedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  转换方法
    // ═══════════════════════════════════════════════════════════════

    private CoreMemoryPO toPO(CoreMemoryVO vo) {
        return CoreMemoryPO.builder()
                .userId("default")  // TODO: 后续传入真实 userId
                .scope(vo.getScope())
                .category(vo.getCategory())
                .title(vo.getTitle())
                .keywords(vo.getKeywords())
                .content(vo.getContent())
                .priority(vo.getPriority())
                .sourceSessionId(vo.getSourceSessionId())
                .useCount(vo.getUseCount() != null ? vo.getUseCount() : 1)
                .createdAt(new Date(vo.getCreatedAt() != null ? vo.getCreatedAt() : System.currentTimeMillis()))
                .lastUsedAt(new Date(vo.getLastUsedAt() != null ? vo.getLastUsedAt() : System.currentTimeMillis()))
                .build();
    }

    private CoreMemoryVO toVO(CoreMemoryPO po) {
        return CoreMemoryVO.builder()
                .id(po.getId())
                .scope(po.getScope())
                .category(po.getCategory())
                .title(po.getTitle())
                .keywords(po.getKeywords())
                .content(po.getContent())
                .priority(po.getPriority())
                .createdAt(po.getCreatedAt() != null ? po.getCreatedAt().getTime() : null)
                .lastUsedAt(po.getLastUsedAt() != null ? po.getLastUsedAt().getTime() : null)
                .useCount(po.getUseCount())
                .sourceSessionId(po.getSourceSessionId())
                .build();
    }

    private List<String> splitToKeywords(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        // 混合分词：按空格、逗号、中文标点拆分
        String[] tokens = query.split("[\\s,，。.!！?？;；:：/\\\\|()\\[\\]{}\"']+");
        List<String> keywords = new ArrayList<>();

        for (String token : tokens) {
            if (token.length() < 2) continue;
            keywords.add(token.toLowerCase());
            // 英文驼峰拆分（如 "UserService" → "user", "service"）
            if (token.matches("[A-Z][a-z]+[A-Z][a-z]+")) {
                String[] parts = token.split("(?=[A-Z])");
                for (String part : parts) {
                    if (part.length() >= 2) keywords.add(part.toLowerCase());
                }
            }
        }

        return keywords;
    }
}
