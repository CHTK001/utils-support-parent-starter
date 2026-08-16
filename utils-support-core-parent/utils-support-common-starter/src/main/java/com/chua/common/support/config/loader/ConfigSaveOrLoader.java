package com.chua.common.support.config.loader;

import java.io.Closeable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 配置保存或加载器接口。
 * 定义了对配置数据进行保存、加载和删除操作的标准方法。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ConfigSaveOrLoader extends Closeable {

    /**
     * 将字节内容保存到指定的键中。
     *
     * @param key     配置的键名，用于标识数据。
     * @param content 需要保存的字节数组内容。
     * @return 包含保存结果的 ConfigSaveResult 对象。
     */
    ConfigSaveResult saveBytes(String key, byte[] content);

    /**
     * 从指定的键中加载字节内容。
     *
     * @param key 配置的键名，用于查找数据。
     * @return 如果找到数据则返回包含内容的 Optional，否则返回空 Optional。
     */
    Optional<byte[]> loadBytes(String key);

    /**
     * 删除指定键的配置数据。
     *
     * @param key 要删除的键名。
     * @return 如果删除成功返回 true，否则返回 false。
     */
    boolean delete(String key);

    /**
     * 获取默认的字符集编码。
     * 默认实现返回 UTF-8 字符集。
     *
     * @return 当前使用的 Charset 实例。
     */
    default Charset charset() {
        return StandardCharsets.UTF_8;
    }

    /**
     * 关闭资源并释放相关连接。
     * 此方法为 Closeable 接口的默认实现，当前为空操作。
     */
    @Override
    default void close() {
        // 执行资源清理操作
    }
}
