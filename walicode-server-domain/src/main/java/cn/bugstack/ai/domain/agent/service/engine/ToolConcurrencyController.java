package cn.bugstack.ai.domain.agent.service.engine;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具并发控制器
 * <p>对标 WaLiCode toolConcurrencyController.ts
 * <p>
 * 策略：
 * - 只读工具（read_file, list_files, search, grep）→ 并发执行（最多 5 个）
 * - 写工具（write_file, edit_file, delete, execute_command）→ 串行执行
 * - 需要确认的工具 → 由 PermissionGuard 处理
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class ToolConcurrencyController {

    /** 只读工具线程池（最大并发 5） */
    private final ExecutorService readOnlyPool = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "tool-readonly-" + threadCounter.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /** 写工具串行执行锁 */
    private final ReentrantLock writeLock = new ReentrantLock();

    /** 当前正在执行的工具数 */
    private final AtomicInteger activeToolCount = new AtomicInteger(0);

    private static final AtomicInteger threadCounter = new AtomicInteger(0);

    /** 只读工具集合 */
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "readFile", "read_file",
            "listFiles", "list_files",
            "searchCode", "search_code",
            "grepSearch", "grep_search",
            "globSearch", "glob_search",
            "webSearch", "web_search",
            "webFetch", "web_fetch",
            "decompile",
            "checkStatus", "check_status"
    );

    /** 危险/写工具集合 */
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
            "executeCommand", "execute_command",
            "executeLocalCommand", "execute_local_command",
            "writeFile", "write_file",
            "editFile", "edit_file",
            "deleteFile", "delete_file",
            "compileProject", "compile_project",
            "deployFiles", "deploy_files"
    );

    /**
     * 判断是否为只读工具
     */
    public boolean isReadOnly(String toolName) {
        if (toolName == null) return false;
        return READ_ONLY_TOOLS.contains(toolName);
    }

    /**
     * 判断是否为危险/写工具
     */
    public boolean isDangerous(String toolName) {
        if (toolName == null) return false;
        return DANGEROUS_TOOLS.contains(toolName);
    }

    /**
     * 获取只读工具线程池
     */
    public ExecutorService getReadOnlyPool() {
        return readOnlyPool;
    }

    /**
     * 获取写工具锁
     */
    public ReentrantLock getWriteLock() {
        return writeLock;
    }

    /**
     * 获取当前活跃工具数
     */
    public int getActiveToolCount() {
        return activeToolCount.get();
    }

    /**
     * 工具开始执行时调用
     */
    public void onToolStart() {
        activeToolCount.incrementAndGet();
    }

    /**
     * 工具结束执行时调用
     */
    public void onToolEnd() {
        activeToolCount.decrementAndGet();
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭工具并发控制器线程池...");
        readOnlyPool.shutdown();
        try {
            if (!readOnlyPool.awaitTermination(5, TimeUnit.SECONDS)) {
                readOnlyPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            readOnlyPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("工具并发控制器线程池已关闭");
    }
}
