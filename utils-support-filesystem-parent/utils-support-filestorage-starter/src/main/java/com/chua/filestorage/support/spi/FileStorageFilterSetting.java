package com.chua.filestorage.support.spi;

import com.chua.common.support.spi.annotations.Spi;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
* 图片全局滤镜 SPI。
*
* <p>定义全局图片滤镜能力（每次图片请求都会触发），
* 与 {@link FileStorageFileSetting}（仅 URL 带参数时生效）不同，
* {@code FileStorageFilterSetting} 是全局的、强制性的图片处理。</p>
*
* <p>实现者通过 {@code @Spi("name")} 注册 SPI 名称，
* 通过 {@link com.chua.filestorage.support.setting.FileStorageSetting#filterSettingKey}
* 选择使用哪个实现。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* @Spi("jdk")
* public class JdkFileStorageFilterSetting implements FileStorageFilterSetting {
*     public List<String> capabilities() {
*         return List.of("resize", "grayscale", "blur", "sharpen", "watermark");
*     }
*
*     public List<ImageFilterConfig> getFilterChain() {
*         return List.of(
*             ImageFilterConfig.of("resize", Map.of("width", "200", "height", "200")),
*             ImageFilterConfig.of("grayscale", Map.of())
*         );
*     }
* }
* }</pre>*         );
*     }
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi
public interface FileStorageFilterSetting {

    /**
    * 返回支持的滤镜能力列表。
    * <p>如 {@code ["resize", "grayscale", "blur", "watermark"]}。</p>
    *
    * @return 支持的滤镜 标识 列表
     */
    default List<String> capabilities() {
        return Collections.emptyList();
    }

    /**
    * 获取全局滤镜链配置。
    * <p>按顺序执行，返回的列表决定滤镜应用顺序。
    * 如果返回空列表，表示不进行任何全局滤镜处理。</p>
    *
    * @return 滤镜链配置列表
     */
    List<ImageFilterConfig> getFilterChain();

    /**
    * 判断指定路径或扩展名是否跳过滤镜处理。
    *
    * @param path      文件路径
    * @param extension 文件扩展名（小写）
    * @return true 表示跳过滤镜
     */
    default boolean isExcluded(String path, String extension) {
        return false;
    }

    /**
    * 滤镜配置。
    *
    * @param id     滤镜 标识（对应 镜像过滤器 SPI 名称，如 {@code "resize"}、{@code "blur"}）
    * @param params 滤镜参数（键值对）
     */
    record ImageFilterConfig(String id, Map<String, Object> params) {

        /**
        * 的
        *
        * @param id 标识
        * @param params 参数
        * @return 的的结果
         */
        public static ImageFilterConfig of(String id, Map<String, Object> params) {
            return new ImageFilterConfig(id, params);
        }

        /**
        * 的
        *
        * @param id 标识
        * @return 的的结果
         */
        public static ImageFilterConfig of(String id) {
            return new ImageFilterConfig(id, Collections.emptyMap());
        }
    }
}
