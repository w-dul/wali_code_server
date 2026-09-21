package cn.bugstack.ai.cases.react.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 格式规范化工具（v2 重写）
 * <p>
 * AI 模型返回的 Markdown 常缺少换行符，导致标题、列表、表格粘在一行。
 * 本工具在后端统一处理，确保发送给前端的内容符合 GFM 规范。
 * <p>
 * 核心设计：
 * 1. 代码块保护：所有操作跳过代码内容
 * 2. 处理顺序：表格先于列表/标题（避免 || 被列表规则破坏）
 * 3. || 表格拆行 + 自动补全分隔行
 * 4. 幂等：多次 normalize 结果一致
 * 5. 占位符使用 \u0001 前缀，trim 时不能吞掉
 *
 * @author WaLiCode
 */
public final class MarkdownNormalizer {

    private MarkdownNormalizer() {
    }

    private static final String CB_PREFIX = "\u0001CB";
    private static final String CB_SUFFIX = "\u0001";
    private static final String IC_PREFIX = "\u0001IC";
    private static final String TB_PREFIX = "\u0001TB";

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;

        // ═══ 阶段 0：保护代码块 ═══
        List<String> codeBlocks = new ArrayList<>();
        String r = protectCodeBlocks(text, codeBlocks);

        // ═══ 阶段 1：表格处理（必须在断行修复之前） ═══
        r = processTables(r);

        // ═══ 阶段 1.5：保护表格行——避免被后续列表规则误匹配 | -- | 为列表标记 ═══
        List<String> tableLines = new ArrayList<>();
        r = protectTableLines(r, tableLines);

        // ═══ 阶段 2：断行修复 ═══
        // 2a. 标题标记 ## 后补空格
        r = r.replaceAll("(#{2,6})([^\\s#])", "$1 $2");
        r = r.replaceAll("(^|\\n)(#)([^\\s#])", "$1$2 $3");

        // 2b. 标题标记前断行+空行
        r = r.replaceAll("([^\\n\\s#])(#{1,6}\\s)", "$1\n\n$2");

        // 2d. 标题后紧跟表格标记 → 断行
        r = r.replaceAll("(#{1,6}\\s[^\\n|]+)(\\|)", "$1\n\n$2");

        // 2d2. 标题后紧跟代码块占位符 → 断行
        r = r.replaceAll("(#{1,6}\\s[^\\n\u0001]+)(\u0001CB)", "$1\n\n$2");

        // 2d3 已移除——标题含句号不应强制断行，段落分隔由 ReactMarkdown 自然处理
        // 2e 已移除——句号后不应强制分段，ReactMarkdown 依据双换行自然处理段落

        // 2f0. 有序列表标记后补空格（AI输出可能缺少空格：1.配置 → 1. 配置）
        // 排除版本号：数字.后面紧跟数字/点号时不补空格（如 3.4.3）
        r = r.replaceAll("(\\d{1,2})\\.([^\\s\\d\\n\\.])", "$1. $2");

        // 2h0. 无序列表标记后补空格（AI输出可能缺少空格：-容器化 → - 容器化）
        // 仅匹配标记后紧跟中文字符，排除 UTF-8（-8）、x64-based 等英文+数字场景
        // 排除连字符场景：字母/数字后的 - 是连字符而非列表标记（如 x64-架构）
        r = r.replaceAll("(?<![a-zA-Z0-9\\u0001])([-*+])([\\u4e00-\\u9fa5])", "$1 $2");
        // 2h0-emoji. 列表标记后紧跟 emoji 补空格
        r = r.replaceAll("([-*+])([\u2705\u274c\u26a0\u2764\u2b50\u2605\u2728\u2714\u2716])", "$1 $2");
        // 2h0-en. 列表标记后紧跟英文大写字母也补空格（类名/文件名通常大写开头）
        // 如 -AdminService → - AdminService
        // 排除连字符场景：字母/数字后的 - 是连字符而非列表标记（xfg-wrench、JSON-RPC、UTF-8）
        r = r.replaceAll("(?<![a-zA-Z0-9\\u0001])([-*+])([A-Z])", "$1 $2");
        // 2h0-en2. 列表标记后紧跟小写字母+中文混合内容时补空格
        // 如 -dto目录 → - dto目录，排除纯英文连字符如 self-contained
        // 排除连字符场景：字母/数字后的 - 是连字符而非列表标记（xfg-wrench框架）
        r = r.replaceAll("(?<![a-zA-Z0-9\\u0001])([-*+])([a-z]+)([\\u4e00-\\u9fa5])", "$1 $2$3");
        // 2h0-ext. 列表标记后紧跟代码块占位符（加粗/斜体/行内代码）也补空格
        // 如 -**增强配置** → 保护后 -IC0 → 补空格 - IC0 → 恢复 - **增强配置**
        r = r.replaceAll("([-*+])(\u0001IC)", "$1 $2");

