package cn.bugstack.ai.domain.agent.model.valobj.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 核心记忆值对象
 * <p>
 * 存储用户的偏好、规则、纠正、关键决策等长期记忆
 * 与 MilestoneVO（事件记录）不同，CoreMemoryVO 是提炼后的长期知识
 *
 * @author 教学版-WaLiCode
 * 2026/6/26
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CoreMemoryVO {

    /** 记忆 ID */
    private Long id;

    /** 记忆作用域：user（全局）/ session（会话级） */
    private String scope;

    /** 记忆分类：Rule（规则）/ Preference（偏好）/ Decision（决策）/ Correction（纠正）/ Fact（事实） */
    private String category;

    /** 记忆标题（简短描述） */
    private String title;

    /** 关键词列表（逗号分隔，用于相关性匹配） */
    private String keywords;

    /** 记忆正文 */
    private String content;

    /** 优先级：1-5，越高越重要 */
    private Integer priority;

    /** 创建时间 */
    private Long createdAt;

    /** 最后使用时间（用于淘汰冷记忆） */
    private Long lastUsedAt;

    /** 使用次数（用于淘汰冷记忆） */
    private Integer useCount;

    /** 来源会话 ID */
    private String sourceSessionId;
}
