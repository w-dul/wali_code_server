package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.ssh.service.ISshTerminalService;
import com.google.adk.tools.Annotations.Schema;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 编译 / 测试 / Lint 验证工具
 *
 * <p>为智能体提供"改完代码后自检"的能力：
 * <ul>
 *   <li>compileProject - 自动检测项目类型并执行主源码编译校验</li>
 *   <li>compileTests - 编译测试源码（mvn test-compile / gradle testClasses）</li>
 *   <li>runUnitTests - 运行单元测试并提取失败摘要</li>
 *   <li>runLint - 自动检测项目类型并执行 lint/静态检查</li>
 * </ul>
 *
 * <p>执行模式：
 * <ul>
 *   <li>如果当前线程绑定了 SSH 终端会话，则在远程服务器执行</li>
 *   <li>否则在本地通过 bash -lc 执行</li>
 * </ul>
 */
@Slf4j
@Service
public class BuildValidationAdkTool {

    private static final long COMPILE_TIMEOUT_MS = 120_000L;
    private static final long TEST_COMPILE_TIMEOUT_MS = 120_000L;
    private static final long TEST_RUN_TIMEOUT_MS = 300_000L;
    private static final long LINT_TIMEOUT_MS = 90_000L;
    private static final int MAX_OUTPUT_LENGTH = 20_000;
    /** 错误摘要最大行数 */
    private static final int MAX_ERROR_SUMMARY_LINES = 50;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource(name = "sseToolProgressNotifier")
    private ToolProgressNotifier progressNotifier;

    public Map<String, Object> compileProject(
            @Schema(name = "projectPath", description = "要编译校验的项目目录，支持本地绝对路径或远程服务器路径")
            String projectPath) {
        String command = buildCompileCommand(projectPath);
        return executeValidation("compileProject", projectPath, command, COMPILE_TIMEOUT_MS);
    }

    /**
     * 编译测试源码（包括主源码 + 测试源码）
     *
     * <p>用于 AI 补全单元测试后验证测试代码能否编译通过。
     *
     * @param projectPath 项目目录
     * @return 编译结果（含 errorSummary 字段，提取关键错误行）
     */
    public Map<String, Object> compileTests(
            @Schema(name = "projectPath", description = "要编译测试代码的项目目录")
            String projectPath) {
        String command = buildTestCompileCommand(projectPath);
        return executeValidation("compileTests", projectPath, command, TEST_COMPILE_TIMEOUT_MS);
    }

    /**
     * 运行单元测试
     *
     * <p>支持指定测试类或运行全部测试，返回失败用例摘要。
     *
     * @param projectPath 项目目录
     * @param testClass   测试类全名（可选，如 com.example.MyServiceTest），为空则运行全部测试
     * @return 测试结果（含 testFailures 字段，提取失败用例信息）
     */
    public Map<String, Object> runUnitTests(
            @Schema(name = "projectPath", description = "要运行测试的项目目录")
            String projectPath,
            @Schema(name = "testClass", description = "测试类全名（可选，如 com.example.MyServiceTest），为空则运行全部测试", optional = true)
            String testClass) {
        String command = buildTestRunCommand(projectPath, testClass);
        return executeValidation("runUnitTests", projectPath, command, TEST_RUN_TIMEOUT_MS);
    }

    public Map<String, Object> runLint(
            @Schema(name = "projectPath", description = "要执行 lint 检查的项目目录,支持本地绝对路径或远程服务器路径")
            String projectPath) {
        String command = buildLintCommand(projectPath);
        return executeValidation("runLint", projectPath, command, LINT_TIMEOUT_MS);
    }

