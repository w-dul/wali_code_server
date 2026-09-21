package cn.bugstack.ai.domain.agent.service.armory.matter.tools.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * 本地指令分发器
 *
 * <p>职责：
 * - 通过 SSE 向 Client 下发本地执行指令
 * - 等待 Client 通过 HTTP POST 回传执行结果
 * - 管理指令生命周期（超时、断连、取消）
 *
 * <p>架构：
 * Server (大脑) ──SSE──► Client (手脚)
 *                 ◄──HTTP POST──
 *
 * @author walissh dev
 */
@Slf4j
@Service
public class CommandDispatcher {

    @Resource
    private ObjectMapper objectMapper;

    // ═══════════════════════════════════════════════════════════════
    //  会话 → 发射器映射（由 SseToolProgressNotifier 绑定/解绑）
    // ═══════════════════════════════════════════════════════════════

    /** sessionId → emitter（复用 SseToolProgressNotifier 的绑定机制） */
    private static volatile ResponseBodyEmitter currentEmitter;
    private static volatile String currentSessionId;

    /**
     * 绑定 emitter（由 AiCallNode 调用 Runner 前调用）
     */
    public static void bindEmitter(String sessionId, ResponseBodyEmitter emitter) {
        currentSessionId = sessionId;
        currentEmitter = emitter;
    }

    /**
     * 解绑 emitter
     */
    public static void unbindEmitter(String sessionId) {
        if (sessionId != null && sessionId.equals(currentSessionId)) {
            currentEmitter = null;
            currentSessionId = null;
        }
    }

    /**
     * 获取当前会话 ID
     */
    public static String currentSessionId() {
        return currentSessionId;
    }

    /**
     * 获取当前 emitter
     */
    public static ResponseBodyEmitter currentEmitter() {
        return currentEmitter;
    }

    // ═══════════════════════════════════════════════════════════════
    //  待完成指令管理
    // ═══════════════════════════════════════════════════════════════

    /** cmdId → 待完成的 Future */
    private final ConcurrentHashMap<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════
    //  Phase 2: 结果缓存 + 幂等执行
    // ═══════════════════════════════════════════════════════════════

    /** cmdId → 缓存的结果（GET /tool_result/pending 重放用，TTL 5min） */
    private final ConcurrentHashMap<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private static final long RESULT_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** 已提交的 cmdId 集合（幂等去重，TTL 5min） */
    private final ConcurrentHashMap<String, Long> submittedCmdIds = new ConcurrentHashMap<>();
    private static final long IDEMPOTENT_TTL_MS = 5 * 60 * 1000L;

    /**
     * 缓存结果（Client 回传结果时调用，同时写入缓存）
     */
    private void cacheResult(String cmdId, CommandResult result) {
        resultCache.put(cmdId, new CachedResult(result, System.currentTimeMillis()));
        // 清理过期缓存
        long now = System.currentTimeMillis();
        resultCache.entrySet().removeIf(e -> now - e.getValue().cachedAt > RESULT_CACHE_TTL_MS);
    }

    /**
     * 检查 cmdId 是否已提交（幂等去重）
     * @return true=重复，应拒绝
     */
    public boolean isDuplicate(String cmdId) {
        Long existing = submittedCmdIds.putIfAbsent(cmdId, System.currentTimeMillis());
        if (existing != null) {
            return true;
        }
        // 清理过期 cmdId
        long now = System.currentTimeMillis();
        submittedCmdIds.entrySet().removeIf(e -> now - e.getValue() > IDEMPOTENT_TTL_MS);
        return false;
    }

    /**
     * 获取并清除缓存的结果（GET /tool_result/pending 调用）
     * Client 轮询时调用，获取结果后从缓存中移除
     *
     * @param cmdId 指令 ID
     * @return 缓存的结果，不存在或已过期返回 null
     */
    public CommandResult pollResult(String cmdId) {
        CachedResult cached = resultCache.remove(cmdId);
        if (cached == null) return null;
        // 检查 TTL
        if (System.currentTimeMillis() - cached.cachedAt > RESULT_CACHE_TTL_MS) {
            return null;
        }
        return cached.result;
    }

