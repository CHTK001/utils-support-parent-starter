package com.chua.filesearch.support.bridge;

import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.utils.NativeUtils;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
* Rust 文件搜索原生库桥接。
*
* <p>通过 {@link com.chua.common.support.utils.NativeLoader} 加载 Rust DLL，
* 提供按名称搜索文件和遍历目录树的功能。</p>
*
* @author CH
* @since 4.0.0.43
 */
@Slf4j
public final class RustFileSearchBridge {

    private static volatile boolean loaded = false; // 加载
    private static final Object LOCK = new Object(); // 锁

    public record FileResultData(
            String path, long size, long lastModified, boolean isDirectory,
            String extension, int attributes, long usnRecordId,
            long parentFileId, long allocatedSize) {}

    /**
    * 加载原生动态库。线程安全，重复调用无副作用。
    */
    public static synchronized void loadLibrary() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            try {
                NativeLoader.of("file-search")
                        .toTarget(NativeUtils.tempRoot().resolve("file-search"))
                        .glob("*file_search*")
                        .load();
                loaded = true;
                log.info("[filesearch] native library loaded");
            } catch (Throwable e) {
                log.warn("[filesearch] load failed: {}", e.getMessage());
                loaded = false;
            }
        }
    }

    /**
    * 查询是否已加载原生库。
    *
    * @return 已加载返回 true
    */
    public static boolean isLoaded() { return loaded; }

 // JNI NAT ??? Rust DLL ???????
    /**
    * 搜索by名称。
    * @param root 根
    * @param pattern 模式
    * @param max 最大
    * @param cb cb
    * @return 搜索by名称的结果
    */
    public static native int searchByName(String root, String pattern, int max, Consumer<FileResultData> cb);
    /**
    * 获取树。
    * @param root 根
    * @param depth 深度
    * @param max 最大
    * @param cb cb
    * @return 获取树的结果
    */
    public static native int getTree(String root, int depth, int max, Consumer<FileResultData> cb);
    /**
    * 搜索by大小。
    * @param root 根
    * @param minSize 最小大小
    * @param maxSize 最大大小
    * @param max 最大
    * @param cb cb
    * @return 搜索by大小的结果
    */
    public static native int searchBySize(String root, long minSize, long maxSize, int max, Consumer<FileResultData> cb);
    /**
    * 搜索by路径。
    * @param root 根
    * @param pattern 模式
    * @param max 最大
    * @param cb cb
    * @return 搜索by路径的结果
    */
    public static native int searchByPath(String root, String pattern, int max, Consumer<FileResultData> cb);
    /**
    * 获取版本。
    * @return 获取版本的结果
    */
    public static native String getVersion();
    /**
    * cancel。
    */
    public static native void cancel();

    // ===== ?? API =====
    /**
    * 安全封装：加载库后按名称搜索文件。
    *
    * @param rootPath     搜索根目录
    * @param namePattern  文件名模式（支持 glob）
    * @param maxResults   最大返回结果数
    * @param callback     每个匹配文件的回调
    * @return 匹配数量
    */
    public static int searchByNameSafe(String rootPath, String namePattern, int maxResults,
                                        Consumer<FileResultData> callback) {
        loadLibrary();
        return searchByName(rootPath, namePattern, maxResults, callback);
    }

    /**
    * 安全封装：加载库后遍历目录树。
    *
    * @param rootPath   根目录
    * @param maxDepth   最大深度
    * @param maxResults 最大返回结果数
    * @param callback   每个节点的回调
    * @return 遍历节点数量
    */
    public static int getTreeSafe(String rootPath, int maxDepth, int maxResults,
                                   Consumer<FileResultData> callback) {
        loadLibrary();
        return getTree(rootPath, maxDepth, maxResults, callback);
    }
}
