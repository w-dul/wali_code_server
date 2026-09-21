package cn.bugstack.ai.domain.agent.service.context.provider;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TaskProvider implements ContextProvider {
    @Override public String getName() { return "task"; }
    @Override public int getOrder() { return 20; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        
        if (messageHistory != null) {
            messageHistory.stream()
                .filter(m -> "user".equals(m.get("role")))
                .findFirst()
                .ifPresent(m -> result.put("taskDescription", m.get("content")));
        }
        
        return result;
    }
}
