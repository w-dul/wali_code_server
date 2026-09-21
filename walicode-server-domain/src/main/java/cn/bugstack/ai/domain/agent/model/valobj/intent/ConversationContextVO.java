package cn.bugstack.ai.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationContextVO {
    private LinkedList<IntentHistoryEntryVO> recentIntents;
    private int turnCount;
    private long sessionStartTime;
    private IntentTypeEnumVO lastIntent;

    /**
     * 最近实体追踪（用于指代消解）
     * key: 实体类型（service, file, command 等）
     * value: 实体值
     */
    @Builder.Default
    private Map<String, String> lastEntities = new HashMap<>();

    /**
     * 获取最近的实体值
     * @param entityKey 实体类型
     * @return 实体值，不存在返回 null
     */
    public String getLastEntity(String entityKey) {
        return lastEntities != null ? lastEntities.get(entityKey) : null;
    }

    /**
     * 记录实体
     */
    public void putEntity(String key, String value) {
        if (lastEntities == null) lastEntities = new HashMap<>();
        if (key != null && value != null) {
            lastEntities.put(key, value);
        }
    }
}
