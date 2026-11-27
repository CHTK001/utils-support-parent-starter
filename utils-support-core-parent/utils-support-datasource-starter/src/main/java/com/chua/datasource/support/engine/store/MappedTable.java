package com.chua.datasource.support.engine.store;

import com.chua.common.support.lang.json.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * 单张表的映射文件承载：记录追加、墓碑删除、偏移索引与 {@code .idx} 侧车文件。
 *
 * <p>本类只被 {@link MappedFileTableStore} 与 {@link MappedRows} 使用，所有外部可见
 * 操作都在表级互斥锁内执行；映射段由 {@link Arena} 持有，关闭时统一释放
 * （JDK 25 在 Windows 上需先解映射再截断文件）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
final class MappedTable {

    /**
     * 单段映射大小（记录不跨段）。
     */
    static final int SEGMENT_SIZE = 1 << 20;

    /**
     * 记录头长度：magic + payload 长度。
     */
    static final int HEADER = 8;

    /**
     * 记录头魔数（"CHUA"）。
     */
    static final int MAGIC = 0x43485541;

    /**
     * 索引文件魔数（"ENGI"）。
     */
    static final int INDEX_MAGIC = 0x454E4749;

    /**
     * 索引文件版本。
     */
    static final int INDEX_VERSION = 1;

    /**
     * payload 首字节编码方式：JDK 序列化。
     */
    static final byte TAG_JAVA = 1;

    /**
     * payload 首字节编码方式：JSON + 类名兜底。
     */
    static final byte TAG_JSON = 2;

    /**
     * payload 首字节编码方式：墓碑（已删除）。
     */
    static final byte TAG_DELETE = 3;

    /**
     * 单条记录 payload 上限（记录不得跨段）。
     */
    static final int MAX_PAYLOAD = SEGMENT_SIZE - HEADER;

    /**
     * 数据文件后缀。
     */
    static final String DATA_SUFFIX = ".dat";

    /**
     * 索引文件后缀。
     */
    static final String INDEX_SUFFIX = ".idx";

    /**
     * 表名。
     */
    private final String table;

    /**
     * 数据文件路径。
     */
    private final Path dataPath;

    /**
     * 索引文件路径。
     */
    private final Path indexPath;

    /**
     * 数据文件句柄（通道独立管理，解映射只关 {@link Arena}）。
     */
    private final RandomAccessFile file;

    /**
     * 数据文件通道。
     */
    private final FileChannel channel;

    /**
     * 已映射段，下标即段号。
     */
    private final List<Segment> segments = new ArrayList<>();

    /**
     * 对外暴露的行列表（同一表恒为同一实例）。
     */
    private final MappedRows rows;

    /**
     * 行偏移索引：{@code offsets[0..count)} 有效。
     */
    private volatile long[] offsets = new long[16];

    /**
     * 有效行数。
     */
    private volatile int count;

    /**
     * 下一条追加记录的起始偏移。
     */
    private long writePos;

    /**
     * 数据文件当前长度（预扩后的长度）。
     */
    private long fileLength;

    /**
     * 是否已关闭。
     */
    private boolean closed;

    MappedTable(String table, Path directory) throws IOException {
        this.table = table;
        String base = fileName(table);
        this.dataPath = directory.resolve(base + DATA_SUFFIX);
        this.indexPath = directory.resolve(base + INDEX_SUFFIX);
        this.file = new RandomAccessFile(dataPath.toFile(), "rw");
        this.channel = file.getChannel();
        this.fileLength = file.length();
        if (!loadIndex()) {
            replay();
        }
        this.rows = new MappedRows(this);
    }

    /**
     * 由表名生成稳定、可读、避免非法字符的文件名。
     *
     * @param table 表名
     * @return 文件基本名
     */
    static String fileName(String table) {
        StringBuilder sb = new StringBuilder(table.length());
        for (int i = 0; i < table.length() && sb.length() < 64; i++) {
            char c = table.charAt(i);
            boolean safe = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.';
            sb.append(safe ? c : '_');
        }
        if (sb.length() == 0) {
            sb.append("table");
        }
        return sb + "-" + Integer.toHexString(table.hashCode());
    }

