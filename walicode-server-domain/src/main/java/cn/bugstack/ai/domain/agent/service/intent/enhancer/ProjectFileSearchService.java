package cn.bugstack.ai.domain.agent.service.intent.enhancer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 项目文件搜索服务
 * <p>
 * 对标 Android P2-5: searchByKeywords fallback
 * <p>
 * 当 SignalExtractor 的信号提取为空（无文件路径、符号名、命令提示等）时，
 * 使用用户输入中的关键词在项目文件系统中搜索匹配文件，作为意图增强的补充。
 * <p>
 * 搜索策略：
 * 1. 从用户输入中提取关键词（分词+驼峰拆分）
 * 2. 在项目根目录下递归扫描文件名
 * 3. 按匹配度排序（完全匹配 > 部分匹配 > 扩展名匹配）
 * 4. 限制返回数量，避免上下文过长
 * <p>
 * 典型场景：
 * - 用户说"帮我看看用户服务" → 搜索到 UserService.java
 * - 用户说"配置文件" → 搜索到 application.yml
 * - 用户说"SSH 相关代码" → 搜索到 SshExecute*.java
 *
 * @author 教学版-WaLiCode
 * 2026/6/26
 */
@Slf4j
@Service
public class ProjectFileSearchService {

    /** 最大返回文件数 */
    private static final int MAX_RESULTS = 10;

    /** 最大搜索深度 */
    private static final int MAX_DEPTH = 10;

    /** 最大单文件内容预览长度 */
    private static final int MAX_PREVIEW_LENGTH = 200;

    /** 排除的目录 */
    private static final List<String> EXCLUDED_DIRS = List.of(
            "node_modules", ".git", "target", "build", "dist", "__pycache__",
            ".idea", ".vscode", "vendor", ".gradle", ".mvn", ".next"
    );

    /** macOS 系统保护目录（需要额外排除） */
    private static final List<String> PROTECTED_MACOS_PATHS = List.of(
            "Library/Application Support/CallHistoryTransactions",
            "Library/Application Support/CallHistoryDB",
            "Library/Messages",
            "Library/Mail",
            "Library/Safari",
            "Library/Keychains",
            "Library/Containers"
    );

    /** 排除的文件扩展名 */
    private static final List<String> EXCLUDED_EXTENSIONS = List.of(
            ".class", ".jar", ".war", ".log", ".tmp", ".bak", ".swp", ".DS_Store",
            ".png", ".jpg", ".gif", ".svg", ".ico", ".woff", ".woff2", ".ttf",
            ".photoslibrary"  // macOS Photos Library 包文件，访问会触发权限异常
    );

    /**
     * 在项目根目录中搜索与关键词匹配的文件
     *
     * @param projectRootPath 项目根路径
     * @param keywords        搜索关键词列表
     * @return 匹配的文件列表（按相关性排序）
     */
    public List<FileSearchResult> searchByKeywords(String projectRootPath, List<String> keywords) {
        if (projectRootPath == null || projectRootPath.isBlank() || keywords == null || keywords.isEmpty()) {
            return Collections.emptyList();
        }

        Path rootPath = Paths.get(projectRootPath);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            log.warn("项目根路径无效: {}", projectRootPath);
            return Collections.emptyList();
        }

        log.info("项目文件搜索: root={}, keywords={}", projectRootPath, keywords);

        List<FileSearchResult> results = new ArrayList<>();

