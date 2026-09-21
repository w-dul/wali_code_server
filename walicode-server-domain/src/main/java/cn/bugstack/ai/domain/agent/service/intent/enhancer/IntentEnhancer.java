package cn.bugstack.ai.domain.agent.service.intent.enhancer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 意图增强器（对标 WaLiCode IntentEnhancer）
 * <p>
 * 核心流程：
 * 1. SignalExtractor 提取结构化信号
 * 2. 根据信号查找相关上下文（文件、命令历史、错误库）
 * 3. [P2-5] 信号为空时 fallback → 项目文件关键词搜索
 * 4. 构建增强上下文注入到 LLM 提示中
 * <p>
 * 与 IntentService 的关系：
 * - IntentService 做意图分类（DIAGNOSE / CONFIGURE / DEPLOY ...）
 * - IntentEnhancer 做上下文增强（找到相关文件、命令、错误信息）
 * - 两者协同：分类决定路由，增强提供上下文
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class IntentEnhancer {

    @Resource
    private SignalExtractor signalExtractor;

    @Resource
    private ProjectFileSearchService projectFileSearchService;

    /** 最大上下文片段数 */
    private static final int MAX_CONTEXT_SNIPPETS = 8;

    /** 最大上下文字符数 */
    private static final int MAX_CONTEXT_CHARS = 3000;

    /**
     * 增强用户消息（含项目文件搜索 fallback）
     * <p>
     * 流程：提取信号 → 查找上下文 → [P2-5] 信号为空时 fallback 搜索项目文件
     *
     * @param userInput       用户原始输入
     * @param sessionId       会话 ID
     * @param projectRootPath 项目根路径（用于文件搜索 fallback）
     * @return 增强结果
     */
    public EnhanceResult enhance(String userInput, String sessionId, String projectRootPath) {
        // 1. 提取信号
        SignalExtractor.ExtractedSignals signals = signalExtractor.extract(userInput);

        if (!signals.hasSignals()) {
            // [P2-5] 信号为空时，fallback 到项目文件关键词搜索
            log.debug("无信号提取，尝试项目文件搜索 fallback: session={}", sessionId);
            return searchByKeywordsFallback(userInput, sessionId, projectRootPath);
        }

        log.info("信号提取: session={}, files={}, symbols={}, errors={}, cmds={}, api={}, ops={}",
                sessionId,
                signals.getFilePaths().size(),
                signals.getSymbolNames().size(),
                signals.getErrorPatterns().size(),
                signals.getCommandHints().size(),
                signals.getApiHints().size(),
                signals.getOpsKeywords().size());

        // 2. 构建上下文片段
        List<ContextSnippet> snippets = new ArrayList<>();

        // 2a. 文件路径 → 提示 AI 关注这些文件
        for (String filePath : signals.getFilePaths()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("file")
                    .content("用户提到的文件: " + filePath)
                    .relevance(0.95)
                    .build());
        }

        // 2b. 命令提示 → 提供常用命令参考
        for (String cmd : signals.getCommandHints()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("command")
                    .content("检测到运维工具: " + cmd + "。相关常用命令: " + getCommonCommands(cmd))
                    .relevance(0.8)
                    .build());
        }

        // 2c. 错误模式 → 提供排查建议
        for (String err : signals.getErrorPatterns()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("error")
                    .content("检测到错误: " + err + "。排查建议: " + getErrorSuggestion(err))
                    .relevance(0.85)
                    .build());
        }

        // 2d. 运维关键词 → 提供监控信息
        for (String ops : signals.getOpsKeywords()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("ops")
                    .content("运维关注点: " + ops + "。建议检查: " + getOpsCheckCommand(ops))
                    .relevance(0.7)
                    .build());
        }

        // 2e. API 端点 → 提示 API 相关
        for (String api : signals.getApiHints()) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            snippets.add(ContextSnippet.builder()
                    .type("api")
                    .content("API 端点: " + api)
                    .relevance(0.75)
                    .build());
        }

        // 3. 构建增强上下文文本
        String enhancedContext = buildEnhancedContext(snippets);

        log.info("意图增强完成: session={}, snippets={}, contextLength={}",
                sessionId, snippets.size(), enhancedContext.length());

        return EnhanceResult.builder()
                .originalInput(userInput)
                .signals(signals)
                .contextSnippets(snippets)
                .enhancedContext(enhancedContext)
                .searchFallback(false)
                .build();
    }

    /**
     * 原始 enhance 方法（不含项目文件搜索），保持兼容
     */
    public EnhanceResult enhance(String userInput, String sessionId) {
        return enhance(userInput, sessionId, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  [P2-5] 项目文件关键词搜索 fallback
    // ═══════════════════════════════════════════════════════════════

    /**
     * 当信号提取为空时，用关键词在项目文件系统中搜索
     * <p>
     * 典型场景：用户说"帮我看看用户服务" → 搜索到 UserService.java
     */
    private EnhanceResult searchByKeywordsFallback(String userInput, String sessionId, String projectRootPath) {
        if (projectRootPath == null || projectRootPath.isBlank()) {
            log.debug("项目根路径为空，无法搜索文件: session={}", sessionId);
            return EnhanceResult.empty(userInput);
        }

        List<String> keywords = projectFileSearchService.extractKeywordsFromInput(userInput);
        if (keywords.isEmpty()) {
            return EnhanceResult.empty(userInput);
        }

        List<ProjectFileSearchService.FileSearchResult> searchResults =
                projectFileSearchService.searchByKeywords(projectRootPath, keywords);

        if (searchResults.isEmpty()) {
            log.debug("项目文件搜索无结果: session={}, keywords={}", sessionId, keywords);
            return EnhanceResult.empty(userInput);
        }

        // 构建搜索结果片段
        List<ContextSnippet> snippets = new ArrayList<>();
        for (ProjectFileSearchService.FileSearchResult result : searchResults) {
            if (snippets.size() >= MAX_CONTEXT_SNIPPETS) break;
            StringBuilder content = new StringBuilder();
            content.append("搜索到相关文件: ").append(result.getRelativePath());
            if (result.getPreview() != null && !result.getPreview().isBlank()) {
                content.append("\n  预览: ").append(result.getPreview());
            }
            snippets.add(ContextSnippet.builder()
                    .type("search_result")
                    .content(content.toString())
                    .relevance(result.getRelevance())
                    .build());
        }

        // 构建增强上下文
        String enhancedContext = buildEnhancedContext(snippets);

        log.info("项目文件搜索 fallback 完成: session={}, keywords={}, found={}, snippets={}",
                sessionId, keywords, searchResults.size(), snippets.size());

        return EnhanceResult.builder()
                .originalInput(userInput)
                .signals(SignalExtractor.ExtractedSignals.empty())
                .contextSnippets(snippets)
                .enhancedContext(enhancedContext)
                .searchFallback(true)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    private String buildEnhancedContext(List<ContextSnippet> snippets) {
        if (snippets.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("--- 意图增强上下文 ---\n");

        int totalChars = 0;
        for (ContextSnippet snippet : snippets) {
            String line = snippet.getContent() + "\n";
            if (totalChars + line.length() > MAX_CONTEXT_CHARS) break;
            sb.append(line);
            totalChars += line.length();
        }

        sb.append("--- 增强上下文结束 ---\n");
        return sb.toString();
    }

    private String getCommonCommands(String tool) {
        return switch (tool) {
            case "nginx" -> "nginx -t (测试配置), systemctl status nginx, nginx -s reload";
            case "redis" -> "redis-cli ping, redis-cli info, redis-cli monitor";
            case "mysql" -> "mysql -u root -p, SHOW PROCESSLIST, SHOW STATUS";
            case "docker" -> "docker ps, docker logs <id>, docker stats, docker inspect <id>";
            case "systemctl" -> "systemctl status <svc>, systemctl restart <svc>, journalctl -u <svc>";
            case "git" -> "git status, git log --oneline -10, git diff, git stash list";
            case "mvn" -> "mvn compile, mvn test, mvn package -DskipTests, mvn clean";
            case "npm" -> "npm run dev, npm run build, npm test, npm list";
            default -> tool + " --help";
        };
    }

    private String getErrorSuggestion(String error) {
        String lower = error.toLowerCase();
        if (lower.contains("nullpointer") || lower.contains("null")) {
            return "检查对象是否初始化，注意 Optional 链式调用";
        }
        if (lower.contains("typeerror") || lower.contains("type")) {
            return "检查类型匹配，注意隐式类型转换";
        }
        if (lower.contains("connection") || lower.contains("timeout") || lower.contains("refused")) {
            return "检查网络连通性、防火墙规则、服务是否运行";
        }
        if (lower.contains("permission") || lower.contains("denied")) {
            return "检查文件权限、用户组、sudo 配置";
        }
        if (lower.contains("oom") || lower.contains("outofmemory")) {
            return "检查 JVM 堆内存、容器内存限制、系统内存使用";
        }
        return "查看完整错误日志，搜索错误关键词";
    }

    private String getOpsCheckCommand(String keyword) {
        return switch (keyword) {
            case "cpu" -> "top -bn1 | head -20, mpstat 1 3";
            case "内存", "memory" -> "free -h, cat /proc/meminfo";
            case "磁盘", "disk" -> "df -h, du -sh /*";
            case "网络", "network" -> "netstat -tlnp, ss -tlnp";
            case "端口", "port" -> "lsof -i :<port>, netstat -tlnp | grep <port>";
            case "进程", "process" -> "ps aux | grep <name>, top";
            case "负载", "load" -> "uptime, cat /proc/loadavg";
            case "连接数", "connections" -> "netstat -an | wc -l, ss -s";
            case "oom" -> "dmesg | grep -i oom, journalctl -k | grep -i oom";
            default -> "查看相关日志和监控";
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  数据结构
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EnhanceResult {
        private String originalInput;
        private SignalExtractor.ExtractedSignals signals;
        private List<ContextSnippet> contextSnippets;
        private String enhancedContext;
        /** 是否使用了项目文件搜索 fallback */
        private boolean searchFallback;

        public static EnhanceResult empty(String originalInput) {
            return EnhanceResult.builder()
                    .originalInput(originalInput)
                    .signals(SignalExtractor.ExtractedSignals.empty())
                    .contextSnippets(List.of())
                    .enhancedContext("")
                    .searchFallback(false)
                    .build();
        }

        public boolean hasEnhancement() {
            return enhancedContext != null && !enhancedContext.isBlank();
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ContextSnippet {
        private String type;
        private String content;
        private double relevance;
    }
}
