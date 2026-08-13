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
 * <p>使用 {@link Serializer} SPI 完成对象与字节的转换，存储在 native 内存中。
 * 调用 {@link #close()} 时 {@link Arena#close()} 确定性释放，不等 GC。</p>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>写操作（append/appendAll/clear/close）：synchronized(writeLock) 保护</li>
 *   <li>读操作（get/size）：无锁，基于写后快照（Snapshot-on-Write）</li>
 * </ul>
 *
 * @param <E> 元素类型，必须实现 {@link Serializable}
 * @author CH
 * @version 1.0.0
 * @see DataStore
 * @see OnHeapDataStore
 * @see Serializer
 */
@Slf4j
public class OffHeapDataStore<E extends Serializable> implements DataStore<E> {

    private final Serializer<E> serializer;
    private final Arena arena;

    /** 写后快照：volatile 引用替换不可变列表，读操作无锁 */
    private volatile List<MemorySegment> segments = Collections.emptyList();

    /** 堆外内存字节统计（无锁累加） */
    private final LongAdder totalBytes = new LongAdder();

    private volatile boolean closed;

    /** 写操作互斥锁，保护 append/close/clear 的原子性 */
    private final Object writeLock = new Object();

    public OffHeapDataStore(Serializer<E> serializer) {
        this.serializer = serializer;
        this.arena = Arena.ofShared();
    }

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

    @Override
    public int size() {
        return segments.size();
    }

    @Override
    public boolean isEmpty() {
        return segments.isEmpty();
    }

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

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isOffHeap() {
        return true;
    }

    @Override
    public long getOffHeapBytes() {
        return totalBytes.sum();
    }

    public int elementCount() {
        return segments.size();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("OffHeapDataStore 已关闭，不可再访问");
        }
    }
}