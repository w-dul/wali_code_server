package cn.bugstack.ai.domain.agent.service.intent.enhancer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 信号提取器（对标 WaLiCode SignalExtractor.ts）
 * <p>
 * 核心思想：只提取结构化信息，不做决策
 * 提取的信号供 IntentEnhancer 查找相关上下文，注入到 LLM 提示中
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class SignalExtractor {

    // ═══════════════════════════════════════════════════════════════
    //  正则模式
    // ═══════════════════════════════════════════════════════════════

    /** 文件路径：src/xxx.ts, ./xxx.java, /path/to/file */
    private static final Pattern[] FILE_PATH_PATTERNS = {
            // 带路径分隔符的文件路径
            Pattern.compile("(?:^|[\\s'\"(])((\\.?\\.?/[a-zA-Z0-9_\\-./]+?\\.[a-zA-Z]{1,10}))"),
            // 目录/文件格式
            Pattern.compile("(?:^|[\\s'\"(])([a-zA-Z0-9_\\-]+/[a-zA-Z0-9_\\-./]+\\.[a-zA-Z]{1,10})"),
            // 反引号中的文件路径
            Pattern.compile("`([^`]*\\.[a-zA-Z]{1,10})`"),
    };

    /** 符号名：大写开头的标识符（组件名、类名） */
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b");

    /** JSX 组件标签：<ComponentName> */
    private static final Pattern JSX_PATTERN = Pattern.compile("<([A-Z][a-zA-Z0-9_]*)");

    /** 方法调用：UserService.login */
    private static final Pattern METHOD_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\.[a-zA-Z0-9_]+");

    /** 错误模式 */
    private static final Pattern[] ERROR_PATTERNS = {
            Pattern.compile("\\b([A-Z]\\w*Error|\\w*Exception|\\w*Error)\\b"),
            Pattern.compile("\\b(TypeError|ReferenceError|SyntaxError|RuntimeError|NullPointer)\\b"),
            Pattern.compile("(undefined|null is not|cannot read prop|is not a function)", Pattern.CASE_INSENSITIVE),
    };

    /** SSH/运维命令 */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "\\b(?:sudo\\s+)?(nginx|redis|mysql|docker|systemctl|apt|yum|pip|npm|mvn|java|python|node|git|curl|wget|ssh|scp|rsync|tar|gzip)\\b");

    /** API 端点提示 */
    private static final Pattern API_PATTERN = Pattern.compile(
            "\\b(?:GET|POST|PUT|DELETE|PATCH)\\s+(/[a-zA-Z0-9_/\\-{}]+)", Pattern.CASE_INSENSITIVE);

    /** 运维关键词 */
    private static final Pattern OPS_KEYWORD_PATTERN = Pattern.compile(
            "\\b(CPU|内存|磁盘|网络|端口|进程|负载|连接数|并发|QPS|TPS|延迟|超时|OOM|crash|panic)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 过滤掉的常见非符号词 */
    private static final Set<String> STOP_WORDS = Set.of(
            "I", "O", "If", "Do", "To", "The", "A", "An", "And", "Or", "But",
            "In", "On", "At", "By", "For", "With", "From", "Up", "Out", "Down",
            "CPU", "RAM", "GPU", "SSD", "HDD", "IP", "DNS", "URL", "URI", "SSH",
            "API", "JSON", "XML", "HTML", "CSS", "SQL", "TCP", "UDP", "HTTP", "HTTPS"
    );

    // ═══════════════════════════════════════════════════════════════
    //  公开 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 提取用户消息中的结构化信号
     */
    public ExtractedSignals extract(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return ExtractedSignals.empty();
        }

        return ExtractedSignals.builder()
                .filePaths(extractFilePaths(userInput))
                .symbolNames(extractSymbolNames(userInput))
                .errorPatterns(extractErrors(userInput))
                .commandHints(extractCommandHints(userInput))
                .apiHints(extractApiHints(userInput))
                .opsKeywords(extractOpsKeywords(userInput))
                .rawInput(userInput)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  提取方法
    // ═══════════════════════════════════════════════════════════════

    private List<String> extractFilePaths(String input) {
        Set<String> results = new HashSet<>();
        for (Pattern pattern : FILE_PATH_PATTERNS) {
            Matcher m = pattern.matcher(input);
            while (m.find()) {
                String path = m.group(1);
                if (path != null && path.length() > 2 && !path.contains(" ")) {
                    results.add(path);
                }
            }
        }
        return new ArrayList<>(results);
    }

    private List<String> extractSymbolNames(String input) {
        Set<String> results = new HashSet<>();

        // 大写开头的标识符
        Matcher m = SYMBOL_PATTERN.matcher(input);
        while (m.find()) {
            String name = m.group(1);
            if (name != null && name.length() > 1 && !STOP_WORDS.contains(name)) {
                results.add(name);
            }
        }

        // JSX 组件标签
        m = JSX_PATTERN.matcher(input);
        while (m.find()) {
            String name = m.group(1);
            if (name != null) results.add(name);
        }

        // 方法调用中的类名
        m = METHOD_PATTERN.matcher(input);
        while (m.find()) {
            String name = m.group(1);
            if (name != null && !STOP_WORDS.contains(name)) results.add(name);
        }

        return new ArrayList<>(results);
    }

    private List<String> extractErrors(String input) {
        Set<String> results = new HashSet<>();
        for (Pattern pattern : ERROR_PATTERNS) {
            Matcher m = pattern.matcher(input);
            while (m.find()) {
                String err = m.group(1);
                if (err != null) results.add(err);
            }
        }
        return new ArrayList<>(results);
    }

    private List<String> extractCommandHints(String input) {
        Set<String> results = new HashSet<>();
        Matcher m = COMMAND_PATTERN.matcher(input);
        while (m.find()) {
            String cmd = m.group(1);
            if (cmd != null) results.add(cmd.toLowerCase());
        }
        return new ArrayList<>(results);
    }

    private List<String> extractApiHints(String input) {
        Set<String> results = new HashSet<>();
        Matcher m = API_PATTERN.matcher(input);
        while (m.find()) {
            String api = m.group(1);
            if (api != null) results.add(api);
        }
        return new ArrayList<>(results);
    }

    private List<String> extractOpsKeywords(String input) {
        Set<String> results = new HashSet<>();
        Matcher m = OPS_KEYWORD_PATTERN.matcher(input);
        while (m.find()) {
            String kw = m.group(1);
            if (kw != null) results.add(kw.toLowerCase());
        }
        return new ArrayList<>(results);
    }

    // ═══════════════════════════════════════════════════════════════
    //  信号数据结构
    // ═══════════════════════════════════════════════════════════════

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ExtractedSignals {
        /** 文件路径 */
        private List<String> filePaths;
        /** 符号名（组件/类/函数名） */
        private List<String> symbolNames;
        /** 错误信息 */
        private List<String> errorPatterns;
        /** 命令提示（nginx, docker, git 等） */
        private List<String> commandHints;
        /** API 端点提示 */
        private List<String> apiHints;
        /** 运维关键词 */
        private List<String> opsKeywords;
        /** 原始输入 */
        private String rawInput;

        public static ExtractedSignals empty() {
            return ExtractedSignals.builder()
                    .filePaths(List.of())
                    .symbolNames(List.of())
                    .errorPatterns(List.of())
                    .commandHints(List.of())
                    .apiHints(List.of())
                    .opsKeywords(List.of())
                    .rawInput("")
                    .build();
        }

        public boolean hasSignals() {
            return (filePaths != null && !filePaths.isEmpty())
                    || (symbolNames != null && !symbolNames.isEmpty())
                    || (errorPatterns != null && !errorPatterns.isEmpty())
                    || (commandHints != null && !commandHints.isEmpty())
                    || (apiHints != null && !apiHints.isEmpty())
                    || (opsKeywords != null && !opsKeywords.isEmpty());
        }
    }
}