    private Map<String, Object> executeValidation(String toolName, String projectPath, String command, long timeoutMs) {
        notifyProgress(toolName, projectPath);

        try {
            String terminalSessionId = SshExecuteAdkTool.getCurrentTerminalSession();
            boolean remote = terminalSessionId != null && !terminalSessionId.isBlank();

            Map<String, Object> result = remote
                    ? executeRemote(toolName, projectPath, command, terminalSessionId, timeoutMs)
                    : executeLocal(toolName, projectPath, command, timeoutMs);

            boolean success = Boolean.TRUE.equals(result.get("success"));
            String summary = String.valueOf(result.getOrDefault("summary", success ? "执行成功" : "执行失败"));
            notifyProgressEnd(toolName, summary, success);
            notifyToolResult(toolName, projectPath, result);
            return result;
        } catch (Exception e) {
            log.error("[{}] 执行失败: path={}", toolName, projectPath, e);
            Map<String, Object> errResult = Map.of(
                    "success", false,
                    "path", projectPath,
                    "mode", "unknown",
                    "error", "执行失败: " + e.getMessage(),
                    "summary", "执行异常"
            );
            notifyProgressEnd(toolName, "执行异常: " + e.getMessage(), false);
            notifyToolResult(toolName, projectPath, errResult);
            return errResult;
        }
    }

    private Map<String, Object> executeRemote(String toolName, String projectPath, String command, String terminalSessionId, long timeoutMs) {
        if (!sshTerminalService.sessionExists(terminalSessionId)) {
            return Map.of(
                    "success", false,
                    "path", projectPath,
                    "mode", "remote",
                    "error", "SSH 终端会话不存在或已关闭",
                    "summary", "远程终端会话不可用"
            );
        }

        log.info("[{}] 远程执行: session={}, path={}", toolName, terminalSessionId, projectPath);
        String output = sshTerminalService.executeCommand(terminalSessionId, command, timeoutMs);
        return buildResult(projectPath, "remote", command, output);
    }

    private Map<String, Object> executeLocal(String toolName, String projectPath, String command, long timeoutMs) throws Exception {
        log.info("[{}] 本地执行: path={}", toolName, projectPath);

        Process process = new ProcessBuilder("bash", "-lc", command)
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return Map.of(
                    "success", false,
                    "path", projectPath,
                    "mode", "local",
                    "command", command,
                    "output", "执行超时,已强制终止",
                    "summary", "执行超时"
            );
        }