        // 2h1. 中文字符与数字之间补空格（如 更新3项 → 更新 3 项）
        // 排除英文场景（x64、UTF-8 等不需要空格）
        r = r.replaceAll("([\u4e00-\u9fa5])(\\d)", "$1 $2");
        r = r.replaceAll("(?<!-)(\\d)([\u4e00-\u9fa5])", "$1 $2");

        // 2f. 有序列表前断行——排除版本号（如 3.4.3 不应被断为列表）
        r = r.replaceAll("([^\\n\\d\\s.])(1\\.\\s)", "$1\n$2");

        // 2g. 有序列表项之间断行——排除版本号和标题内数字标记
        // 排除标题内的数字标记（## 1. 配置管理 中的 1. 是标题文本，不是列表项）
        // 排除 # 和空格后的数字标记不断行
        r = r.replaceAll("([^\\n\\d.#\\s])(\\d{1,2}\\.\\s)", "$1\n$2");

        // 2h. 无序列表前断行——匹配任意非换行非空格字符后跟列表标记
        // 占位符后跟列表标记也需要断行（如 **核心能力**- 配置管理）
        // UTF-8、x64-based 安全：这些场景中 - 后没有空格，不触发 [-*+]\s
        r = r.replaceAll("([^\\n\\s])([-*+]\\s)", "$1\n$2");
        // 2i. 树形符号前断行
        r = r.replaceAll("([^\\n])(├──|└──)", "$1\n$2");

        // 2j. 代码块占位符前断行
        r = r.replaceAll("([^\\n\u0001])(\u0001CB)", "$1\n$2");

        // 2k. 代码块占位符后断行
        r = r.replaceAll("(\u0001CB\\d+\u0001)([^\\n\u0001])", "$1\n$2");

        // ═══ 阶段 2.5：对阶段 2 断行后新暴露的表格行再做一次修复 ═══
        r = fixTableBlocks(r);

        // ═══ 阶段 3：空行修复 ═══
        r = ensureBlankLines(r);

        // ═══ 阶段 4：清理 ═══
        r = r.replaceAll("\\n{3,}", "\n\n");
        r = r.replaceAll("[ \\t]+\\n", "\n");
        r = r.replaceAll("^\\s+", "").replaceAll("\\s+$", "");

        // ═══ 阶段 5：恢复表格行（必须在恢复代码块之前，因为表格行内可能含 IC/CB 占位符） ═══
        r = restoreTableLines(r, tableLines);

        // ═══ 阶段 5.5：恢复代码块和行内元素 ═══
        r = restoreCodeBlocks(r, codeBlocks);

        // ═══ 阶段 6：对 Markdown 展示块内容二次规范化 ═══
        // AI 模型常把 Markdown 内容包裹在 ```markdown ... ``` 中作为"展示"
        // 这些内容并非真正的代码，需要做 normalize 处理
        // 其他代码块（java/python 等）保持不动
        r = normalizeMarkdownShowcaseBlocks(r);

        // ═══ 阶段 7：压缩表格块内空行（必须在最后，因为前面的规则可能插入空行） ═══
        r = compactTableBlocks(r);

        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    //  代码块保护 / 恢复
    // ═══════════════════════════════════════════════════════════════

