package com.chua.common.support.network.protocol.storage.image;

import com.chua.common.support.base.collection.Option;
import com.chua.common.support.base.collection.Options;
import com.chua.common.support.media.MediaType;
import com.chua.common.support.network.protocol.storage.FileStorageFactory;
import com.chua.common.support.network.protocol.storage.FileStorageProcessorContext;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.core.utils.ArrayUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;


/**
 * 图片设置管理器
 * <p>
 * 负责处理图片相关的设置，判断前端参数是否在FileStorageSetting#settings配置中
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
public class ImageSettingManager {

    /**
     * 处理图片设置
     *
     * @param context 处理器上下文
     * @throws Exception 处理过程中可能抛出的异常
     */
    public void process(FileStorageProcessorContext context) throws Exception {
        MediaType mediaType = context.getMediaType();
        Options options = context.getOptions();
        FileStorageFactory fileStorageFactory = context.getFileStorageFactory();

        // 检查是否支持处理
        if (!supports(mediaType, options, fileStorageFactory)) {
            if (log.isDebugEnabled()) {
                log.debug("不支持图片设置处理，设置原始数据到响应");
            }
            return;
        }

        // 获取原始图片二进制数据
        ServletResponse response = context.getResponse();
        byte[] originalImageData = response.getBody();
        
        // 处理图片（中间过程使用二进制）
        byte[] processedImageData = processImage(originalImageData, options, fileStorageFactory);
        
        if (processedImageData != null) {
            // 设置处理后的二进制数据到响应
            context.getResponse().setBody(processedImageData);
            if (log.isDebugEnabled()) {
                log.debug("图片处理完成，设置到响应");
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("图片处理返回null，保持原始数据");
            }
        }
    }

    /**
     * 是否支持处理当前请求
     *
     * @param mediaType          媒体类型
     * @param options            选项参数
     * @param fileStorageFactory 文件存储工厂
     * @return 如果支持返回true
     */
    public boolean supports(MediaType mediaType, Options options, FileStorageFactory fileStorageFactory) {
        // 只处理图片类型
        if (mediaType == null || !mediaType.isImage()) {
            if (log.isDebugEnabled()) {
                log.debug("不是图片类型，不支持图片设置处理");
            }
            return false;
        }

        // 检查是否开启设置功能
        if (!fileStorageFactory.openSetting()) {
            if (log.isDebugEnabled()) {
                log.debug("未开启设置功能，不处理图片设置");
            }
            return false;
        }

        // 获取配置的settings数组
        String[] configuredSettings = fileStorageFactory.settings();
        if (ArrayUtils.isEmpty(configuredSettings)) {
            if (log.isDebugEnabled()) {
                log.debug("未配置settings数组，不处理图片设置");
            }
            return false;
        }

        // 检查前端参数是否在配置的settings中且是支持的图片设置
        boolean hasValidParams = hasValidImageSettingParams(options, configuredSettings);
        if (!hasValidParams) {
            if (log.isDebugEnabled()) {
                log.debug("没有有效的图片设置参数，不处理");
            }
        }

        return hasValidParams;
    }

    /**
     * 处理图片二进制数据
     *
     * @param imageData          原始图片二进制数据
     * @param options            选项参数
     * @param fileStorageFactory 文件存储工厂
     * @return 处理后的图片二进制数据，如果未处理则返回null
     * @throws Exception 处理过程中可能抛出的异常
     */
    public byte[] processImage(byte[] imageData, Options options, FileStorageFactory fileStorageFactory) throws Exception {
        if (imageData == null || imageData.length == 0) {
            if (log.isDebugEnabled()) {
                log.debug("图片数据为空，跳过处理");
            }
            return null;
        }

        byte[] result = imageData;
        int processCount = 0;

        // 获取配置的settings数组
        String[] configuredSettings = fileStorageFactory.settings();

        // 遍历所有选项，应用相应的处理
        for (Map.Entry<String, Option> entry : options.getOption().entrySet()) {
            String settingType = entry.getKey();
            String settingValue = entry.getValue().getFirst();

            // 检查参数是否在配置的settings中
            if (!isSettingConfigured(settingType, configuredSettings)) {
                if (log.isDebugEnabled()) {
                    log.debug("参数 {} 不在配置的settings中，跳过处理", settingType);
                }
                continue;
            }

            // 检查是否是支持的图片设置
            if (!isSupportedImageSetting(settingType, configuredSettings)) {
                if (log.isDebugEnabled()) {
                    log.debug("参数 {} 不是支持的图片设置，跳过处理", settingType);
                }
                continue;
            }

            if (StringUtils.isEmpty(settingValue)) {
                continue;
            }

            try {
                ImageSettingProcessor imageSettingProcessor = ServiceProvider.of(ImageSettingProcessor.class).getExtension(settingType);
                if (imageSettingProcessor != null) {
                    processCount++;
                    byte[] processed = imageSettingProcessor.process(result, settingValue);
                    if (processed != null && processed.length > 0) {
                        result = processed;
                        if (log.isDebugEnabled()) {
                            log.debug("应用图片设置: {} = {}", settingType, settingValue);
                        }
                    } else {
                        log.warn("图片设置处理器返回空数据: {} = {}", settingType, settingValue);
                    }
                }
            } catch (Exception e) {
                log.warn("处理图片设置失败: {} = {}", settingType, settingValue, e);
                // 继续处理其他设置，不中断整个流程
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("图片设置处理完成，应用了 {} 个设置", processCount);
        }
        return processCount > 0 ? result : null;
    }

    /**
     * 检查是否有有效的图片设置参数
     */
    private boolean hasValidImageSettingParams(Options options, String[] configuredSettings) {
        for (String key : configuredSettings) {
            if (options.hasOption(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查设置是否在配置中
     */
    private boolean isSettingConfigured(String settingKey, String[] configuredSettings) {
        if (StringUtils.isEmpty(settingKey) || ArrayUtils.isEmpty(configuredSettings)) {
            return false;
        }
        return ArrayUtils.containsIgnoreCase(configuredSettings, settingKey);
    }

    /**
     * 检查是否是支持的图片设置
     *
     * @param settingKey         设置名称
     * @param configuredSettings 配置
     */
    private boolean isSupportedImageSetting(String settingKey, String[] configuredSettings) {
        return ArrayUtils.containsIgnoreCase(configuredSettings, settingKey);
    }


}