package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.ssh.model.entity.SshFileContentEntity;
import cn.bugstack.ai.domain.ssh.model.entity.SshFileTreeEntity;
import cn.bugstack.ai.domain.ssh.service.ISshFileDomainService;
import cn.bugstack.ai.domain.ssh.service.ISshTerminalService;
import cn.bugstack.ai.domain.ssh.model.entity.TerminalSessionEntity;
import com.google.adk.tools.Annotations.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AI Coding 工具集（ADK FunctionTool）
 *
 * <p>为智能体提供远程服务器上的代码读写能力：
 * <ul>
 *   <li>readFile - 读取远程文件内容（支持分片）</li>
 *   <li>writeFile - 写入/覆盖远程文件</li>
 *   <li>listFiles - 列出目录结构</li>
 *   <li>searchInFiles - 在远程目录中搜索关键词（grep）</li>
 * </ul>
 *
 * <p>通过 terminalSessionId → connectionId 映射，复用已有 SSH 文件服务。
 *
 * @author walissh dev
 */
@Slf4j
@Service
public class CodeEditAdkTool {

    @Resource
    private ISshFileDomainService sshFileDomainService;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource(name = "sseToolProgressNotifier")
    private ToolProgressNotifier progressNotifier;

    /** 文件读取最大字符数（2M，覆盖绝大多数源文件） */
    private static final int MAX_READ_LENGTH = 2_000_000;

    /** 目录列表最大条目数 */
    private static final int MAX_DIR_ENTRIES = 200;

