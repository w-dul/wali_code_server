package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.valobj.memory.CoreMemoryVO;

import java.util.List;

/**
 * 核心记忆仓储接口
 * <p>
 * 存储用户长期偏好、规则、纠正、关键决策
 * 与 IChatHistoryRepository（会话级消息存储）不同，本接口管理跨会话的记忆
 *
 * @author 教学版-WaLiCode
 * 2026/6/26
 */
public interface ICoreMemoryRepository {

    /**
     * 添加核心记忆
     */
    void addMemory(CoreMemoryVO memory);

    /**
     * 根据关键词查询相关记忆（相关性过滤）
     * <p>
     * 匹配逻辑：keywords 字段中的关键词与 query 中的关键词做交集匹配
     * 至少命中 1 个关键词才返回
     *
     * @param userId 用户 ID（用于 scope=user 的全局记忆）
     * @param query  查询文本（从用户消息提取）
     * @param limit  最大返回数量
     * @return 相关记忆列表
     */
    List<CoreMemoryVO> getRelevantMemories(String userId, String query, int limit);

    /**
     * 获取所有记忆（不做相关性过滤，用于 fallback）
     */
    List<CoreMemoryVO> getAllMemories(String userId, int limit);

    /**
     * 更新记忆使用状态（命中后更新 lastUsedAt 和 useCount）
     */
    void touchMemory(Long memoryId);

    /**
     * 淘汰冷记忆（删除 priority 低且 long time 未使用的记忆）
     */
    void evictColdMemories(String userId, int maxMemories);
}