    /**
     * 获取所有待轮询的缓存结果（供 Client 重连后批量检查）
     */
    public Map<String, CommandResult> pollAllResults() {
        Map<String, CommandResult> results = new HashMap<>();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CachedResult>> it = resultCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CachedResult> entry = it.next();
            if (now - entry.getValue().cachedAt <= RESULT_CACHE_TTL_MS) {
                results.put(entry.getKey(), entry.getValue().result);
            }
            it.remove();
        }
        return results;
    }

    /** 缓存结果内部类 */
    private static class CachedResult {
        final CommandResult result;
        final long cachedAt;
        CachedResult(CommandResult result, long cachedAt) {
            this.result = result;
            this.cachedAt = cachedAt;
        }
    }

    /**
     * 下发指令并阻塞等待结果
     *
     * @param request   指令请求
     * @param timeoutMs 超时时间（毫秒）
     * @return 执行结果
     */
    public CommandResult dispatchAndWait(CommandRequest request, long timeoutMs) {
        String cmdId = request.getCmdId();
        log.info("[CommandDispatcher] 下发指令: cmdId={}, type={}, command={}, timeoutMs={}",
                cmdId, request.getType(), request.getCommand(), timeoutMs);

        // 1. 检查 emitter 是否可用
        ResponseBodyEmitter emitter = currentEmitter;
        if (emitter == null) {
            log.warn("[CommandDispatcher] SSE emitter 不可用，无法下发指令: cmdId={}", cmdId);
            return CommandResult.error(cmdId, request.getSessionId(),
                    "本地执行环境不可用（SSE 连接未建立）", 0);
        }

        // 2. 创建 Future
        PendingCommand pending = new PendingCommand(request);
        pendingCommands.put(cmdId, pending);

        try {
            // 3. 通过 SSE 推送指令
            sendCommandViaSse(emitter, request);

            // 4. 阻塞等待结果
            CommandResult result = pending.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
            log.info("[CommandDispatcher] 指令完成: cmdId={}, status={}, exitCode={}, durationMs={}",
                    cmdId, result.getStatus(), result.getExitCode(), result.getDurationMs());
            return result;

        } catch (TimeoutException e) {
            log.warn("[CommandDispatcher] 指令超时: cmdId={}, timeoutMs={}", cmdId, timeoutMs);
            return CommandResult.timeout(cmdId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[CommandDispatcher] 指令被中断: cmdId={}", cmdId);
            return CommandResult.error(cmdId, request.getSessionId(), "指令被中断", 0);
        } catch (Exception e) {
            log.error("[CommandDispatcher] 指令下发异常: cmdId={}", cmdId, e);
            return CommandResult.error(cmdId, request.getSessionId(), "指令下发异常: " + e.getMessage(), 0);
        } finally {
            // 5. 清理
            pendingCommands.remove(cmdId);
        }
    }

    /**
     * Client 回传结果时调用
     *
     * @param cmdId  指令 ID
     * @param result 执行结果
     */
    public void completeCommand(String cmdId, CommandResult result) {
        // 1. 写入缓存（供 GET 轮询重放）
        cacheResult(cmdId, result);

        // 2. 唤醒阻塞的 Future
        PendingCommand pending = pendingCommands.get(cmdId);
        if (pending != null) {
            log.info("[CommandDispatcher] 收到指令结果: cmdId={}, status={}", cmdId, result.getStatus());
            pending.getFuture().complete(result);
        } else {
            log.warn("[CommandDispatcher] 收到未知指令结果: cmdId={}（可能已超时或已清理，结果已缓存供 GET 重放）", cmdId);
        }
    }

    /**
     * 获取当前待完成指令数量
     */
    public int getPendingCount() {
        return pendingCommands.size();
    }

    /**
     * 标记所有待完成指令为断连状态（Client 断开时调用）
     */
    public void markAllDisconnected() {
        log.warn("[CommandDispatcher] 标记所有待完成指令为断连状态，数量: {}", pendingCommands.size());
        for (Map.Entry<String, PendingCommand> entry : pendingCommands.entrySet()) {
            PendingCommand pending = pendingCommands.remove(entry.getKey());
            if (pending != null) {
                pending.getFuture().complete(
                        CommandResult.disconnected(entry.getKey()));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 通过 SSE 推送指令到 Client
     */
    private void sendCommandViaSse(ResponseBodyEmitter emitter, CommandRequest request) throws IOException {
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("event", request.getType());
        event.put("cmdId", request.getCmdId());
        event.put("command", request.getCommand());
        if (request.getCwd() != null) event.put("cwd", request.getCwd());
        event.put("timeoutMs", request.getTimeoutMs());
        event.put("sessionId", request.getSessionId());
        event.put("timestamp", System.currentTimeMillis());

        String json = objectMapper.writeValueAsString(event);
        emitter.send(json + "\n", org.springframework.http.MediaType.APPLICATION_JSON);

        log.debug("[CommandDispatcher] 指令已通过 SSE 推送: cmdId={}", request.getCmdId());
    }

    /**
     * 发送 SSE 心跳保活（静态方法，由 AiCallNode 事件循环调用）
     */
    public static boolean sendHeartbeat() {
        ResponseBodyEmitter emitter = currentEmitter;
        if (emitter == null) return false;
        try {
            String heartbeat = "{\"event\":\"heartbeat\",\"timestamp\":" + System.currentTimeMillis() + "}\n";
            emitter.send(heartbeat, org.springframework.http.MediaType.APPLICATION_JSON);
            return true;
        } catch (IOException e) {
            log.debug("[CommandDispatcher] 心跳发送失败: {}", e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部类
    // ═══════════════════════════════════════════════════════════════

    /**
     * 待完成指令
     */
    private static class PendingCommand {
        private final CommandRequest request;
        private final java.util.concurrent.CompletableFuture<CommandResult> future;

        public PendingCommand(CommandRequest request) {
            this.request = request;
            this.future = new java.util.concurrent.CompletableFuture<>();
        }

        public CommandRequest getRequest() {
            return request;
        }

        public java.util.concurrent.CompletableFuture<CommandResult> getFuture() {
            return future;
        }
    }
}