    /**
     * 对外列表视图。
     *
     * @return 行列表
     */
    MappedRows rows() {
        return rows;
    }

    /**
     * 有效行数。
     *
     * @return 行数
     */
    int size() {
        return count;
    }

    /**
     * 读取一行（返回反序列化后的副本）。
     *
     * @param index 下标
     * @return 行对象
     */
    synchronized Object get(int index) {
        checkIndex(index);
        try {
            return decode(readPayload(offsets[index]));
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("读取内存映射表失败: " + table + "[" + index + "]", e);
        }
    }

    /**
     * 覆盖一行。
     *
     * <p>新记录与旧记录 payload 等长时原地覆盖，否则追加新记录并把旧位置改成墓碑；
     * 两条路径都保证「先把新内容落盘，再更新偏移」，不会出现读到半条新行的窗口。</p>
     *
     * @param index   下标
     * @param element 新行
     * @return 旧行
     */
    synchronized Object set(int index, Object element) {
        checkIndex(index);
        Object previous = get(index);
        try {
            byte[] payload = encode(element);
            long position = offsets[index];
            if (payload.length == payloadLength(position)) {
                writePayload(position, payload);
            } else {
                tombstone(position);
                offsets[index] = append(payload);
            }
            return previous;
        } catch (IOException e) {
            throw new IllegalStateException("写入内存映射表失败: " + table + "[" + index + "]", e);
        }
    }