        // 使用自定义 FileVisitor 替代 Files.walk()，在 preVisitDirectory 阶段就跳过排除目录
        // 这样可以避免 macOS SIP 等权限保护目录在 walk 内部打开时触发异常
        List<Path> allFiles = new ArrayList<>();
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 在进入目录前就判断是否排除，避免触发权限异常
                    if (isExcluded(dir)) {
                        log.debug("跳过排除目录: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // 检查搜索深度
                    int depth = rootPath.relativize(dir).getNameCount();
                    if (depth > MAX_DEPTH) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!isExcluded(file) && attrs.isRegularFile()) {
                        allFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 忽略无权限访问的文件/目录
                    log.debug("跳过无权限路径: {}, 原因: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("项目文件搜索失败: root={}", projectRootPath, e);
            return Collections.emptyList();
        }

        for (Path file : allFiles) {
            try {
                String relativePath = rootPath.relativize(file).toString();
                String fileName = file.getFileName().toString();

                double relevance = calculateRelevance(fileName, relativePath, keywords);
                if (relevance > 0) {
                    String preview = readFilePreview(file);
                    results.add(FileSearchResult.builder()
                            .relativePath(relativePath)
                            .fileName(fileName)
                            .extension(getExtension(fileName))
                            .relevance(relevance)
                            .preview(preview)
                            .build());
                }
            } catch (Exception e) {
                // 忽略单个文件处理异常（如权限问题）
                log.debug("跳过文件处理: {}, 原因: {}", file, e.getMessage());
            }
        }

        // 按相关性排序，截断到 MAX_RESULTS
        results.sort(Comparator.comparingDouble(FileSearchResult::getRelevance).reversed());
        if (results.size() > MAX_RESULTS) {
            results = results.subList(0, MAX_RESULTS);
        }

        log.info("项目文件搜索完成: found={}, returned={}", results.size(), Math.min(results.size(), MAX_RESULTS));
        return results;
    }

    /**
     * 从用户输入中提取关键词
     * <p>
     * 混合分词策略：空格/标点拆分 + 驼峰拆分 + 英文小写化
     */
    public List<String> extractKeywordsFromInput(String userInput) {
        if (userInput == null || userInput.isBlank()) return Collections.emptyList();

        // 按空格、标点拆分
        String[] tokens = userInput.split("[\\s,，。.!！?？;；:：/\\\\|()\\[\\]{}\"']+");
        List<String> keywords = new ArrayList<>();

        for (String token : tokens) {
            if (token.length() < 2) continue;
            String lower = token.toLowerCase();
            keywords.add(lower);

            // 驼峰拆分
            if (token.matches("[A-Z][a-z]+[A-Z][a-z]+.*")) {
                String[] parts = token.split("(?=[A-Z])");
                for (String part : parts) {
                    if (part.length() >= 2) keywords.add(part.toLowerCase());
                }
            }

            // 路径拆分
            if (token.contains("/")) {
                String[] pathParts = token.split("/");
                for (String part : pathParts) {
                    if (part.length() >= 2) keywords.add(part.toLowerCase());
                }
            }
        }

        // 去重
        return keywords.stream().distinct().collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    //  匹配度计算
    // ═══════════════════════════════════════════════════════════════

    private double calculateRelevance(String fileName, String relativePath, List<String> keywords) {
        double score = 0;
        String lowerFileName = fileName.toLowerCase();
        String lowerPath = relativePath.toLowerCase();

        for (String keyword : keywords) {
            String lowerKw = keyword.toLowerCase();

            // 完全匹配文件名（不含扩展名）
            String nameNoExt = lowerFileName.replaceFirst("\\.[^.]+$", "");
            if (nameNoExt.equals(lowerKw)) {
                score += 3.0;  // 最高权重
            }

            // 文件名包含关键词
            if (lowerFileName.contains(lowerKw)) {
                score += 2.0;
            }

            // 路径包含关键词（目录名匹配）
            if (lowerPath.contains(lowerKw)) {
                score += 1.0;
            }

            // 关键词出现在路径的目录段中
            String[] pathParts = lowerPath.split("/");
            for (String part : pathParts) {
                if (part.equals(lowerKw)) {
                    score += 1.5;  // 目录名精确匹配权重高于一般包含
                }
            }
        }

        // 扩展名加分：代码文件权重更高
        String ext = getExtension(fileName);
        if (List.of(".java", ".ts", ".tsx", ".js", ".jsx", ".py", ".go", ".rs", ".vue", ".yml", ".yaml", ".xml", ".sql").contains(ext)) {
            score += 0.5;
        }

        return score;
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    private boolean isExcluded(Path path) {
        String pathStr = path.toString();

        // 检查排除的目录
        for (String dir : EXCLUDED_DIRS) {
            if (pathStr.contains("/" + dir + "/") || pathStr.contains("\\" + dir + "\\")) {
                return true;
            }
        }

        // 检查 macOS 系统保护目录
        for (String protectedPath : PROTECTED_MACOS_PATHS) {
            if (pathStr.contains(protectedPath)) {
                return true;
            }
        }

        // 检查排除的扩展名
        String fileName = path.getFileName().toString();
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (fileName.endsWith(ext)) return true;
        }

        // 隐藏文件
        if (fileName.startsWith(".")) return true;

        return false;
    }

    private String readFilePreview(Path file) {
        try {
            String content = Files.readString(file);
            if (content.length() > MAX_PREVIEW_LENGTH) {
                // 取前几行 + 总行数
                int lines = content.split("\n").length;
                String preview = content.substring(0, MAX_PREVIEW_LENGTH);
                return preview + "... (" + lines + " lines total)";
            }
            return content;
        } catch (IOException e) {
            return "";
        }
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex) : "";
    }

    // ═══════════════════════════════════════════════════════════════
    //  数据结构
    // ═══════════════════════════════════════════════════════════════

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class FileSearchResult {
        /** 相对路径 */
        private String relativePath;
        /** 文件名 */
        private String fileName;
        /** 扩展名 */
        private String extension;
        /** 相关性分数 */
        private double relevance;
        /** 文件内容预览 */
        private String preview;
    }
}
