package cn.bugstack.ai.cases.react;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限确认等待管理器
 *
 * <p>跨层协调：ToolCallNode 发起确认请求 → 阻塞等待 →
 * PermissionResolveController（trigger 层）收到用户回写 → 唤醒
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class PermissionConfirmManager {

    /** confirmId → 等待锁 */
    private final Map<String, Object> waitLocks = new ConcurrentHashMap<>();

    /** confirmId → 确认结果 */
    private final Map<String, PermissionResolveResult> results = new ConcurrentHashMap<>();

    /**
     * 阻塞等待用户确认结果（带超时）
     *
     * @param confirmId 确认请求 ID
     * @param timeoutMs 超时时间
     * @return 用户确认结果，null=超时
     */
    public PermissionResolveResult awaitConfirmation(String confirmId, long timeoutMs) {
        Object lock = new Object();
        waitLocks.put(confirmId, lock);

        synchronized (lock) {
            try {
                // 先检查是否已有结果（用户可能在等待前已回复）
                PermissionResolveResult existing = results.get(confirmId);
                if (existing != null) {
                    return existing;
                }

                lock.wait(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待权限确认被中断: confirmId={}", confirmId);
            }
        }

        PermissionResolveResult result = results.get(confirmId);
        // 清理
        waitLocks.remove(confirmId);
        results.remove(confirmId);
        return result;
    }

    /**
     * 用户回写确认结果（由 Controller 调用）
     */
    public void resolve(String confirmId, boolean approved, String modifiedArgs) {
        PermissionResolveResult result = new PermissionResolveResult(confirmId, approved, modifiedArgs, System.currentTimeMillis());
        results.put(confirmId, result);

        Object lock = waitLocks.get(confirmId);
        if (lock != null) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    /**
     * 确认结果 DTO
     */
    public static class PermissionResolveResult {
        private final String confirmId;
        private final boolean approved;
        private final String modifiedArgs;
        private final long resolvedAt;

        public PermissionResolveResult(String confirmId, boolean approved, String modifiedArgs, long resolvedAt) {
            this.confirmId = confirmId;
            this.approved = approved;
            this.modifiedArgs = modifiedArgs;
            this.resolvedAt = resolvedAt;
        }

        public String getConfirmId() { return confirmId; }
        public boolean isApproved() { return approved; }
        public String getModifiedArgs() { return modifiedArgs; }
        public long getResolvedAt() { return resolvedAt; }
    }
}