    /**
     * 在指定位置插入一行（物理追加 + 索引移位）。
     *
     * @param index   插入位置
     * @param element 行
     */
    synchronized void add(int index, Object element) {
        if (index < 0 || index > count) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + count);
        }
        try {
            long position = append(encode(element));
            ensureOffsetCapacity(count + 1);
            long[] current = offsets;
            System.arraycopy(current, index, current, index + 1, count - index);
            current[index] = position;
            count++;
        } catch (IOException e) {
            throw new IllegalStateException("写入内存映射表失败: " + table + "[" + index + "]", e);
        }
    }

    /**
     * 批量追加行。
     *
     * @param elements 行集合
     */
    synchronized void addAll(Collection<?> elements) {
        for (Object element : elements) {
            add(count, element);
        }
    }

    /**
     * 删除指定位置的行（旧位置写墓碑 + 索引左移）。
     *
     * @param index 下标
     * @return 被删除的行
     */
    synchronized Object removeAt(int index) {
        checkIndex(index);
        Object previous = get(index);
        try {
            tombstone(offsets[index]);
        } catch (IOException e) {
            throw new IllegalStateException("删除内存映射表行失败: " + table + "[" + index + "]", e);
        }
        long[] current = offsets;
        System.arraycopy(current, index + 1, current, index, count - index - 1);
        count--;
        return previous;
    }

    /**
     * 清空整张表：释放映射、截断文件、重置索引。
     */
    synchronized void clear() {
        try {
            releaseSegments();
            channel.truncate(0);
            file.setLength(0);
        } catch (IOException e) {
            throw new IllegalStateException("清空内存映射表失败: " + table, e);
        }
        fileLength = 0L;
        writePos = 0L;
        count = 0;
        offsets = new long[16];
    }

    /**
     * 按谓词删除行。
     *
     * @param filter 谓词
     * @return 是否有行被删除
     */
    synchronized boolean removeIfRows(Predicate<Object> filter) {
        long[] kept = new long[count];
        int keptCount = 0;
        boolean changed = false;
        for (int i = 0; i < count; i++) {
            Object row;
            try {
                row = decode(readPayload(offsets[i]));
            } catch (IOException | ClassNotFoundException e) {
                throw new IllegalStateException("读取内存映射表失败: " + table + "[" + i + "]", e);
            }
            if (filter.test(row)) {
                changed = true;
                try {
                    tombstone(offsets[i]);
                } catch (IOException e) {
                    throw new IllegalStateException("删除内存映射表行失败: " + table + "[" + i + "]", e);
                }
            } else {
                kept[keptCount++] = offsets[i];
            }
        }
        if (changed) {
            offsets = kept;
            count = keptCount;
        }
        return changed;
    }

    /**
     * 关闭表。
     *
     * @param purge true 表示该表已从引擎摘除（删文件）；false 表示引擎关闭（保留文件供下次恢复）
     */
    synchronized void close(boolean purge) {
        if (closed) {
            return;
        }
        closed = true;
        try {
            releaseSegments();
            if (purge) {
                return;
            }
            channel.force(false);
            writeIndex();
        } catch (IOException e) {
            throw new IllegalStateException("关闭内存映射表失败: " + table, e);
        } finally {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭期异常不覆盖主流程结论
            }
            try {
                file.close();
            } catch (IOException ignored) {
                // 同上
            }
            if (purge) {
                deleteQuietly(dataPath);
                deleteQuietly(indexPath);
            }
        }
    }

    /**
     * 校验下标。
     *
     * @param index 下标
     */
    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + count);
        }
    }

    /**
     * 确保偏移数组容量。
     *
     * @param required 需要的容量
     */
    private void ensureOffsetCapacity(int required) {
        long[] current = offsets;
        if (required <= current.length) {
            return;
        }
        int capacity = Math.max(required, current.length << 1);
        long[] grown = new long[capacity];
        System.arraycopy(current, 0, grown, 0, count);
        offsets = grown;
    }

    /**
     * 追加一条 payload 记录并返回其绝对偏移。
     *
     * @param payload 记录体
     * @return 记录起始偏移
     */
    private long append(byte[] payload) throws IOException {
        int total = HEADER + payload.length;
        if (total > SEGMENT_SIZE) {
            throw new IllegalArgumentException("单行序列化后 " + total + " 字节，超过分段上限 "
                    + SEGMENT_SIZE + "（表 " + table + "）");
        }
        long position = writePos;
        if ((position % SEGMENT_SIZE) + total > SEGMENT_SIZE) {
            position = ((position / SEGMENT_SIZE) + 1) * SEGMENT_SIZE;
        }
        ensureMapped(position + total);
        ByteBuffer buffer = segments.get((int) (position / SEGMENT_SIZE)).buffer();
        int base = (int) (position % SEGMENT_SIZE);
        buffer.putInt(base, MAGIC);
        buffer.putInt(base + 4, payload.length);
        buffer.put(base + HEADER, payload);
        writePos = position + total;
        return position;
    }

    /**
     * 读取记录 payload 长度。
     *
     * @param position 记录起始偏移
     * @return payload 长度
     */
    private int payloadLength(long position) throws IOException {
        ByteBuffer buffer = bufferAt(position);
        return buffer.getInt((int) (position % SEGMENT_SIZE) + 4);
    }

    /**
     * 读取记录 payload。
     *
     * @param position 记录起始偏移
     * @return payload 字节
     */
    private byte[] readPayload(long position) throws IOException {
        ByteBuffer buffer = bufferAt(position);
        int base = (int) (position % SEGMENT_SIZE);
        int length = buffer.getInt(base + 4);
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("记录长度异常: " + length + "（表 " + table + "）");
        }
        byte[] payload = new byte[length];
        buffer.get(base + HEADER, payload);
        return payload;
    }

    /**
     * 原地覆盖 payload。
     *
     * @param position 记录起始偏移
     * @param payload  新 payload
     */
    private void writePayload(long position, byte[] payload) throws IOException {
        ByteBuffer buffer = bufferAt(position);
        buffer.put((int) (position % SEGMENT_SIZE) + HEADER, payload);
    }

    /**
     * 把记录标记为墓碑（只改 payload 首字节，保持记录长度可跨越）。
     *
     * @param position 记录起始偏移
     */
    private void tombstone(long position) throws IOException {
        ByteBuffer buffer = bufferAt(position);
        buffer.put((int) (position % SEGMENT_SIZE) + HEADER, TAG_DELETE);
    }

    /**
     * 取指定偏移所在段的缓冲区。
     *
     * @param position 绝对偏移
     * @return 缓冲区
     */
    private ByteBuffer bufferAt(long position) throws IOException {
        ensureMapped(position + HEADER + 1);
        return segments.get((int) (position / SEGMENT_SIZE)).buffer();
    }

    /**
     * 按需预扩文件并映射段。
     *
     * @param end 需要可写到的结束偏移（不含）
     */
    private void ensureMapped(long end) throws IOException {
        if (end > fileLength) {
            long target = Math.max(end, fileLength + SEGMENT_SIZE);
            target = ((target + SEGMENT_SIZE - 1) / SEGMENT_SIZE) * SEGMENT_SIZE;
            file.setLength(target);
            fileLength = target;
        }
        int required = (int) ((end + SEGMENT_SIZE - 1) / SEGMENT_SIZE);
        while (segments.size() < required) {
            int index = segments.size();
            Arena arena = Arena.ofShared();
            MemorySegment segment;
            try {
                segment = channel.map(FileChannel.MapMode.READ_WRITE, (long) index * SEGMENT_SIZE,
                        SEGMENT_SIZE, arena);
            } catch (IOException | RuntimeException e) {
                arena.close();
                throw e;
            }
            segments.add(new Segment(arena, segment.asByteBuffer()));
        }
    }

    /**
     * 释放全部映射段（Windows 下截断/删除文件前必须调用）。
     */
    private void releaseSegments() {
        for (Segment segment : segments) {
            segment.arena().close();
        }
        segments.clear();
    }

    /**
     * 按物理追加顺序 + 墓碑重放数据文件，重建偏移索引。
     */
    private void replay() throws IOException {
        long position = 0L;
        int size = 0;
        while (fileLength - position >= HEADER) {
            ensureMapped(position + HEADER);
            ByteBuffer buffer = segments.get((int) (position / SEGMENT_SIZE)).buffer();
            int base = (int) (position % SEGMENT_SIZE);
            if ((long) base + HEADER + 1 > SEGMENT_SIZE) {
                position = ((position / SEGMENT_SIZE) + 1) * SEGMENT_SIZE;
                continue;
            }
            int magic = buffer.getInt(base);
            if (magic != MAGIC) {
                position = ((position / SEGMENT_SIZE) + 1) * SEGMENT_SIZE;
                continue;
            }
            int length = buffer.getInt(base + 4);
            if (length < 0 || length > MAX_PAYLOAD || (long) base + HEADER + length > SEGMENT_SIZE) {
                position = ((position / SEGMENT_SIZE) + 1) * SEGMENT_SIZE;
                continue;
            }
            byte tag = buffer.get(base + HEADER);
            if (tag != TAG_DELETE) {
                ensureOffsetCapacity(size + 1);
                offsets[size++] = position;
            }
            position += HEADER + length;
        }
        count = size;
        writePos = position;
    }

    /**
     * 读取索引侧车文件；校验失败返回 false 交由重放兜底。
     *
     * @return true 表示索引可用
     */
    private boolean loadIndex() {
        if (!Files.isRegularFile(indexPath)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(indexPath);
             DataInputStream data = new DataInputStream(in)) {
            if (data.readInt() != INDEX_MAGIC || data.readInt() != INDEX_VERSION) {
                return false;
            }
            int size = data.readInt();
            long savedWritePos = data.readLong();
            long savedLength = data.readLong();
            if (size < 0 || savedLength != fileLength || savedWritePos < 0 || savedWritePos > fileLength) {
                return false;
            }
            long[] loaded = new long[Math.max(16, size)];
            for (int i = 0; i < size; i++) {
                loaded[i] = data.readLong();
            }
            offsets = loaded;
            count = size;
            writePos = savedWritePos;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 写出索引侧车文件。
     */
    private void writeIndex() throws IOException {
        try (OutputStream out = Files.newOutputStream(indexPath);
             DataOutputStream data = new DataOutputStream(out)) {
            data.writeInt(INDEX_MAGIC);
            data.writeInt(INDEX_VERSION);
            data.writeInt(count);
            data.writeLong(writePos);
            data.writeLong(fileLength);
            for (int i = 0; i < count; i++) {
                data.writeLong(offsets[i]);
            }
        }
    }

    /**
     * 删除文件，失败不影响关闭结论。
     *
     * @param path 文件
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 文件由进程外部清理
        }
    }

    /**
     * 行编码：可序列化行优先 JDK 序列化（保真），否则 JSON + 类名。
     *
     * @param row 行
     * @return payload
     */
    static byte[] encode(Object row) {
        byte[] java = serialize(row);
        if (java != null) {
            byte[] payload = new byte[java.length + 1];
            payload[0] = TAG_JAVA;
            System.arraycopy(java, 0, payload, 1, java.length);
            return payload;
        }
        byte[] name = row.getClass().getName().getBytes(StandardCharsets.UTF_8);
        byte[] json = Json.toJsonByte(row);
        ByteBuffer buffer = ByteBuffer.allocate(1 + Integer.BYTES + name.length + json.length);
        buffer.put(TAG_JSON);
        buffer.putInt(name.length);
        buffer.put(name);
        buffer.put(json);
        return buffer.array();
    }

    /**
     * 行解码。
     *
     * @param payload 记录体
     * @return 行
     */
    static Object decode(byte[] payload) throws IOException, ClassNotFoundException {
        byte tag = payload[0];
        if (tag == TAG_JAVA) {
            try (ObjectInputStream in = new LoaderAwareObjectInputStream(
                    new ByteArrayInputStream(payload, 1, payload.length - 1))) {
                return in.readObject();
            }
        }
        if (tag == TAG_JSON) {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            buffer.position(1);
            int nameLength = buffer.getInt();
            byte[] name = new byte[nameLength];
            buffer.get(name);
            byte[] json = new byte[buffer.remaining()];
            buffer.get(json);
            Class<?> type;
            try {
                type = Class.forName(new String(name, StandardCharsets.UTF_8), false, classLoader());
            } catch (ClassNotFoundException e) {
                throw new IOException("JSON 行类型不可用: " + new String(name, StandardCharsets.UTF_8), e);
            }
            return Json.fromJson(json, type);
        }
        throw new IOException("未知的行编码标记: " + tag);
    }

    /**
     * 尝试 JDK 序列化。
     *
     * @param row 行
     * @return 序列化字节，不可序列化时返回 null
     */
    private static byte[] serialize(Object row) {
        if (row != null && !(row instanceof Serializable)) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try (ObjectOutputStream stream = new ObjectOutputStream(out)) {
            stream.writeObject(row);
        } catch (IOException e) {
            return null;
        }
        return out.toByteArray();
    }

    /**
     * 行类型解析使用的类加载器。
     *
     * @return 类加载器
     */
    private static ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? MappedTable.class.getClassLoader() : loader;
    }

    /**
     * 一段映射：Arena 负责解映射，缓冲区负责读写。
     *
     * @param arena  映射生命周期
     * @param buffer 段缓冲区
     */
    private record Segment(Arena arena, ByteBuffer buffer) {
    }

    /**
     * 反序列化时使用上下文类加载器，兼容宿主自定义类型。
     */
    private static final class LoaderAwareObjectInputStream extends ObjectInputStream {

        LoaderAwareObjectInputStream(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(java.io.ObjectStreamClass desc)
                throws IOException, ClassNotFoundException {
            try {
                return Class.forName(desc.getName(), false, classLoader());
            } catch (ClassNotFoundException e) {
                return super.resolveClass(desc);
            }
        }
    }
}
