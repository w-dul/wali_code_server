package cn.bugstack.ai.domain.agent.service.armory.matter.tools.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指令执行结果（Client → Server，通过 HTTP POST 回传）
 *
 * @author walissh dev
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandResult {

    /** 对应的指令 ID */
    private String cmdId;

    /** 会话 ID */
    private String sessionId;

    /** 执行状态 */
    private Status status;

    /** 命令输出 */
    private String output;

    /** 退出码 */
    private int exitCode;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 错误信息（仅 status=error 时有值） */
    private String error;

    /** 命令是否成功执行 */
    private boolean success;

    public enum Status {
        SUCCESS, ERROR, TIMEOUT, CANCELLED, DISCONNECTED
    }

    /**
     * 构建成功结果
     */
    public static CommandResult success(String cmdId, String sessionId, String output, int exitCode, long durationMs) {
        return CommandResult.builder()
                .cmdId(cmdId)
                .sessionId(sessionId)
                .status(Status.SUCCESS)
                .output(output)
                .exitCode(exitCode)
                .durationMs(durationMs)
                .success(exitCode == 0)
                .build();
    }

    /**
     * 构建错误结果
     */
    public static CommandResult error(String cmdId, String sessionId, String error, long durationMs) {
        return CommandResult.builder()
                .cmdId(cmdId)
                .sessionId(sessionId)
                .status(Status.ERROR)
                .error(error)
                .durationMs(durationMs)
                .success(false)
                .build();
    }

    /**
     * 构建超时结果
     */
    public static CommandResult timeout(String cmdId) {
        return CommandResult.builder()
                .cmdId(cmdId)
                .status(Status.TIMEOUT)
                .error("指令执行超时")
                .success(false)
                .build();
    }

    /**
     * 构建断连结果
     */
    public static CommandResult disconnected(String cmdId) {
        return CommandResult.builder()
                .cmdId(cmdId)
                .status(Status.DISCONNECTED)
                .error("客户端连接断开")
                .success(false)
                .build();
    }
}
