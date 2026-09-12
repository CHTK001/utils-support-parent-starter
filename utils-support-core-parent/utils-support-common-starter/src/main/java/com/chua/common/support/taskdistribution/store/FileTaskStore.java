package com.chua.common.support.taskdistribution.store;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;
import com.chua.common.support.taskdistribution.task.TaskStatus;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件任务存储实现。
 *
 * <p>基于 JSON 序列化将任务持久化到文件系统，支持应用重启后自动恢复。
 * 文件路径通过 SPI 参数 {@code filePath} 配置，默认 {@code ./data/taskstore/}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("file")
public class FileTaskStore implements TaskStore {

    /**
     * 默认数据目录
     */
    private static final String DEFAULT_DATA_DIR = "./data/taskstore/";

    /**
     * 任务文件后缀
     */
    private static final String TASK_SUFFIX = ".task.json";

    /**
     * 结果文件后缀
     */
    private static final String RESULT_SUFFIX = ".result.json";

    /**
     * 数据目录
     */
    private final Path dataDir;

    /**
     * 内存缓存（避免频繁读文件）
     */
    private final Map<String, Task<?>> taskCache = new ConcurrentHashMap<>();

    /**
     * 结果缓存
     */
    private final Map<String, TaskResult<?>> resultCache = new ConcurrentHashMap<>();

    /**
     * 状态缓存
     */
    private final Map<String, TaskStatus> statusCache = new ConcurrentHashMap<>();

    /**
     * 构造文件任务存储，使用默认数据目录。
     */
    public FileTaskStore() {
        this(DEFAULT_DATA_DIR);
    }

    /**
     * 构造文件任务存储。
     *
     * @param dataDir 数据目录路径
     */
    public FileTaskStore(String dataDir) {
        String dir = dataDir != null ? dataDir : DEFAULT_DATA_DIR;
        this.dataDir = Path.of(dir);
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            log.warn("创建数据目录失败: {}", e.getMessage());
        }
        loadFromDisk();
    }

    /**
     * 启动时从磁盘加载所有任务到缓存。
     */
    private void loadFromDisk() {
        File[] files = dataDir.toFile().listFiles((dir, name) -> name.endsWith(TASK_SUFFIX));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                String taskId = file.getName().replace(TASK_SUFFIX, "");
                Task<?> task = Json.fromJson(content, Task.class);
                if (task != null) {
                    taskCache.put(taskId, task);
                }

                Path resultFile = dataDir.resolve(taskId + RESULT_SUFFIX);
                if (Files.exists(resultFile)) {
                    String resultContent = Files.readString(resultFile, StandardCharsets.UTF_8);
                    TaskResult<?> result = Json.fromJson(resultContent, TaskResult.class);
                    if (result != null) {
                        resultCache.put(taskId, result);
                    }
                }
            } catch (Exception e) {
                log.warn("加载任务文件失败: {}", file.getName(), e);
            }
        }
        log.info("文件任务存储已加载, 任务数: {}", taskCache.size());
    }

    @Override
    /** 保存任务 */
    public void saveTask(Task<?> task, TaskStatus status) {
        if (task == null || task.getTaskId() == null) {
            return;
        }
        taskCache.put(task.getTaskId(), task);
        statusCache.put(task.getTaskId(), status);
        persistTask(task);
    }

    @Override
    /** 更新状态 */
    public void updateStatus(String taskId, TaskStatus status) {
        if (taskId != null) {
            statusCache.put(taskId, status);
        }
    }

    @Override
    /** 保存结果 */
    public void saveResult(TaskResult<?> result) {
        if (result == null || result.getTaskId() == null) {
            return;
        }
        resultCache.put(result.getTaskId(), result);
        persistResult(result);
    }

    @Override
    public Task<?> getTask(String taskId) {
        return taskCache.get(taskId);
    }

    @Override
    /** 获取状态 */
    public TaskStatus getStatus(String taskId) {
        TaskStatus status = statusCache.get(taskId);
        if (status != null) {
            return status;
        }
        return taskCache.containsKey(taskId) ? TaskStatus.PENDING : null;
    }

    @Override
    public TaskResult<?> getResult(String taskId) {
        return resultCache.get(taskId);
    }

    @Override
    public List<Task<?>> getRecoverableTasks() {
        List<Task<?>> result = new ArrayList<>();
        for (Map.Entry<String, Task<?>> entry : taskCache.entrySet()) {
            String taskId = entry.getKey();
            TaskStatus status = statusCache.get(taskId);
            if (status == TaskStatus.PENDING || status == TaskStatus.RUNNING) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    @Override
    /** 移除任务 */
    public void removeTask(String taskId) {
        taskCache.remove(taskId);
        statusCache.remove(taskId);
        resultCache.remove(taskId);
        try {
            Files.deleteIfExists(dataDir.resolve(taskId + TASK_SUFFIX));
            Files.deleteIfExists(dataDir.resolve(taskId + RESULT_SUFFIX));
        } catch (IOException e) {
            log.warn("删除任务文件失败: {}", taskId, e);
        }
    }

    @Override
    /** Clear */
    public void clear() {
        taskCache.clear();
        statusCache.clear();
        resultCache.clear();
        File[] files = dataDir.toFile().listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        clear();
    }

    /**
     * 持久化任务到文件。
     * @param task 任务
     */
    private void persistTask(Task<?> task) {
        try {
            Path file = dataDir.resolve(task.getTaskId() + TASK_SUFFIX);
            String json = Json.toJson(task);
            Path tmp = dataDir.resolve(task.getTaskId() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("持久化任务失败: {}", task.getTaskId(), e);
        }
    }

    /**
     * 持久化结果到文件。
     * @param result 结果
     */
    private void persistResult(TaskResult<?> result) {
        try {
            Path file = dataDir.resolve(result.getTaskId() + RESULT_SUFFIX);
            String json = Json.toJson(result);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("持久化结果失败: {}", result.getTaskId(), e);
        }
    }
}