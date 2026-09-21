package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.service.armory.matter.tools.command.CommandDispatcher;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.command.CommandRequest;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.command.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.Annotations.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 本地命令执行 ADK 工具
 *
 * <p>通过 SSE 指令通道下发到 Client 执行，支持 Server 云部署：
 * <ul>
 *   <li>Server 通过 SSE 推送 execute_local_command 事件到 Client</li>
 *   <li>Client 调用 Tauri 本地执行后，HTTP POST 回传结果</li>
 *   <li>Server 通过 CompletableFuture 阻塞等待结果</li>
 * </ul>
 *
 * @author walissh dev
 */
@Slf4j
@Service
public class LocalExecuteAdkTool {

    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 60_000L;

    /** 命令输出最大字符数（防止大量日志撑爆 AI context） */
    private static final int MAX_OUTPUT_LENGTH = 30_000;

    /** 危险命令模式（需要拦截） */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "\\b(rm\\s+-rf\\s+/|dd\\s+if=|mkfs\\.|:\\(\\)\\s*\\{|>\\s*/dev/sd|chmod\\s+-R\\s+777\\s+/)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Resource
    private ObjectMapper objectMapper;

    @Resource(name = "sseToolProgressNotifier")
    private ToolProgressNotifier progressNotifier;

    @Resource
    private CommandDispatcher commandDispatcher;

    /**
     * 本地执行 Shell 命令
     *
     * @param command 要执行的 Shell 命令
     * @return 执行结果（command, output, success, exitCode）
     */
    public Map<String, Object> executeLocalCommand(
            @Schema(name = "command", description = "要执行的本地 Shell 命令，如: ls -la, mvn clean compile, npm run build, git status")
            String command) {
        return executeLocalCommand(command, null, DEFAULT_COMMAND_TIMEOUT_MS);
    }