    private static String protectCodeBlocks(String text, List<String> codeBlocks) {
        String result = text;
        // 1. 围栏代码块 ```...``` → CB（严格匹配，需闭合）
        result = replaceAndStore(result, Pattern.compile("```[^\\n]*\\n[\\s\\S]*?```"), codeBlocks, CB_PREFIX);
        // 1b. 兜底：未闭合的围栏代码块（AI 流式输出可能缺少结尾 ```）
        result = replaceAndStore(result, Pattern.compile("```[^\\n]*\\n[\\s\\S]*$"), codeBlocks, CB_PREFIX);
        result = replaceAndStore(result, Pattern.compile("`[^`\n]+`"), codeBlocks, IC_PREFIX);
        // 保护加粗标记 **...** —— 避免 * 被列表规则误匹配为无序列表标记
        // 必须在斜体 *...* 之前提取，否则 ** 会被拆成两个 *
        result = replaceAndStore(result, Pattern.compile("\\*\\*[^*\\n]+\\*\\*"), codeBlocks, IC_PREFIX);
        // 保护斜体标记 *...* —— 加粗已提取，剩余单 * 就是斜体
        result = replaceAndStore(result, Pattern.compile("\\*[^*\\n]+\\*"), codeBlocks, IC_PREFIX);
        return result;
    }

