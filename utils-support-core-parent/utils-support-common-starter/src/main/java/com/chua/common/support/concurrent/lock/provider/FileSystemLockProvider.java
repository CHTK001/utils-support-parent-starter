package com.chua.common.support.concurrent.lock.provider;


import com.chua.common.support.concurrent.lock.AbstractLockProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullUnmarked;

/**
 * 基于文件系统的锁提供者实现。
 * 该类利用操作系统的文件锁定机制来实现分布式或进程间的互斥锁。
 * 它通过创建和锁定一个特定的文件来确保同一时间只有一个线程或进程能够获取该锁。
 *
 * @author CH
 * @since 2022-05-27
 */
@Spi("filesystem")
@SuppressWarnings("NullAway")
@NullUnmarked
public class FileSystemLockProvider extends AbstractLockProvider {

    /**
     * 锁的名称，用于标识唯一的锁资源。
     */
    private final String name;

    /**
     * 用于存储锁状态的物理文件对象。
     */
    private final File file;

    /**
     * 默认的文件路径，默认为当前用户的工作目录 (user.dir)。
     */
    private static final String DEFAULT_PATH = System.getProperty("user.dir");

    /**
     * 用于读写文件的随机访问文件流。
     * 在获取锁时打开，释放锁时关闭。
     */
    private RandomAccessFile randomAccessFile;

    /**
     * 文件通道，用于执行非阻塞的锁操作。
     */
    private FileChannel fileChannel;

    /**
     * 当前持有的文件锁对象。
     * 如果为 null，表示未持有锁。
     */
    private FileLock fileLock;

    /**
     * 无参构造函数，使用默认的锁名称 "default"。
     */
    public FileSystemLockProvider() {
        this("default");
    }

    /**
     * 根据指定的名称创建锁提供者。
     * 默认将锁文件创建在当前工作目录下，文件名为 "{name}.lock"。
     *
     * @param name 锁的唯一标识名称。
     */
    public FileSystemLockProvider(String name) {
        this(name, new File(DEFAULT_PATH + "/" + name + ".lock"));
    }

    /**
     * 根据指定的名称和文件路径创建锁提供者。
     * 允许自定义锁文件的存储位置。
     *
     * @param name 锁的唯一标识名称。
     * @param file 用于存储锁状态的目标文件。
     */
    public FileSystemLockProvider(String name, File file) {
        this.name = name;
        this.file = file;
    }

    /**
     * 尝试获取锁。
     * 该方法会先尝试立即获取锁，如果失败则进入循环等待，直到达到超时时间。
     *
     * @param timeout 等待锁的最大时长。
     * @param timeUnit 时间的单位（如毫秒、秒等）。
     * @return 如果成功获取锁返回 true，否则在超时后返回 false。
     */
    @Override
    protected boolean doTryLock(int timeout, TimeUnit timeUnit) {
        try {
            // 初始化并打开文件资源
            openResources();
            
            // 将超时时间转换为毫秒
            long timeUnitMillis = timeUnit.toMillis(timeout);
            
            // 尝试立即获取独占锁（从文件开头到最大长度）
            fileLock = fileChannel.tryLock(0, Long.MAX_VALUE, false);
            
            // 如果立即获取失败，进入等待循环
            if (fileLock == null) {
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < timeUnitMillis) {
                    // 再次尝试获取锁
                    fileLock = fileChannel.tryLock(0, Long.MAX_VALUE, false);
                    if (fileLock != null) {
                        return true;
                    }
                    // 每次重试前休眠一小段时间，避免 CPU 空转
                    ThreadUtils.sleep(100);
                }
            } else {
                // 立即获取成功
                return true;
            }

            // 超时仍未获取到锁
            return false;
        } catch (IOException e) {
            handleException(e);
            return false;
        }
    }

    /**
     * 释放锁并清理相关资源。
     * 依次执行释放文件锁、关闭文件通道和文件流、标记删除临时文件的操作。
     */
    @Override
    protected void doUnlock() {
        try {
            releaseLock();
            closeResources();
            releaseFile();
        } catch (IOException e) {
            handleException(e);
        }
    }

    /**
     * 获取锁的名称。
     *
     * @return 锁的名称字符串。
     */
    @Override
    protected String doGetName() {
        return name;
    }

    @Override
    protected String doGetType() {
        return "filesystem";
    }

    /**
     * 打开文件资源，包括创建 RandomAccessFile 和获取 FileChannel。
     * 如果文件不存在，会自动创建。
     *
     * @throws IOException 如果打开文件失败。
     */
    private void openResources() throws IOException {
        randomAccessFile = new RandomAccessFile(file, "rw");
        fileChannel = randomAccessFile.getChannel();
    }

    /**
     * 关闭已打开的文件资源。
     * 安全地关闭 FileChannel 和 RandomAccessFile，防止空指针异常。
     *
     * @throws IOException 如果关闭文件失败。
     */
    private void closeResources() throws IOException {
        if (fileChannel != null && fileChannel.isOpen()) {
            fileChannel.close();
        }
        if (randomAccessFile != null) {
            randomAccessFile.close();
        }
    }

    /**
     * 标记锁文件在 JVM 退出时自动删除。
     * 这是一种清理机制，防止产生多余的临时锁文件。
     */
    private void releaseFile() {
        try {
            file.deleteOnExit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 释放当前持有的文件锁。
     * 如果之前没有获取到锁（fileLock 为 null），则不执行任何操作。
     *
     * @throws IOException 如果释放锁失败。
     */
    private void releaseLock() throws IOException {
        if (fileLock != null) {
            fileLock.release();
        }
    }

    /**
     * 处理 IO 或其他异常。
     * 当前实现仅打印堆栈跟踪信息。
     *
     * @param e 捕获到的异常对象。
     */
    private void handleException(Exception e) {
        e.printStackTrace();
    }
}
