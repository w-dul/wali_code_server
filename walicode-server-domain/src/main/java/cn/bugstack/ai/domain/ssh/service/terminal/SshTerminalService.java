package cn.bugstack.ai.domain.ssh.service.terminal;

import cn.bugstack.ai.domain.ssh.adapter.port.ISshSessionPort;
import cn.bugstack.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import cn.bugstack.ai.domain.ssh.model.entity.TerminalSessionEntity;
import cn.bugstack.ai.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH终端领域服务实现
 * 遵循单一职责原则，将终端会话管理委托给基础设施层
 *
 * @author waissh dev
 */
@Slf4j
@Service
public class SshTerminalService implements ISshTerminalService {

    private final ISshSessionPort sshSessionService;
    private final ITerminalSessionPort terminalSessionService;

    /** 会话ID -> 终端会话实体 映射 */
    private final Map<String, TerminalSessionEntity> sessionCache = new ConcurrentHashMap<>();

    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionService) {
        this.sshSessionService = sshSessionService;
        this.terminalSessionService = terminalSessionService;
    }

    @Override
    public TerminalSessionEntity openTerminal(String connectionId, int cols, int rows) {
        log.info("打开终端会话 connectionId={} cols={} rows={}", connectionId, cols, rows);

        // 1. 检查SSH连接是否已建立
        if (!sshSessionService.isConnected(connectionId)) {
            throw new IllegalStateException("SSH连接未建立，请先连接");
        }

        // 2. 清理同一 connectionId 的旧会话（基础设施层已关闭 channel，这里清理域层缓存）
        sessionCache.entrySet().removeIf(entry -> {
            if (connectionId.equals(entry.getValue().getConnectionId())) {
                log.info("清理旧终端会话缓存 sessionId={} connectionId={}", entry.getKey(), connectionId);
                return true;
            }
            return false;
        });

        // 3. 通过基础设施层打开终端会话
        String sessionId = terminalSessionService.openTerminal(connectionId, cols, rows);

        // 3. 创建并缓存会话实体
        TerminalSessionEntity entity = TerminalSessionEntity.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .cols(cols)
                .rows(rows)
                .status(1)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();

        sessionCache.put(sessionId, entity);
        log.info("终端会话创建成功 sessionId={}", sessionId);

        return entity;
    }

    /** Agent 执行命令后等待输出的最大时间（ms） */
    private static final long COMMAND_EXEC_WAIT_MS = 5000;

    /** Agent 执行命令后等待输出的检查间隔（ms） */
    private static final long COMMAND_EXEC_CHECK_INTERVAL_MS = 100;

    @Override
    public String executeCommand(String sessionId, String command) {
        return executeCommand(sessionId, command, COMMAND_EXEC_WAIT_MS);
    }

    @Override
    public String executeCommand(String sessionId, String command, long waitTimeoutMs) {
        log.info("Agent执行命令 sessionId={} command={}", sessionId, command);

        // 1. 校验会话
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        // 2. 开启 Agent 专用捕获模式（输出会被同时写入主缓冲区和 agent 专用缓冲区）
        // 这样前端轮询不会“偷走”agent 命令的输出
        terminalSessionService.setAgentCapture(sessionId, true);

        try {
            // 3. 清空 agent 专用缓冲区中残留的旧输出
            terminalSessionService.readAgentBuffer(sessionId);

            // 4. 发送命令到终端（command + \n 触发执行）
            terminalSessionService.write(sessionId, command + "\n");

            // 5. 更新活跃时间
            entity.touch();

            // 6. 等待命令执行完成：轮询 agent 专用缓冲区
            long deadline = System.currentTimeMillis() + Math.max(waitTimeoutMs, COMMAND_EXEC_CHECK_INTERVAL_MS);
            StringBuilder resultOutput = new StringBuilder();

            int emptyReadCount = 0;
            final int EMPTY_READ_THRESHOLD = 3;

            while (System.currentTimeMillis() < deadline) {
                // 从 agent 专用缓冲区读取，不受前端轮询影响
                String chunk = terminalSessionService.readAgentBuffer(sessionId);
                if (chunk != null && !chunk.isEmpty()) {
                    resultOutput.append(chunk);
                    emptyReadCount = 0;
                } else {
                    emptyReadCount++;
                    String current = resultOutput.toString();
                    if (emptyReadCount >= EMPTY_READ_THRESHOLD && current.length() > 0) {
                        // 检测到 prompt 特征，命令执行完成
                        if (current.matches(".*[#$][\\s\\r\\n].*") || current.contains("\r\n") && current.split("\r\n").length > 2) {
                            break;
                        }
                    }
                }
                try {
                    Thread.sleep(COMMAND_EXEC_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 最后再读一次确保不遗漏
            String finalChunk = terminalSessionService.readAgentBuffer(sessionId);
            if (finalChunk != null && !finalChunk.isEmpty()) {
                resultOutput.append(finalChunk);
            }

            String output = resultOutput.toString();
            log.info("命令执行完成 sessionId={} outputLength={} output={}", sessionId, output.length(),
                    output.length() > 300 ? output.substring(0, 300) + "..." : output);

            return output;

        } finally {
            // 7. 关闭 Agent 捕获模式
            terminalSessionService.setAgentCapture(sessionId, false);
        }
    }

    @Override
    public void resizeTerminal(String sessionId, int cols, int rows) {
        log.debug("调整终端大小 sessionId={} cols={} rows={}", sessionId, cols, rows);

        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        terminalSessionService.resize(sessionId, cols, rows);

        entity.setCols(cols);
        entity.setRows(rows);
        entity.touch();
    }

    @Override
    public TerminalSessionEntity getTerminalSession(String sessionId) {
        return sessionCache.get(sessionId);
    }

    @Override
    public void closeTerminal(String sessionId) {
        log.info("关闭终端会话 sessionId={}", sessionId);

        TerminalSessionEntity entity = sessionCache.remove(sessionId);
        if (entity != null) {
            terminalSessionService.closeSession(sessionId);
            log.info("终端会话已关闭 sessionId={}", sessionId);
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return sessionCache.containsKey(sessionId);
    }

    @Override
    public String readTerminal(String sessionId) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        return terminalSessionService.read(sessionId);
    }

    @Override
    public void writeTerminal(String sessionId, String input) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        terminalSessionService.write(sessionId, input);
        entity.touch();
    }

    @Override
    public String executeCommandStreaming(String sessionId, String command, long waitTimeoutMs,
                                          java.util.function.Consumer<String> chunkCallback) {
        log.info("Agent流式执行命令 sessionId={} command={}", sessionId, command);

        // 1. 校验会话
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        // 2. 开启 Agent 专用捕获模式
        terminalSessionService.setAgentCapture(sessionId, true);

        try {
            // 3. 清空旧输出
            terminalSessionService.readAgentBuffer(sessionId);

            // 4. 发送命令
            terminalSessionService.write(sessionId, command + "\n");
            entity.touch();

            // 5. 轮询 agent 专用缓冲区，每个 chunk 实时回调
            long deadline = System.currentTimeMillis() + Math.max(waitTimeoutMs, COMMAND_EXEC_CHECK_INTERVAL_MS);
            StringBuilder resultOutput = new StringBuilder();

            int emptyReadCount = 0;
            final int EMPTY_READ_THRESHOLD = 3;

            while (System.currentTimeMillis() < deadline) {
                String chunk = terminalSessionService.readAgentBuffer(sessionId);
                if (chunk != null && !chunk.isEmpty()) {
                    resultOutput.append(chunk);
                    emptyReadCount = 0;
                    // 实时推送 chunk
                    try {
                        chunkCallback.accept(chunk);
                    } catch (Exception ce) {
                        log.debug("chunkCallback 异常（不影响执行）", ce);
                    }
                } else {
                    emptyReadCount++;
                    String current = resultOutput.toString();
                    if (emptyReadCount >= EMPTY_READ_THRESHOLD && current.length() > 0) {
                        if (current.matches(".*[#$][\s\r\n].*") || current.contains("\r\n") && current.split("\r\n").length > 2) {
                            break;
                        }
                    }
                }
                try {
                    Thread.sleep(COMMAND_EXEC_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 最后再读一次
            String finalChunk = terminalSessionService.readAgentBuffer(sessionId);
            if (finalChunk != null && !finalChunk.isEmpty()) {
                resultOutput.append(finalChunk);
                try {
                    chunkCallback.accept(finalChunk);
                } catch (Exception ce) {
                    log.debug("chunkCallback 异常（不影响执行）", ce);
                }
            }

            String output = resultOutput.toString();
            log.info("流式命令执行完成 sessionId={} outputLength={}", sessionId, output.length());
            return output;

        } finally {
            terminalSessionService.setAgentCapture(sessionId, false);
        }
    }

}
