package com.chua.common.support.collection;

import com.chua.common.support.serialize.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * 堆外内存数据存储，基于 {@link Arena} + {@link MemorySegment} 实现。
 *
 * <p>使用项目统一的 {@link Serializer} SPI 完成对象与字节的转换，
 * 将序列化后的数据存储在 native 内存中。调用 {@link #close()} 时
 * {@link Arena#close()} 确定性释放所有 native 内存，无需等待 GC。</p>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>写操作（append/appendAll/clear/close）：{@code synchronized(writeLock)} 保护</li>
 *   <li>读操作（get/size）：无锁，基于写后快照（Snapshot-on-Write）模式，
 *       volatile 引用替换不可变列表保证读一致性</li>
 * </ul>
 *
 * <h3>序列化</h3>
 * <p>通过 {@link Serializer} SPI 注入序列化策略，支持 java/json 等多种格式。
 * 可通过 {@link com.chua.common.support.spi.ServiceProvider} 按名称加载：
 * <pre>
 *   Serializer<MyData> serializer = ServiceProvider.of(Serializer.class).getExtension("json");
 * </pre>
 * </p>
 *
 * @param <E> 元素类型，必须实现 {@link Serializable}
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see DataStore
 * @see OnHeapDataStore
 * @see Serializer
 * @see Arena
 * @see MemorySegment
 */
@Slf4j
public class OffHeapDataStore<E extends Serializable> implements DataStore<E> {

    private static final long serialVersionUID = 1L;

    /** 序列化器，将对象与字节数组互转 */
    private final Serializer<E> serializer;

    /**
     * 共享 Arena，管理所有 native 内存段的生命周期，close 时一次性释放
     */
    private final Arena arena;

    /**
     * 写后快照：volatile 引用替换不可变列表，读操作无锁。
     *
     * <p>每次写操作（append/appendAll/clear）都会创建新的不可变列表并赋值给此字段，
     * 读操作读取此字段获取一致性快照。</p>
     */
    private volatile List<MemorySegment> segments = Collections.emptyList();

    /**
     * 堆外内存字节统计（无锁累加）。
     *
     * <p>使用 {@link LongAdder} 避免写操作的 CAS 竞争，
     * 读取时通过 {@link LongAdder#sum()} 获取近似值。</p>
     */
    private final LongAdder totalBytes = new LongAdder();

    /** 关闭标志，volatile 保证可见性 */
    private volatile boolean closed;

    /**
     * 写操作互斥锁，保护 append/appendAll/clear/close 的原子性。
     *
     * <p>使用 synchronized 而非 ReentrantLock 以减少内存开销，
     * 因为写操作频率通常远低于读操作。</p>
     */
    private final Object writeLock = new Object();

    /**
     * 构造堆外存储实例。
     *
     * @param serializer 序列化器，用于对象与字节数组的互转
     */
    public OffHeapDataStore(Serializer<E> serializer) {
        this.serializer = serializer;
        this.arena = Arena.ofShared();
    }

    /**
     * {@inheritDoc}
     *
     * <p>序列化元素后分配 native 内存写入，锁外序列化、锁内更新快照引用，
     * 避免持锁时间过长。</p>
     *
     * @throws IllegalArgumentException 如果序列化结果为空
     */
    @Override
    public int append(E element) {
        ensureOpen();
        byte[] data = serializer.serialize(element);
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("序列化结果为空，元素: " + element);
        }

        // 分配 native 内存（锁外执行，避免持锁时间过长）
        MemorySegment segment = arena.allocate(data.length);
        segment.copyFrom(MemorySegment.ofArray(data));

        synchronized (writeLock) {
            ensureOpen();
            List<MemorySegment> current = this.segments;
            List<MemorySegment> newSegments = new ArrayList<>(current.size() + 1);
            newSegments.addAll(current);
            newSegments.add(segment);
            this.segments = Collections.unmodifiableList(newSegments);
            totalBytes.add(data.length);
            return newSegments.size() - 1;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>批量序列化在锁外完成，锁内仅执行内存分配和快照替换，
     * 将 O(n) 序列化开销移出临界区，大幅提升并发吞吐。</p>
     *
     * @throws IllegalArgumentException 如果任一元素序列化结果为空
     */
    @Override
    public int appendAll(Collection<? extends E> elements) {
        ensureOpen();
        if (elements == null || elements.isEmpty()) {
            return 0;
        }

        // 先在锁外序列化所有元素
        List<byte[]> serializedList = new ArrayList<>(elements.size());
        long bytesAdded = 0;
        for (E element : elements) {
            byte[] data = serializer.serialize(element);
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("序列化结果为空，元素: " + element);
            }
            serializedList.add(data);
            bytesAdded += data.length;
        }

        synchronized (writeLock) {
            ensureOpen();
            List<MemorySegment> current = this.segments;
            List<MemorySegment> newSegments = new ArrayList<>(current.size() + serializedList.size());
            newSegments.addAll(current);

            for (byte[] data : serializedList) {
                MemorySegment segment = arena.allocate(data.length);
                segment.copyFrom(MemorySegment.ofArray(data));
                newSegments.add(segment);
            }

            this.segments = Collections.unmodifiableList(newSegments);
            totalBytes.add(bytesAdded);
            return serializedList.size();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>读取时获取 volatile 快照引用，从 {@link MemorySegment} 拷贝字节数组后反序列化。
     * 读操作完全无锁。</p>
     *
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    @Override
    public E get(int index) {
        ensureOpen();
        List<MemorySegment> snapshot = this.segments;
        if (index < 0 || index >= snapshot.size()) {
            throw new IndexOutOfBoundsException("索引 " + index + " 超出范围 [0, " + snapshot.size() + ")");
        }

        MemorySegment segment = snapshot.get(index);
        byte[] data = new byte[(int) segment.byteSize()];
        segment.asByteBuffer().get(data);

        return serializer.deserialize(data);
    }

    /**
     * {@inheritDoc}
     *
     * <p>基于 volatile 快照读取，无锁。</p>
     */
    @Override
    public int size() {
        return segments.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>基于 volatile 快照读取，无锁。</p>
     */
    @Override
    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>清空快照引用并将 totalBytes 归零。注意：已分配的 MemorySegment
     * 仍由 Arena 持有，直到 {@link #close()} 才真正释放 native 内存。</p>
     */
    @Override
    public void clear() {
        synchronized (writeLock) {
            List<MemorySegment> old = this.segments;
            this.segments = Collections.emptyList();
            long bytes = totalBytes.sumThenReset();
            if (!old.isEmpty() && bytes > 0) {
                log.debug("清空堆外存储，释放 {} 字节，{} 个元素", bytes, old.size());
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>关闭 {@link Arena} 确定性释放所有 native 内存，不等 GC。
     * 关闭后任何访问操作将抛出 {@link IllegalStateException}。</p>
     */
    @Override
    public void close() {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            closed = true;
            long bytes = totalBytes.sumThenReset();
            int count = segments.size();
            segments = Collections.emptyList();
            try {
                arena.close();
                log.debug("关闭堆外存储，释放 {} 字节，{} 个元素", bytes, count);
            } catch (Exception e) {
                log.warn("关闭 Arena 异常", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>堆外存储当前不支持只读模式，始终返回 {@code false}。</p>
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>堆外存储始终返回 {@code true}。</p>
     */
    @Override
    public boolean isOffHeap() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>使用 {@link LongAdder#sum()} 返回近似值，适用于监控和日志场景。</p>
     */
    @Override
    public long getOffHeapBytes() {
        return totalBytes.sum();
    }

    /**
     * 获取当前元素数量（等同于 {@link #size()}）。
     *
     * <p>保留此方法以兼容旧版 API，新代码请使用 {@link #size()}。</p>
     *
     * @return 元素数量
     */
    public int elementCount() {
        return segments.size();
    }

    /**
     * 检查存储是否已关闭，若已关闭则抛出异常。
     *
     * @throws IllegalStateException 如果存储已关闭
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("OffHeapDataStore 已关闭，不可再访问");
        }
    }
}
