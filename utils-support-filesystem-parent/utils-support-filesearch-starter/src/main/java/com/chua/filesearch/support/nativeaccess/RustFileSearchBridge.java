package com.chua.filesearch.support.nativeaccess;

import com.chua.common.support.nativeapi.FileSearchSpi;
import com.chua.common.support.nativeapi.NativeServices;

import java.util.function.Consumer;

/**
 * 可选依赖 {@code utils-support-native-filesearch} 的 SPI 访问层。
 *
 * <p>实现由 native 模块通过 {@code META-INF/services} 注册；构件存在时委托其
 * {@code RustFileSearchBridge}，缺失时只在首次调用处打印一次警告，由调用方降级到
 * JDK 遍历。本类不再使用反射。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class RustFileSearchBridge {

    private static final String DEPENDENCY = "com.chua:utils-support-native-filesearch";

    private RustFileSearchBridge() {
    }

    /**
     * 原生搜索结果记录。
     *
     * @param path           文件路径
     * @param size           文件大小
     * @param lastModified   最后修改时间
     * @param isDirectory    是否目录
     * @param extension      扩展名
     * @param attributes     文件属性位
     * @param usnRecordId    USN 记录号
     * @param parentFileId   父目录文件号
     * @param allocatedSize  分配大小
     */
    public record FileResultData(String path, long size, long lastModified, boolean isDirectory,
                                 String extension, int attributes, long usnRecordId,
                                 long parentFileId, long allocatedSize) {
    }

    public static void loadLibrary() {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        if (spi != null) {
            spi.loadLibrary();
        }
    }

    public static boolean isLoaded() {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        return spi != null && spi.isLoaded();
    }

    public static Throwable getLoadError() {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        return spi == null ? new IllegalStateException("请自行添加依赖 " + DEPENDENCY) : spi.getLoadError();
    }

    public static String getVersion() {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        return spi == null ? null : spi.getVersion();
    }

    public static void cancel() {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        if (spi != null) {
            spi.cancel();
        }
    }

    public static int searchByName(String root, String pattern, int max,
                                   Consumer<FileResultData> callback) {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        return spi == null ? -1 : spi.searchByName(root, pattern, max, toNative(callback));
    }

    public static int getTree(String root, int depth, int max, Consumer<FileResultData> callback) {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        return spi == null ? -1 : spi.getTree(root, depth, max, toNative(callback));
    }

    public static int searchBySize(String root, long minSize, long maxSize, int max,
                                   Consumer<FileResultData> callback) {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        return spi == null ? -1 : spi.searchBySize(root, minSize, maxSize, max, toNative(callback));
    }

    public static int searchByPath(String root, String pattern, int max,
                                   Consumer<FileResultData> callback) {
        FileSearchSpi spi = NativeServices.get(FileSearchSpi.class);
        return spi == null ? -1 : spi.searchByPath(root, pattern, max, toNative(callback));
    }

    private static Consumer<FileSearchSpi.Result> toNative(Consumer<FileResultData> callback) {
        if (callback == null) {
            return null;
        }
        return data -> callback.accept(new FileResultData(data.path(), data.size(), data.lastModified(),
                data.isDirectory(), data.extension(), data.attributes(), data.usnRecordId(),
                data.parentFileId(), data.allocatedSize()));
    }
}
