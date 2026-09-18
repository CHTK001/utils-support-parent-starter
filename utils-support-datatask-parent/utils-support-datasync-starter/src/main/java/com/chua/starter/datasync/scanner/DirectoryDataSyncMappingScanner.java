package com.chua.starter.datasync.scanner;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.DirectoryConfigDefinition;
import com.chua.starter.datasync.mapping.DataSyncMappingManager;
import com.chua.starter.datasync.model.DataSyncMapping;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
* 目录映射扫描器，负责启动扫描目录下所有映射配置，
* 并监听目录变化进行热加载。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DirectoryDataSyncMappingScanner {

    /**
    * 轮询间隔（毫秒）
    */
    private static final long POLL_INTERVAL_MS = 2000;

    /** 映射管理器 */
    private final DataSyncMappingManager mappingManager;
    /** 配置定义 */
    private final DirectoryConfigDefinition config;
    /** 配置文件解析器列表 */
    private final List<ConfigFileParser> parsers;
    /** 定时任务执行器 */
    private final ScheduledExecutorService executor;
    /** 文件监听服务 */
    private WatchService watchService;

    /**
    * 创建 目录数据同步mappingscanner 实例
    * @param mappingManager mapping管理器
    * @param config 配置
    * @param parsers parsers
    */
    public DirectoryDataSyncMappingScanner(DataSyncMappingManager mappingManager,
                                           DirectoryConfigDefinition config,
                                           List<ConfigFileParser> parsers) {
        this.mappingManager = mappingManager;
        this.config = config;
        this.parsers = parsers != null ? parsers : List.of();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "datasync-dir-scanner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
    * 启动扫描并监听目录变化。
    */
    public void start() {
        Path dir = Path.of(config.directoryPath());
        if (!Files.isDirectory(dir)) {
            log.warn("目录不存在，跳过扫描: {}", config.directoryPath());
            return;
        }
        scanAll(dir);
        watch(dir);
    }

    /**
    * 停止扫描并关闭监听。
    */
    public void stop() {
        executor.shutdown();
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException e) {
            log.warn("关闭目录监听失败", e);
        }
    }

    /**
    * 扫描全部
    *
    * @param dir dir
    */
    private void scanAll(Path dir) {
        try {
            Files.walk(dir).filter(Files::isRegularFile).forEach(file -> {
                for (ConfigFileParser parser : parsers) {
                    if (parser.supports(file)) {
                        try {
                            DataSyncConfigDefinition def = parser.parse(file);
                            if (def != null) {
                                String mappingId = file.getFileName().toString();
                                int dot = mappingId.lastIndexOf('.');
                                if (dot > 0) {
                                    mappingId = mappingId.substring(0, dot);
                                }
                                DataSyncMapping mapping = mappingManager.createFromConfig(mappingId, def);
                                mappingManager.addMapping(mapping);
                                log.info("扫描加载映射: file={}, mappingId={}", file, mappingId);
                            }
                        } catch (Exception e) {
                            log.error("解析配置文件失败: file={}", file, e);
                        }
                        break;
                    }
                }
            });
        } catch (IOException e) {
            log.error("扫描目录失败: {}", dir, e);
        }
    }

    /**
    * Watch
    *
    * @param dir dir
    */
    private void watch(Path dir) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

            executor.scheduleWithFixedDelay(() -> {
                WatchKey key;
                try {
                    key = watchService.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (key == null) {
                    return;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    Path relative = (Path) event.context();
                    Path child = dir.resolve(relative);
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        String mappingId = relative.toString();
                        int dot = mappingId.lastIndexOf('.');
                        if (dot > 0) {
                            mappingId = mappingId.substring(0, dot);
                        }
                        mappingManager.removeMapping(mappingId);
                        log.info("移除映射: mappingId={}, file={}", mappingId, child);
                    } else {
                        for (ConfigFileParser parser : parsers) {
                            if (parser.supports(child)) {
                                try {
                                    DataSyncConfigDefinition def = parser.parse(child);
                                    if (def != null) {
                                        String mappingId = relative.toString();
                                        int dot = mappingId.lastIndexOf('.');
                                        if (dot > 0) {
                                            mappingId = mappingId.substring(0, dot);
                                        }
                                        DataSyncMapping mapping = mappingManager.createFromConfig(mappingId, def);
                                        mappingManager.addMapping(mapping);
                                        log.info("热加载映射: file={}, mappingId={}", child, mappingId);
                                    }
                                } catch (Exception e) {
                                    log.error("热加载失败: file={}", child, e);
                                }
                                break;
                            }
                        }
                    }
                }
                boolean valid = key.reset();
                if (!valid) {
                    log.warn("目录监听已失效: {}", dir);
                }
            }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            log.error("监听目录失败: {}", dir, e);
        }
    }
}
