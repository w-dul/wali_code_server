package cn.bugstack.ai.domain.agent.service.context.provider;

import cn.bugstack.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import cn.bugstack.ai.domain.ssh.model.entity.SshConnectionEntity;
import cn.bugstack.ai.domain.ssh.model.entity.TerminalSessionEntity;
import cn.bugstack.ai.domain.ssh.service.ISshTerminalService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TerminalStateProvider implements ContextProvider {
    @Resource
    private ISshTerminalService sshTerminalService;
    @Resource
    private ISshConnectionRepository sshConnectionRepository;

    /** 缓存：terminalSessionId → {osInfo, currentUser, uptime}（同一 session 内不变） */
    private final Map<String, Map<String, String>> staticCache = new ConcurrentHashMap<>();

    @Override public String getName() { return "terminal-state"; }
    @Override public int getOrder() { return 10; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return result;
        }

        // 注入 SSH 连接信息（连接名、主机、端口、用户）
        injectConnectionInfo(result, terminalSessionId);

        // 静态信息使用缓存（osInfo, whoami, uptime 在同一 terminal session 中不变）
        Map<String, String> cached = staticCache.computeIfAbsent(terminalSessionId, id -> {
            Map<String, String> m = new HashMap<>();
            m.put("osInfo", safeExec(id, "uname -srm"));
            m.put("currentUser", safeExec(id, "whoami"));
            m.put("uptime", safeExec(id, "uptime -p 2>/dev/null || uptime"));
            return m;
        });

        result.put("osInfo", cached.get("osInfo"));
        result.put("currentUser", cached.get("currentUser"));
        result.put("uptime", cached.get("uptime"));

        // pwd 每次都重新获取（用户可能执行了 cd）
        result.put("currentDirectory", safeExec(terminalSessionId, "pwd"));

        return result;
    }

    /** 清除指定 terminal session 的缓存（连接断开时调用） */
    public void invalidateCache(String terminalSessionId) {
        staticCache.remove(terminalSessionId);
    }

    /**
     * 通过 terminalSessionId → TerminalSessionEntity → connectionId → SshConnectionEntity
     * 注入 SSH 连接信息到上下文
     */
    private void injectConnectionInfo(Map<String, Object> result, String terminalSessionId) {
        try {
            TerminalSessionEntity sessionEntity = sshTerminalService.getTerminalSession(terminalSessionId);
            if (sessionEntity == null || sessionEntity.getConnectionId() == null) {
                return;
            }
            SshConnectionEntity connEntity = sshConnectionRepository.queryConnectionById(sessionEntity.getConnectionId());
            if (connEntity == null) {
                return;
            }
            result.put("sshConnectionName", connEntity.getConnectionName());
            result.put("sshHost", connEntity.getHost());
            result.put("sshPort", connEntity.getPort());
            result.put("sshUsername", connEntity.getUsername());
            result.put("sshConnectionId", connEntity.getConnectionId());
        } catch (Exception e) {
            // 获取连接信息失败不影响其他上下文注入
        }
    }

    private String safeExec(String terminalSessionId, String cmd) {
        try {
            String res = sshTerminalService.executeCommand(terminalSessionId, cmd);
            return res != null ? res.trim() : "";
        } catch (Exception e) { 
            return ""; 
        }
    }
}
