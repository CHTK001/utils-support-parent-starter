package com.chua.filestorage.support.spi;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.filestorage.support.operation.FileOperationSetting;
import com.chua.common.support.spi.annotations.Spi;

import java.util.Collections;
import java.util.List;

/**
 * 文件存储 URL 参数设置 SPI。
 *
 * <p>解析 {@code ?preview&size=100x100&format=webp&quality=80} 等 URL 参数，
 * 返回支持的能力列表和解析后的操作配置对象。</p>
 *
 * <p>实现者通过 {@code @Spi("name")} 注册 SPI 名称，
 * 通过 {@link FileStorageSetting#fileSettingKey} 选择使用哪个实现。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * @Spi("jdk")
 * public class JdkFileStorageFileSetting implements FileStorageFileSetting {
 *     public List<String> capabilities() {
 *         return List.of("size", "format", "quality", "crop", "rotate");
 *     }
 *     public FileOperationSetting parse(ServerRequest request) {
 *         return FileOperationSetting.builder()
 *             .size(request.getParam("size"))
 *             .format(request.getParam("format"))
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see FileOperationSetting
 */
@Spi
public interface FileStorageFileSetting {

    /**
     * 返回支持的能力列表。
     * <p>对应 URL 参数名，如 {@code ["size", "format", "quality"]}。
     * 返回空列表表示当前实现不支持任何 URL 参数。</p>
     *
     * @return 支持的能力名称列表
     */
    default List<String> capabilities() {
        return Collections.emptyList();
    }

    /**
     * 从 HTTP 请求中解析 URL 参数，生成操作配置。
     *
     * <p>参数来源：
     * <ul>
     *   <li>URL query 参数（如 {@code ?size=200x200&format=webp}）</li>
     *   <li>请求头（如 {@code X-Size: 200x200}）</li>
     *   <li>请求属性（由其他 Filter 设置）</li>
     * </ul>
     * </p>
     *
     * @param request HTTP 请求对象
     * @return 解析后的文件操作配置，永不为 null
     */
    FileOperationSetting parse(ServerRequest request);
}