    /**
     * 本地执行 Shell 命令（支持工作目录和超时）
     *
     * @param command 要执行的 Shell 命令
     * @param cwd 工作目录（可选），如 /Users/xxx/projects/myapp
     * @param timeoutMs 超时时间（毫秒），默认 60000
     * @return 执行结果
     */
    public Map<String, Object> executeLocalCommand(
            @Schema(name = "command", description = "要执行的本地 Shell 命令，如: ls -la, mvn test, docker compose up")
            String command,
            @Schema(name = "cwd", description = "工作目录（可选），默认为当前目录")
            String cwd,
            @Schema(name = "timeoutMs", description = "命令最大等待时间（毫秒），默认 60000，最大 300000")
            Long timeoutMs) {

        long effectiveTimeoutMs = (timeoutMs != null && timeoutMs > 0) ? Math.min(timeoutMs, 300_000L) : DEFAULT_COMMAND_TIMEOUT_MS;

        log.info("[LocalExecute] command={}, cwd={}, timeoutMs={}", command, cwd, effectiveTimeoutMs);

        // 危险命令检测
        if (DANGEROUS_PATTERN.matcher(command).find()) {
            log.warn("[LocalExecute] 危险命令被拦截: {}", command);
            Map<String, Object> blockedResult = Map.of(
                    "success", false,
                    "output", "⚠️ 危险命令被拦截: " + command + "\n该命令可能导致系统损坏或数据丢失。如确需执行，请手动在终端操作。",
                    "command", command
            );
            notifyToolResult("executeLocalCommand", command, blockedResult);
            return blockedResult;
        }

        try {
            // ═══════════════════════════════════════════════════════
            //  优先：通过 SSE 指令通道下发到 Client 执行
            // ═══════════════════════════════════════════════════════
            CommandRequest cmdRequest = CommandRequest.executeLocal(
                    CommandDispatcher.currentSessionId(), command, cwd, effectiveTimeoutMs);

            CommandResult cmdResult = commandDispatcher.dispatchAndWait(cmdRequest, effectiveTimeoutMs);

            // 构建返回结果（使用 HashMap，允许 null value）
            Map<String, Object> result = new HashMap<>();
            result.put("command", command);

            String safeOutput = cmdResult.getOutput() != null ? cmdResult.getOutput() : "";
            boolean truncated = safeOutput.length() > MAX_OUTPUT_LENGTH;
            if (truncated) {
                safeOutput = safeOutput.substring(0, MAX_OUTPUT_LENGTH)
                        + "\n... (output truncated, total " + safeOutput.length() + " chars)";
            }
            result.put("output", safeOutput);
            result.put("outputTruncated", truncated);
            result.put("success", cmdResult.isSuccess());
            result.put("exitCode", cmdResult.getExitCode());
            result.put("timeoutMs", effectiveTimeoutMs);

            if (!cmdResult.isSuccess()) {
                String suggestion = analyzeError(cmdResult.getOutput());
                if (suggestion != null) {
                    result.put("suggestion", suggestion);
                }
            }

            log.info("[LocalExecute] 命令执行完成: success={}, outputLength={}, durationMs={}",
                    cmdResult.isSuccess(), safeOutput.length(), cmdResult.getDurationMs());
            notifyToolResult("executeLocalCommand", command, result);
            return result;

        } catch (Exception e) {
            log.error("[LocalExecute] 命令执行异常: command={}", command, e);
            Map<String, Object> errResult = new java.util.HashMap<>();
            errResult.put("success", false);
            errResult.put("output", "命令执行异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            errResult.put("command", command);
            notifyToolResult("executeLocalCommand", command, errResult);
            return errResult;
        }
    }

    /**
     * 检查本地执行服务是否可用
     *
     * <p>通过 CommandDispatcher 检查 SSE 连接状态
     *
     * @return 服务状态信息
     */
    public Map<String, Object> checkService() {
        boolean connected = CommandDispatcher.currentEmitter() != null;
        String sessionId = CommandDispatcher.currentSessionId();

        return Map.of(
                "available", connected,
                "sessionId", sessionId != null ? sessionId : "",
                "status", connected ? "本地执行服务正常（SSE 已连接）" : "Client 未连接（SSE 未建立）",
                "pendingCommands", commandDispatcher.getPendingCount()
        );
    }

    /**
     * 分析错误并提供解决建议
     */
    private String analyzeError(String output) {
        if (output == null) return null;

        String lowerOutput = output.toLowerCase();

        if (lowerOutput.contains("command not found")) {
            return "命令不存在。可能原因：命令拼写错误、软件未安装、或命令不在 PATH 中。建议检查命令名称或安装对应软件包。";
        }
        if (lowerOutput.contains("permission denied")) {
            return "权限不足。建议使用 sudo 提升权限（macOS/Linux），或以管理员身份运行（Windows）。";
        }
        if (lowerOutput.contains("no such file or directory")) {
            return "文件或目录不存在。建议检查路径是否正确，或使用绝对路径。";
        }
        if (lowerOutput.contains("cannot find") || lowerOutput.contains("could not find")) {
            return "未找到文件/依赖。建议检查文件路径、依赖配置或构建工具是否正确安装。";
        }
        if (lowerOutput.contains("compilation failed") || lowerOutput.contains("build failed")) {
            return "编译/构建失败。请检查代码中的编译错误和依赖问题。";
        }
        return "执行失败，请检查命令语法和输出信息中的错误详情。";
    }

    /**
     * 推送工具原始返回值（已禁用 side channel）
     * <p>关闭 internalToolExecutionEnabled=false 后，工具结果通过 ADK 事件流的
     * FunctionResponse 传递给 AiCallNode，不再需要 side channel 推送，避免重复。
     */
    private void notifyToolResult(String toolName, String args, Map<String, Object> rawResult) {
        log.debug("[LocalExecuteAdkTool] notifyToolResult 已禁用（side channel），工具结果通过 ADK FunctionResponse 推送");
    }
}
