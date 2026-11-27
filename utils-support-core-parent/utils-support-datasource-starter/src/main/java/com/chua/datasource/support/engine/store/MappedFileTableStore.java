package com.chua.datasource.support.engine.store;

import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 以 JDK 共享内存映射文件承载整表行的存储实现。
 *
 * <p>每张表对应目录下的一个数据文件与一个索引文件：数据文件按「记录」追加，
 * 记录形如 {@code [int MAGIC][int payloadLen][payload]}，payload 首字节是编码方式
 * （JDK 序列化 / JSON 兜底），删除只把 payload 首字节改成墓碑标记；索引文件
 * （{@code .idx}）保存行顺序的绝对文件偏移与写入位置，堆内常驻的只有
 * {@code long[]} 偏移数组。</p>
 *
 * <h2>为什么堆占用更低</h2>
 * <p>行字节留在映射段里（由操作系统按页换入换出），堆内只保留偏移数组；
 * 读一行是「定位段 → 读长度 → 反序列化」。算子仍在本地执行，本实现只承载
 * <b>整表快照</b>，不参与 SQL 求值。</p>
 *
 * <h2>已知取舍</h2>
 * <ul>
 *   <li>{@link #open(String)} 返回的列表每次 {@code get} 都是<b>新对象副本</b>：
 *       就地改字段不会落盘，引擎的更新路径必须按下标 {@code set} 写回（本仓库已按此改造）。</li>
 *   <li>非 {@link java.io.Serializable} 的行走 JSON 兜底，数值类型可能漂移
 *       （如 {@code Integer} 变 {@code Long}）；可序列化行保真。</li>
 *   <li>重新打开表时按物理追加顺序重建行序；被覆盖/删除的行不会复活，但
 *       <b>重定位过的行会排到末尾</b>，未显式 {@code ORDER BY} 时不保证行序。</li>
 *   <li>{@link #close()} 保留数据文件（下次 {@link #open(String)} 可恢复），
 *       {@link #closeTable(String)} 与 {@link #replace(String, List)} 之外的摘除会删除文件。</li>
 * </ul>
 *
 * <p>目录由 {@value #DIR_PROPERTY} 指定，默认 {@code <java.io.tmpdir>/chua-engine-mmap}；
 * 多个引擎同时使用 mmap 时应为每个引擎配置不同目录，避免同名表互相覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi(value = MappedFileTableStore.SPI_NAME, order = 100)
public class MappedFileTableStore implements TableStore {

    /**
     * SPI 名称。
     */
    public static final String SPI_NAME = "mmap";

    /**
     * 存储目录系统属性。
     */
    public static final String DIR_PROPERTY = "engine.memory.store.dir";

    /**
     * 默认目录名（位于 {@code java.io.tmpdir} 下）。
     */
    public static final String DEFAULT_DIR_NAME = "chua-engine-mmap";

    /**
     * 表名 → 已打开的映射表。
     */
    private final Map<String, MappedTable> tables = new ConcurrentHashMap<>();

    /**
     * 数据文件存放目录。
     */
    private final Path directory;

    /**
     * 按系统属性初始化存储目录。
     */
    public MappedFileTableStore() {
        this(resolveDirectory());
    }

    /**
     * 指定目录初始化存储。
     *
     * @param directory 数据文件目录，不存在时自动创建
     */
    public MappedFileTableStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建内存映射表存储目录: " + this.directory, e);
        }
    }

    /**
     * 解析存储目录：优先系统属性，否则 {@code java.io.tmpdir/chua-engine-mmap}。
     *
     * @return 存储目录
     */
    public static Path resolveDirectory() {
        String configured = System.getProperty(DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim());
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "."), DEFAULT_DIR_NAME);
    }

    @Override
    public String name() {
        return SPI_NAME;
    }

    /**
     * 当前存储目录。
     *
     * @return 目录
     */
    public Path directory() {
        return directory;
    }

    /**
     * 目录是实例级配置：实现名相同但目录不同<b>不可</b>互相替代。
     *
     * <p>否则 {@code engine.setTableStore(new MappedFileTableStore(otherDir))}
     * 会被当成空操作，行仍然写进旧目录，显式配置静默失效。</p>
     *
     * @param other 另一个存储实例
     * @return {@code true} 表示同目录、可直接沿用当前实例
     */
    @Override
    public boolean sameStore(TableStore other) {
        return this == other || other instanceof MappedFileTableStore mapped
                && name().equals(mapped.name()) && directory.equals(mapped.directory);
    }

    @Override
    public List<Object> open(String table) {
        Objects.requireNonNull(table, "table");
        return tables.computeIfAbsent(table, key -> {
            try {
                return new MappedTable(key, directory);
            } catch (IOException e) {
                throw new UncheckedIOException("打开内存映射表失败: " + key, e);
            }
        }).rows();
    }

    @Override
    public void replace(String table, List<Object> rows) {
        List<Object> target = open(table);
        if (target == rows) {
            return;
        }
        target.clear();
        target.addAll(rows);
    }

    @Override
    public void closeTable(String table) {
        MappedTable mounted = tables.remove(table);
        if (mounted != null) {
            mounted.close(true);
        }
    }

    @Override
    public void close() {
        for (Map.Entry<String, MappedTable> entry : tables.entrySet()) {
            MappedTable mounted = tables.remove(entry.getKey());
            if (mounted != null) {
                mounted.close(false);
            }
        }
        tables.clear();
    }
}
