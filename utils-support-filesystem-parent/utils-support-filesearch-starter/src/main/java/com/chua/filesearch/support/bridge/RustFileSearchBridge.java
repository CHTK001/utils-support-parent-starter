package com.chua.filesearch.support.bridge;

import java.util.function.Consumer;

/**
 * Rust 文件搜索本地库桥接（JNI）。
 *
 * <p>封装对 Rust 实现的文件搜索动态库（file_search）的加载与调用，
 * 提供按名称、按大小、按路径搜索与目录树遍历四类能力，
 * 搜索结果通过 {@link Consumer} 回调逐条返回。</p>
 *
 * <p><b>使用约定：</b></p>
 * <ol>
 *   <li>调用任何搜索方法前必须先调用 {@link #loadLibrary()} 加载本地库</li>
 *   <li>通过 {@link #isLoaded()} 判断加载是否成功，未加载时不应调用 native 方法</li>
 * </ol>
 *
 * @author CH
 */
public final class RustFileSearchBridge {

    /** 本地库是否已成功加载 */
    private static volatile boolean loaded = false;

    /** 尝试加载的库名称 */
    private static final String LIBRARY_NAME = "file_search";

    /**
     * 私有构造，仅暴露静态方法。
     */
    private RustFileSearchBridge() {
    }

    /**
     * 文件搜索结果数据载体
     *
     * @param path          文件完整路径
     * @param size          文件大小（字节）
     * @param lastModified  最后修改时间（epoch 毫秒）
     * @param isDirectory   是否为目录
     * @param extension     扩展名（不含点号，可为 null）
     * @param attributes    Windows 文件属性位掩码
     * @param usnRecordId   NTFS USN 记录 ID
     * @param parentFileId  父目录文件 ID
     * @param allocatedSize 分配大小（字节）
     * @author CH
     */
    public record FileResultData(
            String path,
            long size,
            long lastModified,
            boolean isDirectory,
            String extension,
            int attributes,
            long usnRecordId,
            long parentFileId,
            long allocatedSize
    ) {
    }

    /**
     * 加载本地搜索库。
     *
     * <p>重复调用安全；加载失败时静默降级，
     * 可通过 {@link #isLoaded()} 查询结果。</p>
     */
    public static synchronized void loadLibrary() {
        if (loaded) {
            return;
        }
        try {
            System.loadLibrary(LIBRARY_NAME);
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
        }
    }

    /**
     * 判断本地库是否已加载。
     *
     * @return 已加载返回 true
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 获取本地库版本号。
     *
     * @return 版本字符串
     */
    public static native String getVersion();

    /**
     * 取消当前正在执行的搜索任务。
     */
    public static native void cancel();

    /**
     * 按文件名模式搜索。
     *
     * @param rootPath   搜索根目录
     * @param namePattern 文件名通配符（如 *.txt）
     * @param maxResults  最大结果数
     * @param callback    结果回调
     * @return 错误码，负值表示失败
     */
    public static native int searchByName(String rootPath, String namePattern, int maxResults,
                                          Consumer<FileResultData> callback);

    /**
     * 按文件大小范围搜索。
     *
     * @param rootPath   搜索根目录
     * @param minSize    最小字节
     * @param maxSize    最大字节
     * @param maxResults 最大结果数
     * @param callback   结果回调
     * @return 错误码，负值表示失败
     */
    public static native int searchBySize(String rootPath, long minSize, long maxSize, int maxResults,
                                          Consumer<FileResultData> callback);

    /**
     * 遍历目录树。
     *
     * @param rootPath   根目录
     * @param maxDepth   最大深度
     * @param maxResults 最大结果数
     * @param callback   结果回调
     * @return 错误码，负值表示失败
     */
    public static native int getTree(String rootPath, int maxDepth, int maxResults,
                                     Consumer<FileResultData> callback);

    /**
     * 按路径模式搜索。
     *
     * @param rootDir     搜索根目录
     * @param pathPattern 路径通配符
     * @param maxResults  最大结果数
     * @param callback    结果回调
     * @return 错误码，负值表示失败
     */
    public static native int searchByPath(String rootDir, String pathPattern, int maxResults,
                                          Consumer<FileResultData> callback);
}