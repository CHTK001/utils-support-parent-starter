package com.chua.common.support.nativeapi;

import java.util.function.Consumer;

/**
 * 文件搜索原生库 SPI。
 *
 * <p>由 {@code utils-support-native-filesearch} 提供实现。原生 JSON 只提供
 * path / size / modified / ext 四项，其余字段按默认值填充。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface FileSearchSpi extends NativeService {

    /**
     * 文件搜索结果。
     *
     * @param path          文件路径
     * @param size          字节数
     * @param lastModified  最后修改时间（Unix 毫秒）
     * @param isDirectory   是否目录
     * @param extension     扩展名
     * @param attributes    文件属性位
     * @param usnRecordId   USN 记录号
     * @param parentFileId  父目录文件号
     * @param allocatedSize 分配大小
     */
    record Result(String path, long size, long lastModified, boolean isDirectory,
                  String extension, int attributes, long usnRecordId,
                  long parentFileId, long allocatedSize) {
    }

    /**
     * 加载原生库。
     */
    void loadLibrary();

    /**
     * 原生库是否已加载。
     *
     * @return 已加载返回 {@code true}
     */
    boolean isLoaded();

    @Override
    default boolean available() {
        if (isLoaded()) {
            return true;
        }
        loadLibrary();
        return isLoaded();
    }

    /**
     * 原生库加载失败原因。
     *
     * @return 失败异常；加载成功返回 {@code null}
     */
    Throwable getLoadError();

    /**
     * 原生库版本。
     *
     * @return 版本串
     */
    String getVersion();

    /**
     * 取消正在进行的搜索。
     */
    void cancel();

    /**
     * 按名称搜索文件。
     *
     * @param root     搜索根目录
     * @param pattern  文件名 glob 模式
     * @param max      最大返回数
     * @param callback 每个匹配文件的回调
     * @return 匹配数量；原生调用失败返回 -1
     */
    int searchByName(String root, String pattern, int max, Consumer<Result> callback);

    /**
     * 遍历目录树。
     *
     * @param root     根目录
     * @param depth    最大深度
     * @param max      最大返回数
     * @param callback 每个条目的回调
     * @return 条目数量；原生调用失败返回 -1
     */
    int getTree(String root, int depth, int max, Consumer<Result> callback);

    /**
     * 按文件大小区间搜索。
     *
     * @param root     搜索根目录
     * @param minSize  最小字节数
     * @param maxSize  最大字节数
     * @param max      最大返回数
     * @param callback 每个匹配文件的回调
     * @return 匹配数量；原生调用失败返回 -1
     */
    int searchBySize(String root, long minSize, long maxSize, int max,
                     Consumer<Result> callback);

    /**
     * 按路径模式搜索。
     *
     * @param root     搜索根目录
     * @param pattern  路径 glob 模式
     * @param max      最大返回数
     * @param callback 每个匹配文件的回调
     * @return 匹配数量；原生调用失败返回 -1
     */
    int searchByPath(String root, String pattern, int max, Consumer<Result> callback);
}
