package cn.bugstack.ai.domain.agent.service.context.reducer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SlidingWindowReducer implements MessageReducer {
    private static final int DEFAULT_WINDOW_SIZE = 20;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        List<Map<String, Object>> window = new ArrayList<>();
        int usedTokens = 0;

        // 从新到旧逐条添加，直到超出 token 预算或窗口大小
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            int msgTokens = estimateToken(msg);
            if (window.size() >= DEFAULT_WINDOW_SIZE || usedTokens + msgTokens > tokenBudget) break;
            window.add(0, msg);
            usedTokens += msgTokens;
        }
        return window;
    }

    private int estimateToken(Map<String, Object> message) {
        String content = String.valueOf(message.get("content"));
        return content != null ? content.length() / 2 : 0;
    }
}
