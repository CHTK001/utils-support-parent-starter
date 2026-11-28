package com.chua.common.support.tree;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * 磁盘分片 B+ 树。索引页采用追加式写入，清单原子切换根页；常规查找只读取
 * 清单定位出的分片及其索引路径，不扫描分片文件。分片达到容量上限时才重建该分片。
 *
 * <p>键和值的编码由调用方提供，编码器标识会写入清单并在重新打开时校验。
 * 此类不使用 Java 原生对象反序列化，避免从磁盘文件执行不受信任的反序列化。</p>
 *
 * <p>每个更新先追加完整的新索引页，再原子替换清单。中断在清单切换之前时，旧根页仍然有效；
 * 未引用的旧页和未完成追加页暂不回收。</p>
 *
 * @param <K> 可比较的键类型
 * @param <V> 值类型
 */
public final class FileTreeEngine<K extends Comparable<K>, V>
        implements TreeEngine<K, V>, AutoCloseable {

    private static final int MANIFEST_MAGIC = 0x46545245;
    private static final int MANIFEST_VERSION = 1;
    private static final int PAGE_MAGIC = 0x46545047;
    private static final int PAGE_SIZE = 64 * 1024;
    private static final int MIN_SHARD_ENTRIES = 4;
    private static final String MANIFEST_NAME = "tree.manifest";
    private static final String MANIFEST_TEMP_NAME = "tree.manifest.tmp";

    private final Path directory;
    private final Path manifestPath;
    private final int order;
    private final int maxEntriesPerShard;
    private final Codec<K> keyCodec;
    private final Codec<V> valueCodec;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final List<Shard> shards = new ArrayList<>();
    private long nextShardId;
    private int size;
    private boolean closed;

    private FileTreeEngine(Path directory, int order, int maxEntriesPerShard,
                           Codec<K> keyCodec, Codec<V> valueCodec) throws IOException {
        if (order < 3 || order > 128) {
            throw new IllegalArgumentException("order 必须在 3 到 128 之间");
        }
        if (maxEntriesPerShard < MIN_SHARD_ENTRIES) {
            throw new IllegalArgumentException("maxEntriesPerShard 必须至少为 4");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.manifestPath = this.directory.resolve(MANIFEST_NAME);
        this.order = order;
        this.maxEntriesPerShard = maxEntriesPerShard;
        this.keyCodec = Objects.requireNonNull(keyCodec, "keyCodec");
        this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec");
        if (keyCodec.id() == null || keyCodec.id().isBlank()
                || valueCodec.id() == null || valueCodec.id().isBlank()) {
            throw new IllegalArgumentException("codec id 不能为空");
        }
        Files.createDirectories(this.directory);
        FileChannel openedLockChannel = FileChannel.open(this.directory.resolve("tree.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquiredLock;
        try {
            acquiredLock = openedLockChannel.tryLock();
        } catch (IOException | OverlappingFileLockException e) {
            openedLockChannel.close();
            throw new IOException("磁盘树目录已被另一个实例打开: " + this.directory, e);
        }
        if (acquiredLock == null) {
            openedLockChannel.close();
            throw new IOException("磁盘树目录已被另一个实例打开: " + this.directory);
        }
        this.lockChannel = openedLockChannel;
        this.directoryLock = acquiredLock;
        try {
            if (Files.exists(manifestPath)) {
                readManifest();
            } else {
                initializeEmpty();
            }
        } catch (IOException | RuntimeException e) {
            try {
                directoryLock.release();
            } finally {
                lockChannel.close();
            }
            throw e;
        }
    }

    /**
     * 打开或创建磁盘树。
     *
     * @param directory 数据目录
     * @param order B+ 树阶数，范围为 3 到 128
     * @param maxEntriesPerShard 分片条目上限，达到上限后在下一次新键写入前拆分
     * @param keyCodec 键编码器
     * @param valueCodec 值编码器
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 已打开的树
     * @throws IOException 打开或创建存储失败
     */
    public static <K extends Comparable<K>, V> FileTreeEngine<K, V> open(
            Path directory, int order, int maxEntriesPerShard,
            Codec<K> keyCodec, Codec<V> valueCodec) throws IOException {
        return new FileTreeEngine<>(Objects.requireNonNull(directory, "directory"),
                order, maxEntriesPerShard, keyCodec, valueCodec);
    }

    /**
     * 打开或创建磁盘树，分片默认最多保存 4096 个键。
     *
     * @param directory 数据目录
     * @param order B+ 树阶数
     * @param keyCodec 键编码器
     * @param valueCodec 值编码器
     * @param <K> 键类型
     * @param <V> 值类型
     * @return 已打开的树
     * @throws IOException 打开或创建存储失败
     */
    public static <K extends Comparable<K>, V> FileTreeEngine<K, V> open(
            Path directory, int order, Codec<K> keyCodec, Codec<V> valueCodec) throws IOException {
        return open(directory, order, 4096, keyCodec, valueCodec);
    }

    /** UTF-8 字符串编码器。 */
    public static Codec<String> stringCodec() {
        return new Codec<>() {
            @Override
            public String id() {
                return "utf8-string-v1";
            }

            @Override
            public byte[] encode(String value) {
                return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }

            @Override
            public String decode(byte[] bytes) {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        };
    }

    /** 64 位有符号整数编码器。 */
    public static Codec<Long> longCodec() {
        return new Codec<>() {
            @Override
            public String id() {
                return "int64-v1";
            }

            @Override
            public byte[] encode(Long value) {
                return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
            }

            @Override
            public Long decode(byte[] bytes) throws IOException {
                if (bytes.length != Long.BYTES) {
                    throw new IOException("非法 long 编码长度: " + bytes.length);
                }
                return ByteBuffer.wrap(bytes).getLong();
            }
        };
    }

    /** 32 位有符号整数编码器。 */
    public static Codec<Integer> integerCodec() {
        return new Codec<>() {
            @Override
            public String id() {
                return "int32-v1";
            }

            @Override
            public byte[] encode(Integer value) {
                return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
            }

            @Override
            public Integer decode(byte[] bytes) throws IOException {
                if (bytes.length != Integer.BYTES) {
                    throw new IOException("非法 integer 编码长度: " + bytes.length);
                }
                return ByteBuffer.wrap(bytes).getInt();
            }
        };
    }

    /** 返回此树的数据目录。 */
    public Path directory() {
        return directory;
    }

    /** 当前分片数量，供容量规划与测试使用。 */
    public synchronized int shardCount() {
        ensureOpen();
        return shards.size();
    }

    @Override
    public String type() {
        return "FILE_SHARDED_B_PLUS_TREE";
    }

    @Override
    public synchronized Optional<V> get(K key) {
        ensureOpen();
        if (key == null) {
            return Optional.empty();
        }
        Shard shard = shards.get(findShardIndex(key));
        Lookup lookup = lookup(shard, key);
        return lookup.found ? Optional.of(lookup.value) : Optional.empty();
    }

    @Override
    public synchronized boolean containsKey(K key) {
        ensureOpen();
        return key != null && lookup(shards.get(findShardIndex(key)), key).found;
    }

    @Override
    public synchronized List<Map.Entry<K, V>> range(K from, K to) {
        ensureOpen();
        if (from != null && to != null && from.compareTo(to) >= 0) {
            return Collections.emptyList();
        }
        List<Map.Entry<K, V>> result = new ArrayList<>();
        int start = from == null ? 0 : findShardIndex(from);
        for (int i = start; i < shards.size(); i++) {
            Shard shard = shards.get(i);
            if (to != null && shard.lowerBound != null && shard.lowerBound.compareTo(to) >= 0) {
                break;
            }
            collectRange(shard, shard.rootPageId, from, to, result);
        }
        return result;
    }

    @Override
    public synchronized Optional<V> put(K key, V value) {
        ensureOpen();
        if (key == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(value, "value");
        encodeValue(keyCodec, key);
        encodeValue(valueCodec, value);
        Shard shard = shards.get(findShardIndex(key));
        Lookup existing = lookup(shard, key);
        if (!existing.found && shard.size >= maxEntriesPerShard) {
            splitShard(shard);
            shard = shards.get(findShardIndex(key));
        }
        if (!existing.found && size == Integer.MAX_VALUE) {
            throw new IllegalStateException("磁盘树条目数超过 Integer.MAX_VALUE");
        }

        InsertResult inserted = insert(shard, shard.rootPageId, key, value);
        long newRoot = inserted.pageId;
        if (inserted.splitKey != null) {
            Page root = new Page(false);
            root.keys.add(inserted.splitKey);
            root.children.add(newRoot);
            root.children.add(inserted.rightPageId);
            newRoot = appendPage(shard, root);
        }
        int newShardSize = shard.size + (inserted.existed ? 0 : 1);
        Shard replacement = new Shard(shard.id, shard.lowerBound, newRoot, newShardSize);
        List<Shard> next = new ArrayList<>(shards);
        next.set(shards.indexOf(shard), replacement);
        persistManifest(next, nextShardId);
        replaceShards(next);
        size += inserted.existed ? 0 : 1;
        return inserted.existed ? Optional.ofNullable(inserted.oldValue) : Optional.empty();
    }

    @Override
    public synchronized Optional<V> remove(K key) {
        ensureOpen();
        if (key == null) {
            return Optional.empty();
        }
        int shardIndex = findShardIndex(key);
        Shard shard = shards.get(shardIndex);
        DeleteResult deleted = delete(shard, shard.rootPageId, key);
        if (!deleted.removed) {
            return Optional.empty();
        }
        long newRoot = deleted.pageId;
        Page root = readPage(shard, newRoot);
        while (!root.leaf && root.children.size() == 1) {
            newRoot = root.children.getFirst();
            root = readPage(shard, newRoot);
        }
        int newShardSize = shard.size - 1;
        boolean removeShard = newShardSize == 0 && shards.size() > 1;
        List<Shard> next = new ArrayList<>(shards);
        if (removeShard) {
            next.remove(shardIndex);
            if (shardIndex == 0) {
                Shard formerSecond = next.getFirst();
                next.set(0, new Shard(formerSecond.id, null,
                        formerSecond.rootPageId, formerSecond.size));
            }
        } else {
            next.set(shardIndex, new Shard(shard.id, shard.lowerBound, newRoot, newShardSize));
        }
        persistManifest(next, nextShardId);
        replaceShards(next);
        size--;
        if (removeShard) {
            deleteShardFile(shard.id);
        }
        return Optional.ofNullable(deleted.value);
    }

    @Override
    public synchronized int size() {
        ensureOpen();
        return size;
    }

    @Override
    public synchronized boolean isEmpty() {
        ensureOpen();
        return size == 0;
    }

    @Override
    public synchronized void clear() {
        ensureOpen();
        Shard empty = createShard(nextShardId, null);
        List<Shard> next = List.of(empty);
        persistManifest(next, nextShardId + 1);
        List<Shard> old = new ArrayList<>(shards);
        replaceShards(next);
        nextShardId++;
        size = 0;
        for (Shard shard : old) {
            deleteShardFile(shard.id);
        }
    }

    /**
     * 按升序将全部条目展开为契约要求的右斜二叉树。
     * 该显式转换会读取所有分片并在堆内存中创建对应节点。
     *
     * @return 二叉树根节点；空树返回空占位节点
     */
    @Override
    public synchronized TreeNode<K, V> toBinaryTree() {
        ensureOpen();
        List<Map.Entry<K, V>> entries = range(null, null);
        if (entries.isEmpty()) {
            return new TreeNode<>();
        }
        Map.Entry<K, V> first = entries.getFirst();
        TreeNode<K, V> root = new TreeNode<>(first.getKey(), first.getValue());
        TreeNode<K, V> current = root;
        for (int i = 1; i < entries.size(); i++) {
            Map.Entry<K, V> entry = entries.get(i);
            TreeNode<K, V> next = new TreeNode<>(entry.getKey(), entry.getValue());
            current.right = next;
            current = next;
        }
        return root;
    }

    /**
     * 将已有契约表示的右斜二叉树导入磁盘树。清空和每条写入都实际提交到磁盘清单。
     *
     * @param root 二叉树根节点
     */
    public synchronized void restore(TreeNode<K, V> root) {
        ensureOpen();
        IdentityHashMap<TreeNode<K, V>, Boolean> visited = new IdentityHashMap<>();
        for (TreeNode<K, V> current = root; current != null; current = current.right) {
            if (visited.put(current, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("二叉树右链存在循环引用");
            }
            if (current.key != null) {
                Objects.requireNonNull(current.value, "磁盘树不支持 null value");
                encodeValue(keyCodec, current.key);
                encodeValue(valueCodec, current.value);
            }
        }
        clear();
        TreeNode<K, V> current = root;
        while (current != null) {
            if (current.key != null) {
                put(current.key, current.value);
            }
            current = current.right;
        }
    }

    /**
     * 回收追加式更新留下的未引用页。每次只重建一个分片，扫描范围受该分片容量约束；
     * 普通查找、插入和删除不会触发全树扫描。
     */
    public synchronized void compact() {
        ensureOpen();
        for (int i = 0; i < shards.size(); i++) {
            Shard old = shards.get(i);
            List<Map.Entry<K, V>> entries = new ArrayList<>();
            collectRange(old, old.rootPageId, null, null, entries);
            long replacementId = nextShardId;
            Shard replacement = buildShard(replacementId, old.lowerBound, entries);
            List<Shard> next = new ArrayList<>(shards);
            next.set(i, replacement);
            persistManifest(next, replacementId + 1);
            replaceShards(next);
            nextShardId = replacementId + 1;
            deleteShardFile(old.id);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            directoryLock.release();
            lockChannel.close();
        } catch (IOException e) {
            throw new UncheckedIOException("关闭磁盘树目录锁失败", e);
        }
    }

    private void initializeEmpty() throws IOException {
        Shard first = createShard(0, null);
        shards.add(first);
        nextShardId = 1;
        size = 0;
        persistManifest(shards, nextShardId);
    }

    private Shard createShard(long id, K lowerBound) {
        Shard shard = new Shard(id, lowerBound, 0, 0);
        try {
            Path path = shardPath(id);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                writeFully(channel, encodePage(new Page(true)), 0);
                channel.force(true);
            }
            return shard;
        } catch (IOException e) {
            throw new UncheckedIOException("创建磁盘树分片失败: " + id, e);
        }
    }

    private void readManifest() throws IOException {
        byte[] file = Files.readAllBytes(manifestPath);
        if (file.length < Integer.BYTES * 3) {
            throw new IOException("磁盘树清单长度无效: " + manifestPath);
        }
        ByteBuffer envelope = ByteBuffer.wrap(file);
        int payloadLength = envelope.getInt();
        if (payloadLength < 0 || payloadLength != file.length - Integer.BYTES * 2) {
            throw new IOException("磁盘树清单长度校验失败: " + manifestPath);
        }
        byte[] payload = new byte[payloadLength];
        envelope.get(payload);
        int expectedCrc = envelope.getInt();
        if (crc(payload) != expectedCrc) {
            throw new IOException("磁盘树清单 CRC 校验失败: " + manifestPath);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MANIFEST_MAGIC || input.readInt() != MANIFEST_VERSION) {
                throw new IOException("磁盘树清单格式不支持: " + manifestPath);
            }
            if (input.readInt() != order || input.readInt() != maxEntriesPerShard
                    || input.readInt() != PAGE_SIZE) {
                throw new IOException("重新打开时必须使用原 order、分片容量和页大小");
            }
            if (!input.readUTF().equals(keyCodec.id()) || !input.readUTF().equals(valueCodec.id())) {
                throw new IOException("重新打开时的键/值编码器与清单不匹配");
            }
            nextShardId = input.readLong();
            size = input.readInt();
            int count = input.readInt();
            if (size < 0 || count < 1 || count > 1_000_000) {
                throw new IOException("磁盘树清单中的分片数量或条目数无效");
            }
            int total = 0;
            for (int i = 0; i < count; i++) {
                K lowerBound = input.readBoolean() ? decodeValue(keyCodec, readBytes(input)) : null;
                long id = input.readLong();
                long rootPageId = input.readLong();
                int shardSize = input.readInt();
                if (id < 0 || rootPageId < 0 || shardSize < 0 || total > Integer.MAX_VALUE - shardSize) {
                    throw new IOException("磁盘树分片元数据无效");
                }
                if (i == 0 && lowerBound != null || i > 0 && lowerBound == null) {
                    throw new IOException("磁盘树分片边界无效");
                }
                if (i > 1 && shards.getLast().lowerBound.compareTo(lowerBound) >= 0) {
                    throw new IOException("磁盘树分片边界未严格递增");
                }
                Path shardFile = shardPath(id);
                if (!Files.exists(shardFile)) {
                    throw new IOException("缺少磁盘树分片文件: " + shardFile);
                }
                shards.add(new Shard(id, lowerBound, rootPageId, shardSize));
                total += shardSize;
            }
            if (input.available() != 0 || total != size) {
                throw new IOException("磁盘树清单条目数不一致");
            }
            long maxId = shards.stream().mapToLong(shard -> shard.id).max().orElse(-1);
            if (nextShardId <= maxId) {
                throw new IOException("磁盘树下一个分片编号无效");
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void persistManifest(List<Shard> target, long targetNextShardId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MANIFEST_MAGIC);
                output.writeInt(MANIFEST_VERSION);
                output.writeInt(order);
                output.writeInt(maxEntriesPerShard);
                output.writeInt(PAGE_SIZE);
                output.writeUTF(keyCodec.id());
                output.writeUTF(valueCodec.id());
                output.writeLong(targetNextShardId);
                int total = target.stream().mapToInt(shard -> shard.size).sum();
                output.writeInt(total);
                output.writeInt(target.size());
                for (Shard shard : target) {
                    output.writeBoolean(shard.lowerBound != null);
                    if (shard.lowerBound != null) {
                        writeBytes(output, encodeValue(keyCodec, shard.lowerBound));
                    }
                    output.writeLong(shard.id);
                    output.writeLong(shard.rootPageId);
                    output.writeInt(shard.size);
                }
            }
            byte[] payload = bytes.toByteArray();
            ByteBuffer envelope = ByteBuffer.allocate(Integer.BYTES + payload.length + Integer.BYTES);
            envelope.putInt(payload.length).put(payload).putInt(crc(payload)).flip();
            Path temporary = directory.resolve(MANIFEST_TEMP_NAME);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                while (envelope.hasRemaining()) {
                    channel.write(envelope);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, manifestPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("提交磁盘树清单失败", e);
        }
    }

    private Lookup lookup(Shard shard, K key) {
        long pageId = shard.rootPageId;
        while (true) {
            Page page = readPage(shard, pageId);
            int index = lowerBound(page.keys, key);
            if (page.leaf) {
                return index < page.keys.size() && page.keys.get(index).compareTo(key) == 0
                        ? new Lookup(true, page.values.get(index)) : new Lookup(false, null);
            }
            pageId = page.children.get(index);
        }
    }

    private InsertResult insert(Shard shard, long pageId, K key, V value) {
        Page page = readPage(shard, pageId);
        int index = lowerBound(page.keys, key);
        if (page.leaf) {
            Page changed = copy(page);
            boolean existed = index < page.keys.size() && page.keys.get(index).compareTo(key) == 0;
            V old = existed ? changed.values.set(index, value) : null;
            if (!existed) {
                changed.keys.add(index, key);
                changed.values.add(index, value);
            }
            if (changed.keys.size() <= order - 1) {
                return new InsertResult(appendPage(shard, changed), -1, -1, existed, old);
            }
            int middle = changed.keys.size() / 2;
            Page left = new Page(true);
            Page right = new Page(true);
            left.keys.addAll(changed.keys.subList(0, middle));
            left.values.addAll(changed.values.subList(0, middle));
            right.keys.addAll(changed.keys.subList(middle, changed.keys.size()));
            right.values.addAll(changed.values.subList(middle, changed.values.size()));
            long leftId = appendPage(shard, left);
            long rightId = appendPage(shard, right);
            return new InsertResult(leftId, left.keys.getLast(), rightId, existed, old);
        }

        InsertResult child = insert(shard, page.children.get(index), key, value);
        Page changed = copy(page);
        changed.children.set(index, child.pageId);
        if (child.splitKey != null) {
            if (index < changed.keys.size()) {
                changed.keys.set(index, child.splitKey);
            } else {
                changed.keys.add(child.splitKey);
            }
            changed.children.add(index + 1, child.rightPageId);
        }
        if (changed.keys.size() <= order - 1) {
            return new InsertResult(appendPage(shard, changed), -1, -1, child.existed, child.oldValue);
        }
        int middle = changed.keys.size() / 2;
        K promoted = changed.keys.get(middle);
        Page left = new Page(false);
        Page right = new Page(false);
        left.keys.addAll(changed.keys.subList(0, middle));
        left.children.addAll(changed.children.subList(0, middle + 1));
        right.keys.addAll(changed.keys.subList(middle + 1, changed.keys.size()));
        right.children.addAll(changed.children.subList(middle + 1, changed.children.size()));
        long leftId = appendPage(shard, left);
        long rightId = appendPage(shard, right);
        return new InsertResult(leftId, promoted, rightId, child.existed, child.oldValue);
    }

    private DeleteResult delete(Shard shard, long pageId, K key) {
        Page page = readPage(shard, pageId);
        int index = lowerBound(page.keys, key);
        if (page.leaf) {
            if (index >= page.keys.size() || page.keys.get(index).compareTo(key) != 0) {
                return new DeleteResult(false, pageId, null);
            }
            Page changed = copy(page);
            V old = changed.values.remove(index);
            changed.keys.remove(index);
            return new DeleteResult(true, appendPage(shard, changed), old);
        }
        DeleteResult child = delete(shard, page.children.get(index), key);
        if (!child.removed) {
            return new DeleteResult(false, pageId, null);
        }
        Page changed = copy(page);
        changed.children.set(index, child.pageId);
        return new DeleteResult(true, appendPage(shard, changed), child.value);
    }

    private void collectRange(Shard shard, long pageId, K from, K to,
                              List<Map.Entry<K, V>> result) {
        Page page = readPage(shard, pageId);
        if (page.leaf) {
            for (int i = 0; i < page.keys.size(); i++) {
                K key = page.keys.get(i);
                if ((from == null || key.compareTo(from) >= 0)
                        && (to == null || key.compareTo(to) < 0)) {
                    result.add(Map.entry(key, page.values.get(i)));
                }
            }
            return;
        }
        int firstChild = from == null ? 0 : lowerBound(page.keys, from);
        for (int i = firstChild; i < page.children.size(); i++) {
            if (i > 0 && to != null && page.keys.get(i - 1).compareTo(to) >= 0) {
                break;
            }
            collectRange(shard, page.children.get(i), from, to, result);
        }
    }

    private void splitShard(Shard shard) {
        List<Map.Entry<K, V>> entries = new ArrayList<>();
        collectRange(shard, shard.rootPageId, null, null, entries);
        if (entries.size() < 2) {
            throw new IllegalStateException("分片条目不足，无法拆分: " + shard.id);
        }
        int middle = entries.size() / 2;
        long leftId = nextShardId;
        long rightId = leftId + 1;
        Shard left = buildShard(leftId, shard.lowerBound, entries.subList(0, middle));
        Shard right = buildShard(rightId, entries.get(middle).getKey(),
                entries.subList(middle, entries.size()));
        int index = shards.indexOf(shard);
        List<Shard> next = new ArrayList<>(shards);
        next.remove(index);
        next.add(index, right);
        next.add(index, left);
        persistManifest(next, rightId + 1);
        replaceShards(next);
        nextShardId = rightId + 1;
        deleteShardFile(shard.id);
    }

    private Shard buildShard(long id, K lowerBound, List<Map.Entry<K, V>> entries) {
        Shard shard = createShard(id, lowerBound);
        long root = shard.rootPageId;
        int count = 0;
        for (Map.Entry<K, V> entry : entries) {
            InsertResult inserted = insert(shard, root, entry.getKey(), entry.getValue());
            root = inserted.pageId;
            if (inserted.splitKey != null) {
                Page newRoot = new Page(false);
                newRoot.keys.add(inserted.splitKey);
                newRoot.children.add(root);
                newRoot.children.add(inserted.rightPageId);
                root = appendPage(shard, newRoot);
            }
            count++;
        }
        return new Shard(id, lowerBound, root, count);
    }

    private int findShardIndex(K key) {
        int low = 0;
        int high = shards.size() - 1;
        int result = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            K lowerBound = shards.get(middle).lowerBound;
            if (lowerBound == null || lowerBound.compareTo(key) <= 0) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private Page readPage(Shard shard, long pageId) {
        if (pageId < 0) {
            throw new IllegalStateException("非法磁盘树页号: " + pageId);
        }
        ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE);
        try (FileChannel channel = FileChannel.open(shardPath(shard.id), StandardOpenOption.READ)) {
            readFully(channel, buffer, Math.multiplyExact(pageId, (long) PAGE_SIZE));
        } catch (IOException e) {
            throw new UncheckedIOException("读取磁盘树页失败: shard=" + shard.id + ", page=" + pageId, e);
        }
        byte[] encoded = buffer.array();
        ByteBuffer crcBuffer = ByteBuffer.wrap(encoded, PAGE_SIZE - Integer.BYTES, Integer.BYTES);
        int expectedCrc = crcBuffer.getInt();
        CRC32 crc = new CRC32();
        crc.update(encoded, 0, PAGE_SIZE - Integer.BYTES);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IllegalStateException("磁盘树页 CRC 校验失败: shard=" + shard.id + ", page=" + pageId);
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded, 0, PAGE_SIZE - Integer.BYTES))) {
            if (input.readInt() != PAGE_MAGIC) {
                throw new IOException("磁盘树页魔数无效");
            }
            boolean leaf = input.readBoolean();
            int count = input.readInt();
            if (count < 0 || count > order) {
                throw new IOException("磁盘树页键数量无效: " + count);
            }
            Page page = new Page(leaf);
            for (int i = 0; i < count; i++) {
                page.keys.add(decodeValue(keyCodec, readBytes(input)));
            }
            if (leaf) {
                for (int i = 0; i < count; i++) {
                    page.values.add(decodeValue(valueCodec, readBytes(input)));
                }
            } else {
                for (int i = 0; i <= count; i++) {
                    page.children.add(input.readLong());
                }
            }
            return page;
        } catch (IOException e) {
            throw new UncheckedIOException("解码磁盘树页失败: shard=" + shard.id + ", page=" + pageId, e);
        }
    }

    private long appendPage(Shard shard, Page page) {
        byte[] encoded = encodePage(page);
        try (FileChannel channel = FileChannel.open(shardPath(shard.id), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long bytes = channel.size();
            long pageId = (bytes + PAGE_SIZE - 1) / PAGE_SIZE;
            writeFully(channel, ByteBuffer.wrap(encoded), Math.multiplyExact(pageId, (long) PAGE_SIZE));
            channel.force(false);
            return pageId;
        } catch (IOException e) {
            throw new UncheckedIOException("追加磁盘树页失败: shard=" + shard.id, e);
        }
    }

    private byte[] encodePage(Page page) {
        try {
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
                output.writeInt(PAGE_MAGIC);
                output.writeBoolean(page.leaf);
                output.writeInt(page.keys.size());
                for (K key : page.keys) {
                    writeBytes(output, encodeValue(keyCodec, key));
                }
                if (page.leaf) {
                    for (V value : page.values) {
                        writeBytes(output, encodeValue(valueCodec, value));
                    }
                } else {
                    for (long child : page.children) {
                        output.writeLong(child);
                    }
                }
            }
            byte[] body = bodyBytes.toByteArray();
            if (body.length > PAGE_SIZE - Integer.BYTES) {
                throw new IllegalStateException("磁盘树页容量超限；请减小 order 或键值编码大小");
            }
            byte[] pageBytes = new byte[PAGE_SIZE];
            System.arraycopy(body, 0, pageBytes, 0, body.length);
            CRC32 crc = new CRC32();
            crc.update(pageBytes, 0, PAGE_SIZE - Integer.BYTES);
            ByteBuffer.wrap(pageBytes, PAGE_SIZE - Integer.BYTES, Integer.BYTES)
                    .putInt((int) crc.getValue());
            return pageBytes;
        } catch (IOException e) {
            throw new UncheckedIOException("编码磁盘树页失败", e);
        }
    }

    private Page copy(Page source) {
        Page result = new Page(source.leaf);
        result.keys.addAll(source.keys);
        result.values.addAll(source.values);
        result.children.addAll(source.children);
        return result;
    }

    private int lowerBound(List<K> keys, K key) {
        int low = 0;
        int high = keys.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle).compareTo(key) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private byte[] encodeValue(Codec<?> codec, Object value) {
        try {
            @SuppressWarnings("unchecked")
            Codec<Object> typedCodec = (Codec<Object>) codec;
            byte[] bytes = Objects.requireNonNull(typedCodec.encode(value), "codec returned null");
            if (bytes.length > PAGE_SIZE - Integer.BYTES * 8) {
                throw new IllegalStateException("键/值编码超过单页可容纳上限");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("键/值编码失败", e);
        }
    }

    private <T> T decodeValue(Codec<T> codec, byte[] bytes) {
        try {
            return Objects.requireNonNull(codec.decode(bytes), "codec decoded null");
        } catch (IOException e) {
            throw new UncheckedIOException("键/值解码失败", e);
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > PAGE_SIZE) {
            throw new IOException("磁盘树字段长度无效: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("磁盘树字段数据不完整");
        }
        return bytes;
    }

    private Path shardPath(long id) {
        return directory.resolve("shard-%020d.pages".formatted(id));
    }

    private void deleteShardFile(long id) {
        try {
            Files.deleteIfExists(shardPath(id));
        } catch (IOException ignored) {
            // 未引用分片仅占用磁盘空间，不影响清单中当前树的一致性。
        }
    }

    private void replaceShards(List<Shard> replacement) {
        shards.clear();
        shards.addAll(replacement);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("磁盘树已经关闭");
        }
    }

    private static int crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("磁盘树页不完整");
            }
            if (read == 0) {
                continue;
            }
            position += read;
        }
    }

    /** 用户提供的确定性键/值编码契约。 */
    public interface Codec<T> {
        /** 清单中用于兼容性校验的稳定版本标识。 */
        String id();

        /** 将一个非 null 值编码为字节。 */
        byte[] encode(T value) throws IOException;

        /** 从编码字节恢复一个非 null 值。 */
        T decode(byte[] bytes) throws IOException;
    }

    private final class Page {
        private final boolean leaf;
        private final List<K> keys = new ArrayList<>();
        private final List<V> values = new ArrayList<>();
        private final List<Long> children = new ArrayList<>();

        private Page(boolean leaf) {
            this.leaf = leaf;
        }
    }

    private final class Shard {
        private final long id;
        private final K lowerBound;
        private final long rootPageId;
        private final int size;

        private Shard(long id, K lowerBound, long rootPageId, int size) {
            this.id = id;
            this.lowerBound = lowerBound;
            this.rootPageId = rootPageId;
            this.size = size;
        }
    }

    private final class Lookup {
        private final boolean found;
        private final V value;

        private Lookup(boolean found, V value) {
            this.found = found;
            this.value = value;
        }
    }

    private final class InsertResult {
        private final long pageId;
        private final K splitKey;
        private final long rightPageId;
        private final boolean existed;
        private final V oldValue;

        private InsertResult(long pageId, K splitKey, long rightPageId, boolean existed, V oldValue) {
            this.pageId = pageId;
            this.splitKey = splitKey;
            this.rightPageId = rightPageId;
            this.existed = existed;
            this.oldValue = oldValue;
        }
    }

    private final class DeleteResult {
        private final boolean removed;
        private final long pageId;
        private final V value;

        private DeleteResult(boolean removed, long pageId, V value) {
            this.removed = removed;
            this.pageId = pageId;
            this.value = value;
        }
    }
}