    // ═══════════════════════════════════════════════════════════════
    //  本地文件操作（不依赖 SSH，直接通过 Java IO 操作）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 读取本地文件内容
     *
     * @param filePath 文件绝对路径
     * @return 文件内容
     */
    public Map<String, Object> readLocalFile(
            @Schema(name = "filePath", description = "要读取的本地文件绝对路径，如 /Users/user/project/app.js 或 C:\\Users\\user\\project\\app.js")
            String filePath) {

        try {
            Path path = Paths.get(filePath);
            log.info("[CodeEdit-Local] 读取本地文件: path={}", filePath);
            notifyProgress("readLocalFile", filePath);

            if (!Files.exists(path)) {
                return Map.of("success", false, "error", "文件不存在: " + filePath, "path", filePath);
            }

            if (Files.isDirectory(path)) {
                return Map.of("success", false, "error", "路径是目录，不是文件: " + filePath, "path", filePath);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            boolean truncated = false;
            long size = Files.size(path);

            if (content.length() > MAX_READ_LENGTH) {
                content = content.substring(0, MAX_READ_LENGTH);
                truncated = true;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", filePath);
            result.put("name", path.getFileName().toString());
            result.put("size", size);
            result.put("truncated", truncated);
            result.put("content", content);

            if (truncated) {
                result.put("note", "文件内容超过 " + MAX_READ_LENGTH + " 字符，已截断。");
            }

            log.info("[CodeEdit-Local] 读取本地文件成功: path={}, size={}", filePath, size);
            notifyProgressEnd("readLocalFile", "读取成功，" + size + " 字节", true);
            return result;

        } catch (Exception e) {
            log.error("[CodeEdit-Local] 读取本地文件失败: path={}", filePath, e);
            return Map.of("success", false, "error", "读取本地文件失败: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * 写入/覆盖本地文件
     *
     * @param filePath 文件绝对路径
     * @param content  文件内容
     * @return 写入结果
     */
    public Map<String, Object> writeLocalFile(
            @Schema(name = "filePath", description = "要写入的本地文件绝对路径，如 /Users/user/project/app.js")
            String filePath,
            @Schema(name = "content", description = "文件完整内容（会覆盖原有内容）")
            String content) {

        try {
            Path path = Paths.get(filePath);
            log.info("[CodeEdit-Local] 写入本地文件: path={}, contentLength={}",
                    filePath, content != null ? content.length() : 0);
            notifyProgress("writeLocalFile", filePath);

            // 如果父目录不存在，自动创建
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("[CodeEdit-Local] 自动创建目录: {}", parent);
            }

            Files.writeString(path, content != null ? content : "", StandardCharsets.UTF_8);
            long bytesWritten = content != null ? content.length() : 0;

            log.info("[CodeEdit-Local] 写入本地文件成功: path={}, bytes={}", filePath, bytesWritten);
            notifyProgressEnd("writeLocalFile", "写入成功，" + bytesWritten + " 字节", true);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", filePath);
            result.put("bytesWritten", bytesWritten);
            result.put("message", "文件已成功写入: " + filePath);
            return result;

        } catch (Exception e) {
            log.error("[CodeEdit-Local] 写入本地文件失败: path={}", filePath, e);
            return Map.of("success", false, "error", "写入本地文件失败: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * 列出本地目录结构
     *
     * @param dirPath 目录路径
     * @return 目录列表
     */
    public Map<String, Object> listLocalFiles(
            @Schema(name = "dirPath", description = "要列出的本地目录路径，如 /Users/user/project 或 .")
            String dirPath) {

        try {
            Path path = Paths.get(dirPath);
            log.info("[CodeEdit-Local] 列出本地目录: path={}", dirPath);
            notifyProgress("listLocalFiles", dirPath);

            if (!Files.exists(path)) {
                return Map.of("success", false, "error", "目录不存在: " + dirPath, "path", dirPath);
            }

            if (!Files.isDirectory(path)) {
                return Map.of("success", false, "error", "路径不是目录: " + dirPath, "path", dirPath);
            }

            List<Map<String, Object>> items = new ArrayList<>();
            int count = 0;
            try (Stream<Path> stream = Files.list(path)) {
                List<Path> sorted = stream.sorted().collect(Collectors.toList());
                for (Path item : sorted) {
                    if (count >= MAX_DIR_ENTRIES) break;
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("name", item.getFileName().toString());
                    itemMap.put("path", item.toAbsolutePath().toString());
                    itemMap.put("directory", Files.isDirectory(item));
                    try {
                        itemMap.put("size", Files.size(item));
                    } catch (IOException ignored) {
                        itemMap.put("size", 0);
                    }
                    items.add(itemMap);
                    count++;
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", path.toAbsolutePath().toString());
            result.put("parentPath", path.getParent() != null ? path.getParent().toAbsolutePath().toString() : null);
            result.put("items", items);
            result.put("total", items.size());
            result.put("truncated", count >= MAX_DIR_ENTRIES);

            log.info("[CodeEdit-Local] 列出本地目录成功: path={}, items={}", dirPath, items.size());
            notifyProgressEnd("listLocalFiles", "列出 " + items.size() + " 个条目", true);
            return result;

        } catch (Exception e) {
            log.error("[CodeEdit-Local] 列出本地目录失败: path={}", dirPath, e);
            return Map.of("success", false, "error", "列出本地目录失败: " + e.getMessage(), "path", dirPath);
        }
    }

    /**
     * 在本地文件中搜索关键词
     *
     * @param directory 搜索目录
     * @param keyword   搜索关键词
     * @return 搜索结果
     */
    public Map<String, Object> searchInLocalFiles(
            @Schema(name = "directory", description = "搜索的起始目录，如 /Users/user/project/src")
            String directory,
            @Schema(name = "keyword", description = "搜索关键词或正则表达式")
            String keyword) {

        try {
            Path searchPath = Paths.get(directory);
            log.info("[CodeEdit-Local] 搜索本地文件: dir={}, keyword={}", directory, keyword);
            notifyProgress("searchInLocalFiles", directory + " (keyword: " + keyword + ")");

            if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
                return Map.of("success", false, "error", "目录不存在或不是目录: " + directory);
            }

            List<Map<String, Object>> matches = new ArrayList<>();
            String[] extensions = {".java", ".py", ".js", ".ts", ".tsx", ".go", ".rs", ".c", ".cpp", ".h",
                    ".yml", ".yaml", ".json", ".xml", ".sh", ".conf", ".md", ".txt", ".sql", ".html", ".css", ".vue"};
            Set<String> extSet = new HashSet<>(Arrays.asList(extensions));

            Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= 100) return FileVisitResult.TERMINATE;

                    String fileName = file.getFileName().toString();
                    boolean hasValidExt = false;
                    for (String ext : extSet) {
                        if (fileName.endsWith(ext)) {
                            hasValidExt = true;
                            break;
                        }
                    }
                    if (!hasValidExt) return FileVisitResult.CONTINUE;

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).contains(keyword)) {
                                Map<String, Object> match = new HashMap<>();
                                match.put("file", file.toAbsolutePath().toString());
                                match.put("line", String.valueOf(i + 1));
                                match.put("content", lines.get(i).trim());
                                matches.add(match);
                                if (matches.size() >= 100) return FileVisitResult.TERMINATE;
                            }
                        }
                    } catch (IOException ignored) {
                        // 跳过无法读取的文件
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    // 跳过隐藏目录和常见忽略目录
                    if (name.startsWith(".") || name.equals("node_modules") || name.equals("target")
                            || name.equals("build") || name.equals("dist") || name.equals("__pycache__")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("directory", directory);
            result.put("keyword", keyword);
            result.put("matches", matches);
            result.put("total", matches.size());

            log.info("[CodeEdit-Local] 搜索完成: keyword={}, matches={}", keyword, matches.size());
            notifyProgressEnd("searchInLocalFiles", "搜索完成，" + matches.size() + " 个匹配", true);
            return result;

        } catch (Exception e) {
            log.error("[CodeEdit-Local] 搜索本地文件失败: dir={}, keyword={}", directory, keyword, e);
            return Map.of("success", false, "error", "搜索本地文件失败: " + e.getMessage());
        }
    }

    /**
     * 创建本地新文件
     *
     * @param filePath 文件路径
     * @return 创建结果
     */
    public Map<String, Object> createLocalFile(
            @Schema(name = "filePath", description = "要创建的本地文件路径，如 /Users/user/project/new-app.js")
            String filePath) {

        try {
            Path path = Paths.get(filePath);
            log.info("[CodeEdit-Local] 创建本地文件: path={}", filePath);
            notifyProgress("createLocalFile", filePath);

            if (Files.exists(path)) {
                return Map.of("success", false, "error", "文件已存在: " + filePath, "path", filePath);
            }

            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.createFile(path);
            log.info("[CodeEdit-Local] 创建本地文件成功: path={}", filePath);
            notifyProgressEnd("createLocalFile", "文件已创建", true);

            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "文件已创建: " + filePath
            );

        } catch (Exception e) {
            log.error("[CodeEdit-Local] 创建本地文件失败: path={}", filePath, e);
            return Map.of("success", false, "error", "创建本地文件失败: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * 删除本地文件
     *
     * @param filePath 文件路径
     * @return 删除结果
     */
    public Map<String, Object> deleteLocalFile(
            @Schema(name = "filePath", description = "要删除的本地文件路径")
            String filePath) {

        try {
            Path path = Paths.get(filePath);
            log.info("[CodeEdit-Local] 删除本地文件: path={}", filePath);
            notifyProgress("deleteLocalFile", filePath);

            if (!Files.exists(path)) {
                return Map.of("success", false, "error", "文件不存在: " + filePath, "path", filePath);
            }

            Files.delete(path);
            log.info("[CodeEdit-Local] 删除本地文件成功: path={}", filePath);
            notifyProgressEnd("deleteLocalFile", "文件已删除", true);

            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "文件已删除: " + filePath
            );

        } catch (Exception e) {
            log.error("[CodeEdit-Local] 删除本地文件失败: path={}", filePath, e);
            return Map.of("success", false, "error", "删除本地文件失败: " + e.getMessage(), "path", filePath);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TerminalSession → ConnectionId 映射（远程 SSH 文件操作）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 通过 terminalSessionId 获取 connectionId
     */
    private String getConnectionId(String terminalSessionId) {
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return null;
        }
        TerminalSessionEntity entity = sshTerminalService.getTerminalSession(terminalSessionId);
        if (entity == null || !entity.isActive()) {
            log.warn("[CodeEdit] 终端会话不存在或已关闭: {}", terminalSessionId);
            return null;
        }
        return entity.getConnectionId();
    }

    /**
     * 从 ThreadLocal 获取终端会话 ID（与 SshExecuteAdkTool 共享同一 ThreadLocal）
     * ThreadLocal 为空时 fallback 到 sessionMapping
     */
    private String resolveTerminalSessionId() {
        // 复用 SshExecuteAdkTool 的 ThreadLocal
        String terminalSessionId = SshExecuteAdkTool.getCurrentTerminalSession();
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            log.warn("[CodeEdit] ThreadLocal 未绑定终端会话，尝试 sessionMapping");
        }
        return terminalSessionId;
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 读取远程文件内容
     *
     * @param filePath 文件绝对路径
     * @return 文件内容
     */
    public Map<String, Object> readFile(
            @Schema(name = "filePath", description = "要读取的文件绝对路径，如 /etc/nginx/nginx.conf 或 /home/user/app.js")
            String filePath) {

        String terminalSessionId = resolveTerminalSessionId();
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "未绑定 SSH 终端会话，无法读取文件");
        }

        try {
            log.info("[CodeEdit] 读取文件: connectionId={}, path={}", connectionId, filePath);

            SshFileContentEntity entity = sshFileDomainService.content(connectionId, filePath);

            String content = entity.getContent();
            boolean truncated = false;

            if (content != null && content.length() > MAX_READ_LENGTH) {
                content = content.substring(0, MAX_READ_LENGTH);
                truncated = true;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", entity.getPath());
            result.put("name", entity.getName());
            result.put("size", entity.getSize());
            result.put("binary", entity.isBinary());
            result.put("charset", entity.getCharset());
            result.put("truncated", truncated || entity.isTruncated());
            result.put("offset", entity.getOffset());
            result.put("content", content);

            if (truncated) {
                result.put("note", "文件内容超过 " + MAX_READ_LENGTH + " 字符，已截断。如需读取后续内容，请使用 offset 参数。");
            }

            log.info("[CodeEdit] 读取文件成功: path={}, size={}, truncated={}",
                    filePath, entity.getSize(), truncated);

            return result;

        } catch (Exception e) {
            log.error("[CodeEdit] 读取文件失败: path={}", filePath, e);
            return Map.of("success", false, "error", "读取文件失败: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * 写入/覆盖远程文件
     *
     * @param filePath 文件绝对路径
     * @param content  文件内容
     * @return 写入结果
     */
    public Map<String, Object> writeFile(
            @Schema(name = "filePath", description = "要写入的文件绝对路径，如 /home/user/app.js")
            String filePath,
            @Schema(name = "content", description = "文件完整内容（会覆盖原有内容）")
            String content) {

        String terminalSessionId = resolveTerminalSessionId();
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "未绑定 SSH 终端会话，无法写入文件");
        }

        try {
            log.info("[CodeEdit] 写入文件: connectionId={}, path={}, contentLength={}",
                    connectionId, filePath, content != null ? content.length() : 0);

            // 如果文件不存在，先创建
            try {
                sshFileDomainService.saveFile(connectionId, filePath, content != null ? content : "", false);
            } catch (Exception saveEx) {
                // 可能文件不存在或权限不足，尝试 sudo
                log.warn("[CodeEdit] 普通写入失败，尝试 sudo: {}", saveEx.getMessage());
                sshFileDomainService.saveFile(connectionId, filePath, content != null ? content : "", true);
            }

            log.info("[CodeEdit] 写入文件成功: path={}, bytes={}", filePath, content != null ? content.length() : 0);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", filePath);
            result.put("bytesWritten", content != null ? content.length() : 0);
            result.put("message", "文件已成功写入: " + filePath);

            return result;

        } catch (Exception e) {
            log.error("[CodeEdit] 写入文件失败: path={}", filePath, e);
            return Map.of("success", false, "error", "写入文件失败: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * 列出目录结构
     *
     * @param dirPath 目录路径
     * @return 目录列表
     */
    public Map<String, Object> listFiles(
            @Schema(name = "dirPath", description = "要列出的目录路径，如 /home/user/project 或 /etc/nginx")
            String dirPath) {

        String terminalSessionId = resolveTerminalSessionId();
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "未绑定 SSH 终端会话，无法列出目录");
        }

        try {
            log.info("[CodeEdit] 列出目录: connectionId={}, path={}", connectionId, dirPath);

            SshFileTreeEntity tree = sshFileDomainService.tree(connectionId, dirPath);

            List<Map<String, Object>> items = new ArrayList<>();
            int count = 0;
            for (var item : tree.getItems()) {
                if (count >= MAX_DIR_ENTRIES) {
                    break;
                }
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("name", item.getName());
                itemMap.put("path", item.getPath());
                itemMap.put("directory", item.isDirectory());
                itemMap.put("size", item.getSize());
                items.add(itemMap);
                count++;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("path", tree.getCurrentPath());
            result.put("parentPath", tree.getParentPath());
            result.put("items", items);
            result.put("total", tree.getItems().size());
            result.put("truncated", tree.getItems().size() > MAX_DIR_ENTRIES);

            if (tree.getItems().size() > MAX_DIR_ENTRIES) {
                result.put("note", "目录条目超过 " + MAX_DIR_ENTRIES + "，已截断");
            }

            log.info("[CodeEdit] 列出目录成功: path={}, items={}", dirPath, items.size());

            return result;

        } catch (Exception e) {
            log.error("[CodeEdit] 列出目录失败: path={}", dirPath, e);
            return Map.of("success", false, "error", "列出目录失败: " + e.getMessage(), "path", dirPath);
        }
    }

    /**
     * 在远程文件中搜索关键词
     *
     * @param directory 搜索目录
     * @param keyword   搜索关键词
     * @return 搜索结果
     */
    public Map<String, Object> searchInFiles(
            @Schema(name = "directory", description = "搜索的起始目录，如 /home/user/project/src")
            String directory,
            @Schema(name = "keyword", description = "搜索关键词或正则表达式")
            String keyword) {

        String terminalSessionId = resolveTerminalSessionId();

        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return Map.of("success", false, "error", "未绑定 SSH 终端会话");
        }

        if (!sshTerminalService.sessionExists(terminalSessionId)) {
            return Map.of("success", false, "error", "SSH 终端会话不存在");
        }

        try {
            log.info("[CodeEdit] 搜索文件: dir={}, keyword={}", directory, keyword);

            // 使用 grep -rn 递归搜索
            // 转义 keyword 中的单引号
            String escapedKeyword = keyword.replace("'", "'\\''");
            String command = String.format("grep -rn --include='*.java' --include='*.py' --include='*.js' --include='*.ts' --include='*.tsx' --include='*.go' --include='*.rs' --include='*.c' --include='*.cpp' --include='*.h' --include='*.yml' --include='*.yaml' --include='*.json' --include='*.xml' --include='*.sh' --include='*.conf' --include='*.md' --include='*.txt' --include='*.sql' '%s' %s 2>/dev/null | head -100", escapedKeyword, directory);

            String output = sshTerminalService.executeCommand(terminalSessionId, command);

            // 解析 grep 输出
            List<Map<String, Object>> matches = new ArrayList<>();
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.contains("Exit code:") || line.startsWith("[Exit")) {
                    continue;
                }
                // grep -rn 格式: file:line:content
                int firstColon = line.indexOf(':');
                int secondColon = line.indexOf(':', firstColon + 1);
                if (firstColon > 0 && secondColon > firstColon) {
                    Map<String, Object> match = new HashMap<>();
                    match.put("file", line.substring(0, firstColon));
                    match.put("line", line.substring(firstColon + 1, secondColon));
                    match.put("content", line.substring(secondColon + 1));
                    matches.add(match);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("directory", directory);
            result.put("keyword", keyword);
            result.put("matches", matches);
            result.put("total", matches.size());
            result.put("rawOutput", output.length() > 5000 ? output.substring(0, 5000) + "..." : output);

            log.info("[CodeEdit] 搜索完成: keyword={}, matches={}", keyword, matches.size());

            return result;

        } catch (Exception e) {
            log.error("[CodeEdit] 搜索文件失败: dir={}, keyword={}", directory, keyword, e);
            return Map.of("success", false, "error", "搜索文件失败: " + e.getMessage());
        }
    }

    /**
     * 在远程服务器上创建新文件
     *
     * @param filePath 文件路径
     * @return 创建结果
     */
    public Map<String, Object> createFile(
            @Schema(name = "filePath", description = "要创建的文件路径，如 /home/user/new-app.js")
            String filePath) {

        String terminalSessionId = resolveTerminalSessionId();
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "未绑定 SSH 终端会话");
        }

        try {
            log.info("[CodeEdit] 创建文件: connectionId={}, path={}", connectionId, filePath);

            try {
                sshFileDomainService.createFile(connectionId, filePath, false);
            } catch (Exception e) {
                log.warn("[CodeEdit] 普通创建失败，尝试 sudo: {}", e.getMessage());
                sshFileDomainService.createFile(connectionId, filePath, true);
            }

            log.info("[CodeEdit] 创建文件成功: path={}", filePath);

            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "文件已创建: " + filePath
            );

        } catch (Exception e) {
            log.error("[CodeEdit] 创建文件失败: path={}", filePath, e);
            return Map.of("success", false, "error", "创建文件失败: " + e.getMessage(), "path", filePath);
        }
    }

    /**
     * 删除远程文件
     *
     * @param filePath 文件路径
     * @return 删除结果
     */
    public Map<String, Object> deleteFile(
            @Schema(name = "filePath", description = "要删除的文件路径")
            String filePath) {

        String terminalSessionId = resolveTerminalSessionId();
        String connectionId = getConnectionId(terminalSessionId);

        if (connectionId == null) {
            return Map.of("success", false, "error", "未绑定 SSH 终端会话");
        }

        try {
            log.info("[CodeEdit] 删除文件: connectionId={}, path={}", connectionId, filePath);

            try {
                sshFileDomainService.delete(connectionId, filePath, false);
            } catch (Exception e) {
                log.warn("[CodeEdit] 普通删除失败，尝试 sudo: {}", e.getMessage());
                sshFileDomainService.delete(connectionId, filePath, true);
            }

            log.info("[CodeEdit] 删除文件成功: path={}", filePath);

            return Map.of(
                    "success", true,
                    "path", filePath,
                    "message", "文件已删除: " + filePath
            );

        } catch (Exception e) {
            log.error("[CodeEdit] 删除文件失败: path={}", filePath, e);
            return Map.of("success", false, "error", "删除文件失败: " + e.getMessage(), "path", filePath);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  进度通知辅助方法
    // ═══════════════════════════════════════════════════════════════

    private void notifyProgress(String toolName, String args) {
        if (progressNotifier != null) {
            try {
                progressNotifier.onToolStart(toolName, args);
            } catch (Exception e) {
                log.debug("进度通知失败（不影响工具执行）", e);
            }
        }
    }

    private void notifyProgressEnd(String toolName, String summary, boolean success) {
        if (progressNotifier != null) {
            try {
                progressNotifier.onToolEnd(toolName, summary, success);
            } catch (Exception e) {
                log.debug("进度通知失败（不影响工具执行）", e);
            }
        }
    }

}
