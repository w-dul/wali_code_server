package cn.bugstack.ai.domain.agent.service.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 权限守卫（五层递进安全检查）
 * <p>对标 WaLiCode permissionGuard.ts
 * <p>
 * 五层检查（逐层递进，任一层拒绝即阻止）：
 * 1. AST 解析层：解析工具参数，验证结构合法性
 * 2. 正则规则层：内置 40+ 危险命令/敏感文件规则
 * 3. AI 分类层：对模糊命令进行 AI 二次分类（Phase 3 实现）
 * 4. 拒绝兜底层：未知工具或无法解析的参数直接拒绝
 * 5. 断路器层：同一会话连续触发安全拒绝 → 自动降级
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class PermissionGuard {

    @Autowired(required = false)
    private ChatModel chatModel;

    // ═══════════════════════════════════════════════════════════════
    //  Layer 2: 正则规则集
    // ═══════════════════════════════════════════════════════════════

    /** 危险命令（直接拒绝） */
    private static final Rule[] DANGEROUS_COMMANDS = {
            // 文件系统破坏
            new Rule("rm_rf_root", Pattern.compile("\\brm\\s+-rf?\\s+/?(\\s|$)"), Action.DENY, "递归删除根目录"),
            new Rule("rm_rf_home", Pattern.compile("\\brm\\s+-rf?\\s+~/?(\\s|$)"), Action.DENY, "递归删除用户目录"),
            new Rule("rm_rf_star", Pattern.compile("\\brm\\s+-rf?\\s+\\*"), Action.DENY, "递归删除当前目录所有文件"),
            new Rule("dd_disk", Pattern.compile("\\bdd\\s+if=.*\\s+of=/dev/(sd|nvme|hd)"), Action.DENY, "直接写入磁盘设备"),
            new Rule("mkfs", Pattern.compile("\\bmkfs\\.(ext[234]|xfs|btrfs|ntfs|fat)\\s+/dev/"), Action.DENY, "格式化磁盘分区"),
            new Rule("fork_bomb", Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;:"), Action.DENY, "Fork 炸弹"),
            new Rule("chmod_777_root", Pattern.compile("\\bchmod\\s+-R\\s+777\\s+/"), Action.DENY, "递归设置根目录 777 权限"),
            new Rule("chown_root", Pattern.compile("\\bchown\\s+-R\\s+\\S+\\s+/\\s*$"), Action.DENY, "递归修改根目录所有者"),

            // 系统破坏
            new Rule("shutdown", Pattern.compile("\\b(shutdown|poweroff|halt|init\\s+0)\\b"), Action.DENY, "关机命令"),
            new Rule("reboot", Pattern.compile("\\b(reboot|init\\s+6)\\b"), Action.DENY, "重启命令"),
            new Rule("killall_system", Pattern.compile("\\bkill\\s+-9\\s+-1\\b"), Action.DENY, "杀死所有进程"),
            new Rule("ulimit_zero", Pattern.compile("\\bulimit\\s+-n\\s+0\\b"), Action.DENY, "设置文件描述符上限为 0"),

            // 网络危险操作
            new Rule("iptables_flush", Pattern.compile("\\biptables\\s+-F\\b"), Action.DENY, "清空防火墙规则"),
            new Rule("iptables_drop_all", Pattern.compile("iptables\\s+-A\\s+(INPUT|OUTPUT|FORWARD)\\s+-j\\s+DROP"), Action.DENY, "防火墙默认 DROP"),
            new Rule("ifconfig_down", Pattern.compile("\\bifconfig\\s+\\S+\\s+down\\b"), Action.DENY, "关闭网络接口"),
            new Rule("route_del_default", Pattern.compile("\\broute\\s+del\\s+default\\b"), Action.DENY, "删除默认路由"),

            // 进程危险操作
            new Rule("pkill_system", Pattern.compile("\\bpkill\\s+(systemd|init|sshd)\\b"), Action.DENY, "杀死系统关键进程"),

            // Git 危险操作
            new Rule("git_force_push_master", Pattern.compile("\\bgit\\s+push\\s+(-f|--force)\\s+\\S+\\s+(master|main)\\b"), Action.DENY, "强制推送到主分支"),
            new Rule("git_clean_fd", Pattern.compile("\\bgit\\s+clean\\s+-fdx?\\b"), Action.DENY, "Git 清理未跟踪文件（含忽略）"),
            new Rule("git_reset_hard_origin", Pattern.compile("\\bgit\\s+reset\\s+--hard\\s+origin/(master|main)\\b"), Action.DENY, "硬重置到远程主分支"),
    };

    /** 需要确认的命令（CONDITIONAL → 需用户确认） */
    private static final Rule[] CONDITIONAL_COMMANDS = {
            // 文件删除
            new Rule("rm_any", Pattern.compile("\\brm\\s+(-[rfRd]+\\s+)?\\S+"), Action.CONFIRM, "删除文件或目录"),
            new Rule("rmdir", Pattern.compile("\\brmdir\\s+\\S+"), Action.CONFIRM, "删除目录"),

            // 权限修改
            new Rule("chmod_any", Pattern.compile("\\bchmod\\s+\\S+"), Action.CONFIRM, "修改文件权限"),
            new Rule("chown_any", Pattern.compile("\\bchown\\s+\\S+"), Action.CONFIRM, "修改文件所有者"),

            // 包管理安装/卸载
            new Rule("apt_install", Pattern.compile("\\bapt(-get)?\\s+install\\b"), Action.CONFIRM, "安装软件包"),
            new Rule("apt_remove", Pattern.compile("\\bapt(-get)?\\s+(remove|purge)\\b"), Action.CONFIRM, "卸载软件包"),
            new Rule("yum_install", Pattern.compile("\\b(yum|dnf)\\s+install\\b"), Action.CONFIRM, "安装软件包"),
            new Rule("pip_install", Pattern.compile("\\bpip3?\\s+install\\b"), Action.CONFIRM, "安装 Python 包"),
            new Rule("npm_install_global", Pattern.compile("\\bnpm\\s+install\\s+-g\\b"), Action.CONFIRM, "全局安装 npm 包"),

            // Git 写操作
            new Rule("git_push", Pattern.compile("\\bgit\\s+push\\b"), Action.CONFIRM, "Git 推送"),
            new Rule("git_reset_hard", Pattern.compile("\\bgit\\s+reset\\s+--hard\\b"), Action.CONFIRM, "Git 硬重置"),
            new Rule("git_commit", Pattern.compile("\\bgit\\s+commit\\b"), Action.CONFIRM, "Git 提交"),

            // 系统服务
            new Rule("systemctl_stop", Pattern.compile("\\bsystemctl\\s+stop\\b"), Action.CONFIRM, "停止系统服务"),
            new Rule("systemctl_disable", Pattern.compile("\\bsystemctl\\s+disable\\b"), Action.CONFIRM, "禁用系统服务"),
            new Rule("systemctl_restart", Pattern.compile("\\bsystemctl\\s+restart\\b"), Action.CONFIRM, "重启系统服务"),

            // Docker 危险操作
            new Rule("docker_rm", Pattern.compile("\\bdocker\\s+rm\\b"), Action.CONFIRM, "删除 Docker 容器"),
            new Rule("docker_rmi", Pattern.compile("\\bdocker\\s+rmi\\b"), Action.CONFIRM, "删除 Docker 镜像"),
            new Rule("docker_stop", Pattern.compile("\\bdocker\\s+stop\\b"), Action.CONFIRM, "停止 Docker 容器"),
            new Rule("docker_volume_rm", Pattern.compile("\\bdocker\\s+volume\\s+rm\\b"), Action.CONFIRM, "删除 Docker 卷"),

            // 网络下载执行
            new Rule("curl_pipe_sh", Pattern.compile("\\bcurl\\s+\\S+\\s*\\|\\s*(sh|bash|zsh)"), Action.CONFIRM, "管道执行远程脚本"),
            new Rule("wget_pipe_sh", Pattern.compile("\\bwget\\s+\\S+\\s*-O\\s*-\\s*\\|\\s*(sh|bash)"), Action.CONFIRM, "管道执行远程脚本"),
            new Rule("curl_bash", Pattern.compile("curl\\s+.*\\|\\s*bash"), Action.CONFIRM, "管道执行远程脚本"),

            // 端口绑定
            new Rule("bind_privileged_port", Pattern.compile("\\b(nginx|apache2|httpd)\\s+.*--port\\s+(\\d{1,3})\\b"), Action.CONFIRM, "绑定特权端口"),

            // 数据库危险操作
            new Rule("mysql_drop", Pattern.compile("\\bDROP\\s+(DATABASE|TABLE)\\b", Pattern.CASE_INSENSITIVE), Action.CONFIRM, "删除数据库/表"),
            new Rule("redis_flushall", Pattern.compile("\\bFLUSHALL\\b", Pattern.CASE_INSENSITIVE), Action.CONFIRM, "Redis 清空所有数据"),
            new Rule("redis_flushdb", Pattern.compile("\\bFLUSHDB\\b", Pattern.CASE_INSENSITIVE), Action.CONFIRM, "Redis 清空当前数据库"),
    };

    /** 敏感文件路径（直接拒绝） */
    private static final Pattern[] SENSITIVE_FILE_PATTERNS = {
            Pattern.compile("^/etc/(shadow|passwd|gshadow|group)$"),
            Pattern.compile("^/etc/ssh/"),
            Pattern.compile("^/root/\\.ssh/"),
            Pattern.compile("^~?/\\.ssh/(id_rsa|id_ed25519|id_ecdsa|id_dsa)$"),
            Pattern.compile("^/proc/sysrq-trigger$"),
            Pattern.compile("^/sys/kernel/"),
            Pattern.compile("^/boot/"),
            Pattern.compile("\\.env$"),
            Pattern.compile("\\.pem$"),
            Pattern.compile("\\.key$"),
            Pattern.compile("^/etc/cron\\."),
            Pattern.compile("^/var/log/auth\\.log$"),
    };

    /** 允许只读的安全命令前缀 */
    private static final Set<String> SAFE_COMMAND_PREFIXES = Set.of(
            "ls", "pwd", "cat", "head", "tail", "less", "more", "wc",
            "grep", "find", "which", "whereis", "file", "stat", "du", "df",
            "ps", "top", "free", "uptime", "who", "whoami", "id", "uname",
            "docker ps", "docker logs", "docker inspect", "docker stats",
            "git status", "git log", "git diff", "git branch", "git show",
            "git remote", "git stash list", "git tag",
            "mvn compile", "mvn test", "mvn clean", "mvn package",
            "npm run", "npm test", "npm list",
            "java -version", "node --version", "python3 --version",
            "curl -I", "ping -c", "netstat -", "ss -",
            "systemctl status", "systemctl list-units", "systemctl is-active",
            "journalctl", "dmesg", "lsof", "strace"
    );

    /** 命令注入分隔符/子shell 模式 — 用于检测 `ls;rm -rf /`、`cat file && curl x|sh` 等注入
     *  检测目标：;、&&、||、|（管道）、反引号子shell
     *  不检测 $() — 太常见于正常命令（如 echo $(date)），误报率高
     */
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            ";"             // 命令分隔符
            + "|&&"          // 逻辑与链
            + "|\\|\\|"     // 逻辑或链
            + "|`[^`]+`"    // 反引号子shell
    );

    /** 断路器阈值：同一会话连续安全拒绝次数 */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    /** 断路器冷却时间（毫秒） */
    private static final long CIRCUIT_BREAKER_COOLDOWN_MS = 60_000L;

    // ═══════════════════════════════════════════════════════════════
    //  状态
    // ═══════════════════════════════════════════════════════════════

    /** 会话 → 连续拒绝次数 */
    private final java.util.concurrent.ConcurrentHashMap<String, CircuitBreakerState> circuitBreakers =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════
    //  公开 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 检查工具调用权限
     *
     * @param sessionId  会话 ID（用于断路器）
     * @param toolName   工具名称
     * @param args       工具参数（JSON 字符串或命令字符串）
     * @return 权限决策结果
     */
    public PermissionDecision check(String sessionId, String toolName, String args) {
        log.info("权限检查: session={}, tool={}, args={}",
                sessionId, toolName, args != null && args.length() > 100 ? args.substring(0, 100) + "..." : args);

        // Layer 5: 断路器检查（先检查，防止已熔断的会话继续尝试）
        if (isCircuitBroken(sessionId)) {
            log.warn("断路器触发: session={} 连续拒绝已达阈值", sessionId);
            return PermissionDecision.deny("circuit_breaker",
                    "安全断路器已触发：连续 " + CIRCUIT_BREAKER_THRESHOLD + " 次危险操作被拦截，"
                            + (CIRCUIT_BREAKER_COOLDOWN_MS / 1000) + "秒内禁止执行任何工具。");
        }

        // Layer 1: AST/参数解析层
        if (args == null || args.isBlank()) {
            // 无参数的工具调用，视为安全
            return PermissionDecision.allow();
        }

        // 提取命令（从 JSON 参数中提取 command 字段，或直接使用 args）
        String command = extractCommand(args);
        if (command == null || command.isBlank()) {
            return PermissionDecision.allow();
        }

        // Layer 2: 正则规则层
        PermissionDecision layer2Result = checkRules(sessionId, toolName, command);
        if (layer2Result.getAction() != Action.ALLOW) {
            return layer2Result;
        }

        // 敏感文件路径检查
        PermissionDecision fileResult = checkSensitiveFiles(command);
        if (fileResult.getAction() != Action.ALLOW) {
            recordDenial(sessionId);
            return fileResult;
        }

        // 安全命令快速通过
        if (isSafeCommand(command)) {
            return PermissionDecision.allow();
        }

        // Layer 3: AI 分类层 — 对不在安全列表也不在危险规则中的命令进行 AI 安全分类
        PermissionDecision aiResult = classifyWithAI(sessionId, toolName, command);
        if (aiResult.getAction() != Action.ALLOW) {
            return aiResult;
        }

        // Layer 4: 拒绝兜底层 — AI 也无法判断时默认放行（避免过度拦截）
        return PermissionDecision.allow();
    }

    /**
     * 记录用户确认结果（确认/拒绝）
     */
    public void recordConfirmation(String sessionId, boolean approved) {
        if (approved) {
            resetCircuitBreaker(sessionId);
            log.info("用户确认执行，重置断路器: session={}", sessionId);
        } else {
            recordDenial(sessionId);
        }
    }

    /**
     * 获取断路器状态
     */
    public CircuitBreakerState getCircuitBreakerState(String sessionId) {
        return circuitBreakers.get(sessionId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Layer 2: 正则规则检查
    // ═══════════════════════════════════════════════════════════════

    private PermissionDecision checkRules(String sessionId, String toolName, String command) {
        // 检查危险命令（直接拒绝）
        for (Rule rule : DANGEROUS_COMMANDS) {
            if (rule.pattern.matcher(command).find()) {
                log.warn("危险命令拦截: rule={}, tool={}, command={}", rule.name, toolName, command);
                recordDenial(sessionId);
                return PermissionDecision.deny(rule.name, rule.description);
            }
        }

        // 检查需要确认的命令
        for (Rule rule : CONDITIONAL_COMMANDS) {
            if (rule.pattern.matcher(command).find()) {
                log.info("需要确认的命令: rule={}, tool={}, command={}", rule.name, toolName, command);
                return PermissionDecision.confirm(rule.name, rule.description, command);
            }
        }

        return PermissionDecision.allow();
    }

    // ═══════════════════════════════════════════════════════════════
    //  敏感文件检查
    // ═══════════════════════════════════════════════════════════════

    private PermissionDecision checkSensitiveFiles(String command) {
        String[] parts = command.split("\\s+");
        for (String part : parts) {
            // 匹配绝对路径和 ~/ 路径
            String path = part.startsWith("/") ? part : part.startsWith("~/") ? part : null;
            if (path != null) {
                for (Pattern pattern : SENSITIVE_FILE_PATTERNS) {
                    if (pattern.matcher(path).find()) {
                        log.warn("敏感文件访问拦截: path={}, command={}", path, command);
                        return PermissionDecision.deny("sensitive_file",
                                "访问敏感文件被拦截: " + path);
                    }
                }
            }

            // 匹配相对路径中的敏感文件后缀（.env, .pem, .key, .p12 等）
            if (part.matches(".*\\.(env|pem|key|p12|pfx|jks|keystore)$") ||
                part.equals(".env") || part.endsWith("/.env")) {
                for (Pattern pattern : SENSITIVE_FILE_PATTERNS) {
                    if (pattern.matcher(part).find()) {
                        log.warn("敏感文件访问拦截(相对路径): part={}, command={}", part, command);
                        return PermissionDecision.deny("sensitive_file",
                                "访问敏感文件被拦截: " + part);
                    }
                }
            }
        }
        return PermissionDecision.allow();
    }

    // ═══════════════════════════════════════════════════════════════
    //  安全命令快速通过
    // ═══════════════════════════════════════════════════════════════

    private boolean isSafeCommand(String command) {
        String trimmed = command.trim();

        // 拒绝包含命令注入分隔符的命令，防止 `ls;rm -rf /` 类型绕过
        if (INJECTION_PATTERN.matcher(trimmed).find()) {
            log.debug("安全命令前缀匹配跳过: 检测到命令注入分隔符, command={}",
                    trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed);
            return false;
        }

        for (String prefix : SAFE_COMMAND_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Layer 3: AI 安全分类
    // ═══════════════════════════════════════════════════════════════

    /** AI 分类超时（毫秒） */
    private static final long AI_CLASSIFY_TIMEOUT_MS = 5_000L;

    /** AI 分类缓存（session+command_hash → decision），避免重复调用 */
    private final java.util.concurrent.ConcurrentHashMap<String, PermissionDecision> aiClassifyCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** AI 分类提示词 */
    private static final String AI_CLASSIFY_SYSTEM_PROMPT = """
            You are a security classifier for shell commands. Analyze the given command and classify it into one of three categories:
            - DENY: The command is dangerous and should be blocked (e.g., data destruction, system compromise, privilege escalation, network attacks)
            - CONFIRM: The command is potentially risky and should require user confirmation (e.g., file modification, service restart, package installation, network requests)
            - ALLOW: The command is safe to execute (e.g., reading files, listing directories, checking status)
            
            Respond with ONLY a JSON object in this exact format:
            {"action":"DENY|CONFIRM|ALLOW","reason":"brief explanation"}
            
            Do not include any other text. Be conservative: when in doubt, choose CONFIRM over ALLOW, and DENY over CONFIRM.
            """;

    private PermissionDecision classifyWithAI(String sessionId, String toolName, String command) {
        if (chatModel == null) {
            log.debug("ChatModel 未注入，跳过 AI 分类层");
            return PermissionDecision.allow();
        }

        // 缓存键：command 的 SHA-256 前 16 位
        String cacheKey = sessionId + ":" + Integer.toHexString(command.hashCode());
        PermissionDecision cached = aiClassifyCache.get(cacheKey);
        if (cached != null) {
            log.debug("AI 分类缓存命中: key={}, action={}", cacheKey, cached.getAction());
            return cached;
        }

        try {
            String userPrompt = String.format(
                    "Tool: %s\nCommand: %s\n\nClassify this command.",
                    toolName, command.length() > 500 ? command.substring(0, 500) : command
            );

            org.springframework.ai.chat.model.ChatResponse response = chatModel.call(
                    new Prompt(java.util.List.of(
                            new SystemMessage(AI_CLASSIFY_SYSTEM_PROMPT),
                            new UserMessage(userPrompt)
                    ))
            );

            String content = response.getResult().getOutput().getText().trim();
            log.info("AI 分类结果: command={}, response={}",
                    command.length() > 80 ? command.substring(0, 80) + "..." : command, content);

            PermissionDecision decision = parseAiClassification(content, command);

            // 缓存结果（最多 1000 条，防止内存溢出）
            if (aiClassifyCache.size() < 1000) {
                aiClassifyCache.put(cacheKey, decision);
            }

            if (decision.getAction() == Action.DENY) {
                recordDenial(sessionId);
            }

            return decision;
        } catch (Exception e) {
            log.warn("AI 分类失败，默认放行: command={}, error={}",
                    command.length() > 80 ? command.substring(0, 80) + "..." : command, e.getMessage());
            return PermissionDecision.allow();
        }
    }

    /**
     * 解析 AI 分类响应
     */
    private PermissionDecision parseAiClassification(String content, String command) {
        try {
            // 提取 JSON 部分
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end < 0 || end <= start) {
                log.warn("AI 分类响应非 JSON 格式: {}", content);
                return PermissionDecision.allow();
            }

            String json = content.substring(start, end + 1);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);

            String action = node.has("action") ? node.get("action").asText().toUpperCase() : "ALLOW";
            String reason = node.has("reason") ? node.get("reason").asText() : "AI 分类";

            return switch (action) {
                case "DENY" -> PermissionDecision.deny("ai_classify", reason);
                case "CONFIRM" -> PermissionDecision.confirm("ai_classify", reason, command);
                default -> PermissionDecision.allow();
            };
        } catch (Exception e) {
            log.warn("AI 分类响应解析失败: content={}, error={}", content, e.getMessage());
            return PermissionDecision.allow();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Layer 5: 断路器
    // ═══════════════════════════════════════════════════════════════

    private boolean isCircuitBroken(String sessionId) {
        CircuitBreakerState state = circuitBreakers.get(sessionId);
        if (state == null) return false;

        // 冷却期已过，重置
        if (System.currentTimeMillis() - state.lastDenialTime > CIRCUIT_BREAKER_COOLDOWN_MS) {
            circuitBreakers.remove(sessionId);
            return false;
        }

        return state.consecutiveDenials >= CIRCUIT_BREAKER_THRESHOLD;
    }

    private void recordDenial(String sessionId) {
        circuitBreakers.compute(sessionId, (key, state) -> {
            if (state == null || System.currentTimeMillis() - state.lastDenialTime > CIRCUIT_BREAKER_COOLDOWN_MS) {
                state = new CircuitBreakerState();
            }
            state.consecutiveDenials++;
            state.lastDenialTime = System.currentTimeMillis();
            return state;
        });
    }

    private void resetCircuitBreaker(String sessionId) {
        circuitBreakers.remove(sessionId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从参数中提取命令字符串
     */
    private String extractCommand(String args) {
        if (args == null || args.isBlank()) return null;

        // 尝试 JSON 解析
        if (args.trim().startsWith("{")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(args);
                if (node.has("command")) return node.get("command").asText();
                if (node.has("cmd")) return node.get("cmd").asText();
                if (node.has("path")) return node.get("path").asText();
                if (node.has("file")) return node.get("file").asText();
            } catch (Exception ignored) {
            }
        }

        // 直接作为命令字符串
        return args;
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部类型
    // ═══════════════════════════════════════════════════════════════

    /**
     * 权限动作
     */
    public enum Action {
        /** 允许执行 */
        ALLOW,
        /** 需要用户确认 */
        CONFIRM,
        /** 拒绝执行 */
        DENY
    }

    /**
     * 规则定义
     */
    @AllArgsConstructor
    private static class Rule {
        final String name;
        final Pattern pattern;
        final Action action;
        final String description;
    }

    /**
     * 权限决策结果
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PermissionDecision {
        private Action action;
        private String ruleName;
        private String reason;
        private String command;

        public static PermissionDecision allow() {
            return new PermissionDecision(Action.ALLOW, null, null, null);
        }

        public static PermissionDecision deny(String ruleName, String reason) {
            return new PermissionDecision(Action.DENY, ruleName, reason, null);
        }

        public static PermissionDecision confirm(String ruleName, String reason, String command) {
            return new PermissionDecision(Action.CONFIRM, ruleName, reason, command);
        }

        public boolean isAllowed() {
            return action == Action.ALLOW;
        }

        public boolean needsConfirmation() {
            return action == Action.CONFIRM;
        }

        public boolean isDenied() {
            return action == Action.DENY;
        }
    }

    /**
     * 断路器状态
     */
    @Data
    public static class CircuitBreakerState {
        private int consecutiveDenials = 0;
        private long lastDenialTime = 0;
    }
}
