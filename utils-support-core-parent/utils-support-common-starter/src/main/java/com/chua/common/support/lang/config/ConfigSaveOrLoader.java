package com.chua.common.support.lang.config;

import java.io.Closeable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 配置保存与加载接口。
 * 该接口定义了用于持久化配置数据的通用操作，包括保存字节数据、加载字节数据以及删除配置项。
 * 同时实现了 Closeable 接口，确保资源在使用完毕后能够被正确关闭。
 * @author CH
 * @since 4.0.0.42
 */
public interface ConfigSaveOrLoader extends Closeable {

    /**
     * 将指定的字节内容保存到配置存储中。
     *
     * @param key      配置的唯一标识键。
     * @param content  需要保存的字节数组内容。
     * @return         保存操作的结果对象，包含成功状态及可能的详细信息。
     */
    ConfigSaveResult saveBytes(String key, byte[] content);

    /**
     * 根据键从配置存储中加载对应的字节数据。
     *
     * @param key 配置的唯一标识键。
     * @return      包含加载内容的 Optional 对象。如果未找到对应键，则返回空的 Optional。
     */
    Optional<byte[]> loadBytes(String key);

    /**
     * 删除指定键对应的配置项。
     *
     * @param key 需要删除的配置键。
     * @return      如果删除成功或键不存在（视为成功），返回 true；否则返回 false。
     */
    boolean delete(String key);

    /**
     * 获取此配置加载器默认使用的字符集。
     * 默认实现返回 UTF-8 字符集。
     *
     * @return 字符集对象。
     */
    default Charset charset() {
        return StandardCharsets.UTF_8;
    }

    /**
     * 关闭此配置加载器，释放相关资源。
     * 默认实现为空，子类可根据需要重写以执行具体的清理逻辑。
     */
    @Override
    default void close() {
        // 默认不执行任何操作，由具体实现类处理资源关闭
    }
}