        String output = readProcessOutput(process.getInputStream());
        return buildResult(projectPath, "local", command, output, process.exitValue());
    }

    private Map<String, Object> buildResult(String projectPath, String mode, String command, String output) {
        return buildResult(projectPath, mode, command, output, inferExitCode(output));
    }

    private Map<String, Object> buildResult(String projectPath, String mode, String command, String output, int exitCode) {
        String safeOutput = truncate(output);
        boolean success = exitCode == 0 && !safeOutput.contains("UNSUPPORTED_PROJECT") && !safeOutput.contains("UNSUPPORTED_LINT");

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("path", projectPath);
        result.put("mode", mode);
        result.put("command", command);
        result.put("exitCode", exitCode);
        result.put("output", safeOutput);
        result.put("summary", success ? "校验通过" : "校验失败");

        if (!success) {
            result.put("error", safeOutput);
            // 提取关键错误行，帮助 AI 快速定位问题
            String errorSummary = extractErrorSummary(output);
            if (errorSummary != null && !errorSummary.isBlank()) {
                result.put("errorSummary", errorSummary);
            }
        }

        // 测试运行结果：提取失败用例
        if (output != null && (output.contains("Tests run:") || output.contains("BUILD FAILURE"))) {
            String testFailures = extractTestFailures(output);
            if (testFailures != null && !testFailures.isBlank()) {
                result.put("testFailures", testFailures);
            }
        }

        return result;
    }

    private String buildCompileCommand(String projectPath) {
        String path = shellQuote(projectPath);
        return "cd " + path + " && " +
                "if [ -f pom.xml ]; then mvn -q -DskipTests compile; " +
                "elif [ -f gradlew ]; then chmod +x ./gradlew >/dev/null 2>&1; ./gradlew classes; " +
                "elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then gradle classes; " +
                "elif [ -f package.json ]; then npm run build --if-present && npm run typecheck --if-present; " +
                "elif [ -f go.mod ]; then go build ./...; " +
                "elif [ -f Cargo.toml ]; then cargo check; " +
                "elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then python -m compileall .; " +
                "else echo UNSUPPORTED_PROJECT: 无法识别项目类型; exit 2; fi";
    }

    /**
     * 构建测试编译命令（主源码 + 测试源码）
     */
    private String buildTestCompileCommand(String projectPath) {
        String path = shellQuote(projectPath);
        return "cd " + path + " && " +
                "if [ -f pom.xml ]; then mvn -q test-compile; " +
                "elif [ -f gradlew ]; then chmod +x ./gradlew >/dev/null 2>&1; ./gradlew testClasses; " +
                "elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then gradle testClasses; " +
                "elif [ -f package.json ]; then npm run build --if-present && npx tsc --noEmit --project tsconfig.json 2>/dev/null || true; " +
                "elif [ -f go.mod ]; then go vet ./...; " +
                "elif [ -f Cargo.toml ]; then cargo test --no-run; " +
                "elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then python -m pytest --collect-only -q 2>/dev/null || true; " +
                "else echo UNSUPPORTED_PROJECT: 无法识别项目类型; exit 2; fi";
    }

    /**
     * 构建单元测试运行命令
     */
    private String buildTestRunCommand(String projectPath, String testClass) {
        String path = shellQuote(projectPath);
        String testFilter = (testClass != null && !testClass.isBlank()) ? " -Dtest=" + shellQuote(testClass) : "";
        return "cd " + path + " && " +
                "if [ -f pom.xml ]; then mvn test" + testFilter + " -q 2>&1; " +
                "elif [ -f gradlew ]; then chmod +x ./gradlew >/dev/null 2>&1; ./gradlew test" +
                    (testClass != null && !testClass.isBlank() ? " --tests " + shellQuote(testClass) : "") + "; " +
                "elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then gradle test" +
                    (testClass != null && !testClass.isBlank() ? " --tests " + shellQuote(testClass) : "") + "; " +
                "elif [ -f package.json ]; then npm test 2>&1; " +
                "elif [ -f go.mod ]; then go test ./... -v 2>&1; " +
                "elif [ -f Cargo.toml ]; then cargo test 2>&1; " +
                "elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then python -m pytest -v 2>&1; " +
                "else echo UNSUPPORTED_PROJECT: 无法识别项目类型; exit 2; fi";
    }

    /**
     * 从编译/测试输出中提取关键错误行（[ERROR] / error: / FAILED）
     *
     * <p>避免将几百行 Maven 日志全部传给 AI，只提取关键错误信息。
     */
    private String extractErrorSummary(String output) {
        if (output == null || output.isBlank()) return null;

        java.util.List<String> errorLines = new java.util.ArrayList<>();
        String[] lines = output.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (errorLines.size() >= MAX_ERROR_SUMMARY_LINES) break;

            // Maven [ERROR] 行
            if (trimmed.startsWith("[ERROR]")) {
                errorLines.add(trimmed);
                continue;
            }
            // 编译器 error: 行
            String lower = trimmed.toLowerCase();
            if (lower.contains("error:") || lower.contains("cannot find symbol")
                    || lower.contains("找不到符号") || lower.contains("程序包不存在")) {
                errorLines.add(trimmed);
                continue;
            }
            // Gradle 失败行
            if (lower.contains("execution failed for task") || lower.contains("what went wrong:")) {
                errorLines.add(trimmed);
                continue;
            }
            // 通用编译失败
            if (lower.contains("compilation failed") || lower.contains("build failed")) {
                errorLines.add(trimmed);
            }
        }

        if (errorLines.isEmpty()) return null;
        return String.join("\n", errorLines);
    }

    /**
     * 从测试输出中提取失败用例摘要
     *
     * <p>提取 "Tests run:" 统计行和 "FAILED" 用例行，帮助 AI 快速定位失败测试。
     */
    private String extractTestFailures(String output) {
        if (output == null || output.isBlank()) return null;

        java.util.List<String> failureLines = new java.util.ArrayList<>();
        String[] lines = output.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (failureLines.size() >= MAX_ERROR_SUMMARY_LINES) break;

            // Maven Surefire 测试统计行
            if (trimmed.contains("Tests run:") && (trimmed.contains("Failures:") || trimmed.contains("Errors:"))) {
                failureLines.add(trimmed);
                continue;
            }
            // FAILED 行
            if (trimmed.contains("FAILED") || trimmed.contains("<<< FAILURE!") || trimmed.contains("<<< ERROR!")) {
                failureLines.add(trimmed);
                continue;
            }
            // Gradle 测试失败
            String lower = trimmed.toLowerCase();
            if (lower.contains("test failed") || lower.contains("tests failed")) {
                failureLines.add(trimmed);
            }
        }

        if (failureLines.isEmpty()) return null;
        return String.join("\n", failureLines);
    }

    private String buildLintCommand(String projectPath) {
        String path = shellQuote(projectPath);
        return "cd " + path + " && " +
                "if [ -f package.json ]; then npm run lint --if-present; " +
                "elif [ -f Cargo.toml ]; then cargo clippy --quiet -- -D warnings; " +
                "elif [ -f go.mod ]; then (golangci-lint run || go vet ./...); " +
                "elif [ -f pyproject.toml ] || [ -f requirements.txt ] || [ -f setup.py ]; then (ruff check . || flake8 .); " +
                "elif [ -f pom.xml ] || [ -f build.gradle ] || [ -f build.gradle.kts ]; then echo UNSUPPORTED_LINT: 当前 Java 项目未配置统一 lint 命令; exit 2; " +
                "else echo UNSUPPORTED_LINT: 无法识别项目类型; exit 2; fi";
    }

    private String shellQuote(String value) {
        if (value == null || value.isBlank()) {
            return "'.'";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String readProcessOutput(InputStream inputStream) throws Exception {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private String truncate(String output) {
        if (output == null) return "";
        if (output.length() <= MAX_OUTPUT_LENGTH) return output;
        return output.substring(0, MAX_OUTPUT_LENGTH) + "\n... (truncated, total " + output.length() + " chars)";
    }

    private int inferExitCode(String output) {
        if (output == null) return 0;
        if (output.contains("BUILD SUCCESS") || output.contains("校验通过")) return 0;
        if (output.contains("UNSUPPORTED_PROJECT") || output.contains("UNSUPPORTED_LINT")) return 2;
        return containsFailure(output) ? 1 : 0;
    }

    private boolean containsFailure(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("exception")
                || lower.contains("permission denied")
                || lower.contains("command not found")
                || lower.contains("cannot find");
    }

    private void notifyProgress(String toolName, String args) {
        if (progressNotifier != null) {
            try {
                progressNotifier.onToolStart(toolName, args);
            } catch (Exception e) {
                log.debug("进度通知失败(不影响工具执行)", e);
            }
        }
    }

    private void notifyProgressEnd(String toolName, String summary, boolean success) {
        if (progressNotifier != null) {
            try {
                progressNotifier.onToolEnd(toolName, summary, success);
            } catch (Exception e) {
                log.debug("进度通知失败(不影响工具执行)", e);
            }
        }
    }

    /**
     * 推送工具原始返回值（已禁用 side channel）
     * <p>关闭 internalToolExecutionEnabled=false 后，工具结果通过 ADK 事件流的
     * FunctionResponse 传递给 AiCallNode，不再需要 side channel 推送，避免重复。
     */
    private void notifyToolResult(String toolName, String args, Map<String, Object> rawResult) {
        log.debug("[BuildValidationAdkTool] notifyToolResult 已禁用（side channel），工具结果通过 ADK FunctionResponse 推送");
    }
}