    private static String replaceAndStore(String text, Pattern pattern, List<String> store, String prefix) {
        Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String placeholder = prefix + store.size() + CB_SUFFIX;
            store.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String restoreCodeBlocks(String text, List<String> codeBlocks) {
        String result = text;
        for (int i = codeBlocks.size() - 1; i >= 0; i--) {
            // 清理代码块内容开头的垃圾文本
            // 飞书/语雀等富文本编辑器导出时，会将"复制代码"按钮文字写入代码块内容第一行
            // 模式：```text\n复制\n... → 移除"复制"行
            String cleaned = stripCopyLabelFromCodeBlock(codeBlocks.get(i));
            // 尝试两种前缀：CB（代码块）和 IC（行内元素）
            result = result.replace(CB_PREFIX + i + CB_SUFFIX, cleaned);
            result = result.replace(IC_PREFIX + i + CB_SUFFIX, codeBlocks.get(i));
        }
        return result;
    }

    /**
     * 清理代码块内容开头的"复制"垃圾行。
     * 飞书/语雀等富文本编辑器导出 Markdown 时，将"复制代码"按钮文字写入代码块：
     * ```text
     * 复制
     * 真正的代码内容...
     * ```
     * 本方法检测并移除这种垃圾行，保持代码块内容干净。
     *
     * @param codeBlock 代码块的完整文本（包含 ``` 开头和结尾）
     * @return 清理后的代码块文本
     */
    private static String stripCopyLabelFromCodeBlock(String codeBlock) {
        // 只处理围栏代码块（以 ``` 开头）
        if (!codeBlock.startsWith("```")) return codeBlock;
        // 找到 ``` 后的第一个换行符，提取代码块内容
        int firstNewline = codeBlock.indexOf('\n');
        if (firstNewline < 0) return codeBlock;
        String content = codeBlock.substring(firstNewline + 1);
        // 检查内容第一行是否为"复制"（可能有前后空白）
        int secondNewline = content.indexOf('\n');
        String firstLine;
        String rest;
        if (secondNewline < 0) {
            firstLine = content.trim();
            rest = "";
        } else {
            firstLine = content.substring(0, secondNewline).trim();
            rest = content.substring(secondNewline + 1);
        }
        if ("复制".equals(firstLine) || "Copy".equals(firstLine) || "复制代码".equals(firstLine)) {
            return codeBlock.substring(0, firstNewline + 1) + rest;
        }
        return codeBlock;
    }

    /**
     * 保护表格行（| 开头的行），避免被列表规则误匹配。
     * 表格中的 | -- | 等内容会被 [-*+]\s 规则误判为列表，导致分隔行被拆成多行。
     * 与前端的 protectTableLines 逻辑对齐。
     */
    private static String protectTableLines(String text, List<String> tableLines) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("|") && trimmed.length() > 1 && isTableRow(lines[i])) {
                String placeholder = TB_PREFIX + tableLines.size() + CB_SUFFIX;
                tableLines.add(lines[i]);
                sb.append(placeholder);
            } else {
                sb.append(lines[i]);
            }
        }
        return sb.toString();
    }

    private static String restoreTableLines(String text, List<String> tableLines) {
        String result = text;
        for (int i = tableLines.size() - 1; i >= 0; i--) {
            result = result.replace(TB_PREFIX + i + CB_SUFFIX, tableLines.get(i));
        }
        return result;
    }

    /**
     * 对 Markdown 展示块（```markdown / ```md 开头的代码块）的内容做二次 normalize。
     * AI 模型常把 Markdown 文本包裹在 ```markdown ... ``` 中作为展示，这些内容并非真正的代码，
     * 需要和外部文本一样做断行/补空格处理。
     * 其他代码块（java/python/shell 等）保持不动。
     */
    private static String normalizeMarkdownShowcaseBlocks(String text) {
        // 匹配 ```markdown 或 ```md 开头的代码块，提取其内容做 normalize
        // AI 输出可能是 ```markdown\n 或 ```markdown 直接紧跟内容（无换行）
        Pattern p = Pattern.compile("(```(?:markdown|md)\\n?)([\\s\\S]*?)(```)");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String opening = m.group(1);   // ```markdown\n 或 ```markdown 或 ```md\n 或 ```md
            String inner = m.group(2);      // 代码块内容
            String closing = m.group(3);    // ```
            // 对内部内容做一次 normalize（递归调用，但 Markdown 展示块内部不会再有 markdown 代码块，所以不会无限递归）
            String normalizedInner = normalize(inner);
            m.appendReplacement(sb, Matcher.quoteReplacement(opening + normalizedInner + closing));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  表格处理
    // ═══════════════════════════════════════════════════════════════


    /**
     * 去除连续重复的分隔行。
     * mergeTableFragments 和 fixTableBlocks 可能各生成一次分隔行，
     * 导致连续出现多个 | --- | --- |。
     */
    private static String dedupSeparatorRows(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        boolean lastWasSep = false;
        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            String trimmed = line.trim();
            boolean isSep = trimmed.startsWith("|") && trimmed.contains("---")
                    && trimmed.matches("\\|\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?");
            if (isSep && lastWasSep) {
                continue;
            }
            if (isSep) {
                lastWasSep = true;
                // 回删分隔行后的空行
                if (!result.isEmpty() && result.get(result.size() - 1).trim().isEmpty()) {
                    result.remove(result.size() - 1);
                }
            } else if (!trimmed.isEmpty()) {
                lastWasSep = false;
            }
            result.add(line);
        }
        return String.join("\n", result);
    }
    private static String processTables(String text) {
        String r = mergeTableFragments(text);
        r = cleanupOrphanedDashFragments(r);
        r = splitTableContentFromListItems(r);
        r = splitDoublePipeTables(r);
        r = fixTableBlocks(r);
        r = dedupSeparatorRows(r);
        r = compactTableBlocks(r);
        return r;
    }

    /**
     * 压缩表格块内的空行。
     * 表格行（以 | 开头）之间不应有空行，否则 Markdown 渲染器不识别为表格。
     * 只处理连续表格行之间的空行，不影响表格块前后的空行。
     */
    private static String compactTableBlocks(String text) {
        String[] lines = text.split("\\n", -1);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            // 如果当前行是空行，检查前后是否都是表格行
            if (trimmed.isEmpty() && !result.isEmpty()) {
                String prev = result.get(result.size() - 1).trim();
                // 看下一行（如果有的话）
                String next = (i + 1 < lines.length) ? lines[i + 1].trim() : "";
                if (prev.startsWith("|") && prev.endsWith("|") && next.startsWith("|") && next.endsWith("|")) {
                    continue; // 跳过表格行之间的空行
                }
            }
            result.add(lines[i]);
        }
        return String.join("\n", result);
    }

    /**
     * 清理碎片化表格分隔行留下的孤立短横线行。
     * 如 "--"、"-"、"------" 等不是表格行（不以 | 开头）的纯短横线行。
     */
    private static String cleanupOrphanedDashFragments(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            // 删除孤立的纯短横线碎片（如 "--", "-", "------"）
            if (trimmed.matches("-{1,50}") && !trimmed.equals("---")) {
                continue;
            }
            // 删除孤立的短横线+管道碎片（如 "------|", "---------|"）
            if (trimmed.matches("-{2,50}\\|")) {
                continue;
            }
            // 处理碎片粘合行：如 "---------| | IC24 | 对外 API契约 |"
            if (trimmed.matches("-{2,50}\\|.*\\|.*")) {
                int pipeIdx = trimmed.indexOf('|');
                String afterPipe = trimmed.substring(pipeIdx + 1).trim();
                result.add("| " + afterPipe);
                continue;
            }
            // 删除表格行中的纯短横线碎片：| ------ | 或 | --- |-- 等
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                // 检查是否全是 | 和 - 和空格
                String noSep = trimmed.replaceAll("[|\\-\\s:]", "");
                if (noSep.isEmpty() && !trimmed.contains("---")) {
                    // 纯碎片行（没有 --- 的），跳过
                    continue;
                }
            }
            result.add(line);
        }
        return String.join("\n", result);
    }

    /**
     * 合并 AI 流式输出中的碎片化表格行。
     * AI 模型在流式输出时，可能把分隔行 |---|---| 拆成多个碎片：
     *   |          (只有竖线)
     *   ------|    (分隔内容碎片)
     *   ------|    (另一个碎片)
     * 本方法将这些碎片合并为完整的表格分隔行。
     */
    private static String mergeTableFragments(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();

            // 检测碎片模式：一个 | 行后紧跟碎片行
            if (line.equals("|")) {
                // 收集后续的碎片行（------| 和空行）
                int j = i + 1;
                // 跳过空行
                while (j < lines.length && lines[j].trim().isEmpty()) j++;
                // 收集碎片
                List<String> fragments = new ArrayList<>();
                while (j < lines.length && lines[j].trim().matches("-+\\|?")) {
                    fragments.add(lines[j].trim());
                    j++;
                    // 跳过碎片之间的空行
                    while (j < lines.length && lines[j].trim().isEmpty()) j++;
                }

                if (!fragments.isEmpty()) {
                    // 合并碎片为完整的分隔行
                    // 每个 ------| 代表一个单元格的 --- 分隔
                    // 重建为标准分隔行：| --- | --- | ... |
                    StringBuilder separator = new StringBuilder("|");
                    for (String frag : fragments) {
                        separator.append(" --- |");
                    }
                    result.add(separator.toString());
                    i = j;
                } else {
                    // 没有碎片，可能是分隔行开头但后续内容不匹配
                    // 这种情况 | 后面应该紧跟分隔行内容，直接删除孤立的 |
                    // 如果下一行（跳过空行）是表格数据行，说明 | 是碎片分隔行的开头
                    int nextNonEmpty = i + 1;
                    while (nextNonEmpty < lines.length && lines[nextNonEmpty].trim().isEmpty()) nextNonEmpty++;
                    if (nextNonEmpty < lines.length && lines[nextNonEmpty].trim().startsWith("|") && lines[nextNonEmpty].trim().length() > 1) {
                        // | 是分隔行的碎片开头，但后续内容被其他规则处理了
                        // 需要检查前一行是否是表格数据行
                        // 如果前一行是表格行，则需要插入分隔行
                        boolean prevIsTable = !result.isEmpty() && result.get(result.size() - 1).trim().startsWith("|") && result.get(result.size() - 1).trim().length() > 1;
                        if (prevIsTable) {
                            // 计算列数
                            String prevLine = result.get(result.size() - 1).trim();
                            int colCount = countColumns(prevLine);
                            result.add(buildSeparatorRow(colCount));
                        }
                        // 跳过 | 和空行
                        i = nextNonEmpty;
                    } else {
                        // 孤立 | 无法处理，保留
                        result.add(lines[i]);
                        i++;
                    }
                }
            } else {
                // 也要检测独立的碎片行 ------|（可能出现在 | 行已经被其他处理移除后）
                if (line.matches("-+\\|?")) {
                    // 独立碎片行，可能是分隔行的残留
                    // 检查前一行是否是表格数据行
                    boolean prevIsTable = !result.isEmpty() && result.get(result.size() - 1).trim().startsWith("|") && result.get(result.size() - 1).trim().length() > 1;
                    if (prevIsTable) {
                        // 是分隔行碎片，跳过（fixSingleTable 会补全分隔行）
                        i++;
                        continue;
                    }
                }
                result.add(lines[i]);
                i++;
            }
        }
        return String.join("\n", result);
    }

    /**
     * 分离表格行中粘合的列表/标题内容。
     * AI 模型有时把表格最后一行和后续列表粘在一起：
     *   | 缺点 | 扩展困难 |\u0001IC1\u0001- ✅私有构造函数
     * 
     * 策略：从右往左找第一个 | 后紧跟非表格内容的位置，从此处拆分。
     * 非表格内容 = IC/CB 占位符、**、- 列表标记、## 标题。
     * 注意：不使用 String.trim()（会吃掉 \u0001），只用 trimAsciiOnly。
     */
    private static String splitTableContentFromListItems(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.length() > 1) {
                int splitPos = findSplitPosition(trimmed);
                if (splitPos > 0 && splitPos < trimmed.length() - 1) {
                    String tablePart = trimmed.substring(0, splitPos + 1); // 包含 |
                    String trailing = trimmed.substring(splitPos + 1);
                    if (countPipes(tablePart) >= 2) {
                        result.add(tablePart);
                        result.add("");
                        result.add(trimAsciiOnly(trailing));
                        continue;
                    }
                }
                result.add(line);
            } else {
                result.add(line);
            }
        }
        return String.join("\n", result);
    }

    /**
     * 从右往左找到表格行中最后一个有效拆分点。
     * 返回该 | 的索引，-1 表示无粘合内容。
     */
    private static int findSplitPosition(String line) {
        for (int pos = line.length() - 1; pos >= 0; pos--) {
            if (line.charAt(pos) != '|') continue;
            String after = line.substring(pos + 1);
            if (after.isEmpty()) continue;
            if (after.startsWith(IC_PREFIX) || after.startsWith(CB_PREFIX)
                    || after.startsWith("**") || after.startsWith("##")
                    || after.matches("-\\s*[✅❌⚠].*") || after.matches("-\\s+\\S.*")) {
                String before = line.substring(0, pos);
                if (countPipes(before) >= 1) {
                    return pos;
                }
            }
        }
        return -1;
    }

    /**
     * 只删除 ASCII 空格 (0x20)、制表符 (0x09)、换行符 (0x0a)、回车 (0x0d)。
     * 不删除 \u0001 等控制字符，保护占位符前缀/后缀。
     */
    private static String trimAsciiOnly(String s) {
        if (s == null) return null;
        int start = 0, end = s.length();
        while (start < end && isTrimChar(s.charAt(start))) start++;
        while (end > start && isTrimChar(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isTrimChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static int countPipes(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == '|') count++;
        }
        return count;
    }

    private static String splitDoublePipeTables(String text) {
        if (!text.contains("||")) return text;

        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\\n", -1);

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append("\n");
            if (lines[i].contains("||")) {
                result.append(splitSingleGluedLine(lines[i]));
            } else {
                result.append(lines[i]);
            }
        }
        return result.toString();
    }

    private static String splitSingleGluedLine(String line) {
        int firstPipe = line.indexOf('|');
        if (firstPipe < 0) return line;

        String prefix = "";
        String tablePart = line;
        if (firstPipe > 0) {
            String beforeFirstPipe = line.substring(0, firstPipe).trim();
            if (!beforeFirstPipe.isEmpty() && !beforeFirstPipe.startsWith("|")) {
                prefix = beforeFirstPipe + "\n";
                tablePart = line.substring(firstPipe);
            }
        }

        String[] segments = tablePart.split("\\|\\|");
        List<String> rows = new ArrayList<>();

        for (String seg : segments) {
            String row = seg.trim();
            if (row.isEmpty()) continue;

            // 检查该段是否有尾部非表格内容
            int trailingIdx = findTrailingContentIndex(row);
            String tableRowPart;
            String trailingContent = null;

            if (trailingIdx > 0) {
                // trailingIdx 指向 | 的位置，| 属于表格行尾
                tableRowPart = row.substring(0, trailingIdx + 1).trim(); // 包含 |
                trailingContent = row.substring(trailingIdx + 1).trim();  // | 之后的内容
            } else {
                tableRowPart = row;
            }

            if (!tableRowPart.isEmpty()) {
                if (!tableRowPart.startsWith("|")) tableRowPart = "|" + tableRowPart;
                if (!tableRowPart.endsWith("|")) tableRowPart = tableRowPart + "|";
                if (isSeparatorContent(tableRowPart)) {
                    tableRowPart = formatSeparatorRow(tableRowPart);
                } else {
                    tableRowPart = normalizeCellSpacing(tableRowPart);
                }
                rows.add(tableRowPart);
            }

            if (trailingContent != null && !trailingContent.isEmpty()) {
                rows.add(trailingContent);
            }
        }
        return prefix + String.join("\n", rows);
    }

    /**
     * 找到 segment 中表格行尾 | 后紧跟非表格内容的 | 的位置。
     * 返回 -1 表示没有尾部内容。
     */
    private static int findTrailingContentIndex(String segment) {
        // | 后紧跟 ## 标题标记（取第一个匹配，因为一旦标题开始后续都是非表格内容）
        Matcher m = Pattern.compile("\\|\\s*(#{1,6}\\s)").matcher(segment);
        if (m.find()) {
            return m.start();
        }

        // | 后紧跟中文，且后面没有更多 |，且内容长度>30或含标点
        Matcher m2 = Pattern.compile("\\|\\s*([\\u4e00-\\u9fa5])").matcher(segment);
        int lastMatch = -1;
        while (m2.find()) {
            String after = segment.substring(m2.end());
            if (!after.contains("|")) {
                String textAfterPipe = segment.substring(m2.start() + 1).trim();
                if (textAfterPipe.matches(".*[。！？].*") || textAfterPipe.length() > 30) {
                    lastMatch = m2.start();
                }
            }
        }
        return lastMatch;
    }

    private static boolean isSeparatorContent(String row) {
        String trimmed = row.trim();
        if (!trimmed.startsWith("|") || trimmed.length() <= 2) return false;
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        if (inner.isEmpty()) return false;
        String[] cells = inner.split("\\|");
        boolean hasDash = false;
        for (String cell : cells) {
            String c = cell.trim();
            if (c.isEmpty()) continue;
            if (!c.matches("[-:]+")) return false;
            if (c.contains("-")) hasDash = true;
        }
        return hasDash;
    }

    private static String formatSeparatorRow(String row) {
        String trimmed = row.trim();
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        String[] cells = inner.split("\\|");
        StringBuilder sb = new StringBuilder("|");
        for (String cell : cells) {
            String c = cell.trim();
            if (c.isEmpty()) continue;
            sb.append(" ").append(c).append(" |");
        }
        return sb.toString();
    }

    private static String normalizeCellSpacing(String row) {
        String trimmed = row.trim();
        String inner = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        String[] cells = inner.split("\\|", -1);
        StringBuilder sb = new StringBuilder("|");
        for (String cell : cells) {
            sb.append(" ").append(cell.trim()).append(" |");
        }
        return sb.toString();
    }

    private static String fixTableBlocks(String text) {
        String[] lines = text.split("\\n", -1);
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (isTableRow(lines[i])) {
                List<String> tableRows = new ArrayList<>();
                while (i < lines.length && isTableRow(lines[i])) {
                    tableRows.add(lines[i]);
                    i++;
                }
                result.addAll(fixSingleTable(tableRows));
            } else {
                result.add(lines[i]);
                i++;
            }
        }
        return String.join("\n", result);
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.length() > 1;
    }

    private static List<String> fixSingleTable(List<String> rows) {
        if (rows.isEmpty()) return rows;
        if (isSeparatorContent(rows.get(0))) return rows;

        int colCount = countColumns(rows.get(0));
        List<String> fixed = new ArrayList<>();

        for (int j = 0; j < rows.size(); j++) {
            String row = rows.get(j);
            if (isSeparatorContent(row)) {
                int sepCols = countColumns(row);
                if (sepCols != colCount) {
                    fixed.add(buildSeparatorRow(colCount));
                } else {
                    fixed.add(formatSeparatorRow(row));
                }
            } else {
                // 确保非分隔行也有正确的 | 包裹和末尾 |
                if (!row.startsWith("|")) row = "|" + row;
                if (!row.endsWith("|")) row = normalizeCellSpacing(row);
                fixed.add(row);
                if (j == 0) {
                    boolean nextIsSep = (j + 1 < rows.size()) && isSeparatorContent(rows.get(j + 1));
                    if (!nextIsSep) {
                        fixed.add(buildSeparatorRow(colCount));
                    }
                }
            }
        }
        return fixed;
    }

    private static int countColumns(String row) {
        String trimmed = row.trim();
        if (!trimmed.startsWith("|")) return 0;
        String inner = trimmed;
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        return inner.split("\\|", -1).length;
    }

    private static String buildSeparatorRow(int colCount) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < colCount; c++) sb.append(" --- |");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  空行修复（逐行处理）
    // ═══════════════════════════════════════════════════════════════

    private static String ensureBlankLines(String text) {
        String[] lines = text.split("\\n", -1);
        List<String> result = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                // 注意：不能用 String.trim()，它会吞掉 \u0001 控制字符
                String prev = stripTrailingWhitespace(lines[i - 1]);
                String curr = stripTrailingWhitespace(lines[i]);

                if (prev.isEmpty()) {
                    result.add(lines[i]);
                    continue;
                }

                boolean needBlank = false;

                if (curr.matches("#{1,6}\\s.*") && !prev.matches("#{1,6}\\s.*")) needBlank = true;
                if (prev.matches("#{1,6}\\s.*") && !curr.matches("#{1,6}\\s.*") && !curr.isEmpty()
                        && !curr.startsWith("|") && !isSeparatorContent(curr)) needBlank = true;
                if (curr.matches("\\d+\\.\\s.*") && !prev.matches("\\d+\\.\\s.*")) needBlank = true;
                if (curr.matches("[-*+]\\s.*") && !prev.matches("[-*+]\\s.*")) needBlank = true;
                if (curr.startsWith("|") && !prev.startsWith("|")) needBlank = true;
                if (prev.startsWith("|") && !curr.startsWith("|") && !curr.isEmpty()) needBlank = true;
                if (curr.startsWith(CB_PREFIX) && !prev.startsWith(CB_PREFIX)) needBlank = true;
                if (prev.endsWith(CB_SUFFIX) && !curr.startsWith(CB_PREFIX) && !curr.isEmpty()) needBlank = true;

                if (needBlank) result.add("");
            }
            result.add(lines[i]);
        }
        return String.join("\n", result);
    }

    /** 安全的 trim 替代，只去掉 ASCII 空白（空格、制表符），不吞控制字符 */
    private static String stripTrailingWhitespace(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) end--;
        return s.substring(0, end);
    }
}
