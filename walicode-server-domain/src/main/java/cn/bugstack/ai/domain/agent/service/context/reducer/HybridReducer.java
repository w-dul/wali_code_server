package cn.bugstack.ai.domain.agent.service.context.reducer;

import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HybridReducer implements MessageReducer {
    @Resource 
    private PriorityReducer priorityReducer;
    
    @Resource 
    private SlidingWindowReducer slidingReducer;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        Set<Integer> priorityKeep = indexSet(priorityReducer.reduce(messages, tokenBudget), messages);
        Set<Integer> slidingKeep  = indexSet(slidingReducer.reduce(messages, tokenBudget), messages);

        // 取并集（修复：原交集过于激进，会丢失重要消息）
        Set<Integer> keepIndices = new HashSet<>(priorityKeep);
        keepIndices.addAll(slidingKeep);

        // 保证至少有最近 2 条
        int minKeep = Math.min(2, messages.size());
        for (int i = messages.size() - minKeep; i < messages.size(); i++) {
            keepIndices.add(i);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keepIndices.contains(i)) result.add(messages.get(i));
        }
        return result;
    }

    private Set<Integer> indexSet(List<Map<String, Object>> subset, List<Map<String, Object>> all) {
        Set<Integer> indices = new HashSet<>();
        for (Map<String, Object> msg : subset) {
            int idx = all.indexOf(msg);
            if (idx >= 0) indices.add(idx);
        }
        return indices;
    }
}
