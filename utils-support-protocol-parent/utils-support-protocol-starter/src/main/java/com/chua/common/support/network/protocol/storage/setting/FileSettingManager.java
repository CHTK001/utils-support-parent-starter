package com.chua.common.support.network.protocol.storage.setting;

import com.chua.common.support.base.collection.Options;
import com.chua.common.support.io.file.system.ConvertFileSystem;
import com.chua.common.support.io.file.system.ConvertFileSystemUtils;
import com.chua.common.support.media.MediaType;
import com.chua.common.support.media.MediaTypeFactory;
import com.chua.common.support.network.protocol.storage.FileStorageFactory;
import com.chua.common.support.network.protocol.storage.FileStorageProcessorContext;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.core.utils.ArrayUtils;
import com.chua.common.support.core.utils.IoUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 文件设置管理器
 * 
 * 负责处理文件格式转换功能，支持客户端format参数
 * 使用ConvertFileSystem进行转换，缓存结果文件到磁盘增强性能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
public class FileSettingManager {

    private static final String CACHE_DIR = System.getProperty("java.io.tmpdir") + File.separator + "file-format-cache";

    static {
        // 确保缓存目录存在
        try {
            Files.createDirectories(Paths.get(CACHE_DIR));
        } catch (IOException e) {
            log.warn("创建缓存目录失败: {}", CACHE_DIR, e);
        }
    }

    /**
     * 处理文件设置
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
                log.debug("不支持文件格式转换处理");
            }
            return;
        }
        
        String targetFormat = options.getString("format");
        if (StringUtils.isEmpty(targetFormat)) {
            if (log.isDebugEnabled()) {
                log.debug("format参数为空，不进行转换");
            }
            return;
        }

        // 处理auto格式
        String currentFormat = mediaType.subtype();
        if ("auto".equalsIgnoreCase(targetFormat)) {
            targetFormat = resolveAutoFormat(currentFormat);
            if (log.isDebugEnabled()) {
                log.debug("auto格式解析结果: {} -> {}", currentFormat, targetFormat);
            }
        }

        ServletResponse response = context.getResponse();
        byte[] originalData = response.getBody();

        if (originalData == null || originalData.length == 0) {
            log.warn("原始文件数据为空");
            return;
        }

        // 检查是否需要转换
        if (targetFormat.equalsIgnoreCase(currentFormat)) {
            if (log.isDebugEnabled()) {
                log.debug("目标格式与当前格式相同，不需要转换");
            }
            return;
        }

        // 获取文件存储设置
        FileStorageFactory.FileStorageSetting setting = fileStorageFactory.getFileStorageSetting();

        // 尝试从缓存获取转换结果
        byte[] convertedData = getFromCache(originalData, currentFormat, targetFormat, setting);
        if (convertedData != null) {
            if (log.isDebugEnabled()) {
                log.debug("从缓存获取转换结果: {} -> {}", currentFormat, targetFormat);
            }
            response.setBody(convertedData);
            updateResponseMediaType(response, targetFormat);
            return;
        }

        // 执行格式转换
        convertedData = convertFormat(originalData, currentFormat, targetFormat);
        if (convertedData != null) {
            // 缓存转换结果
            cacheResult(originalData, currentFormat, targetFormat, convertedData);

            // 更新响应
            response.setBody(convertedData);
            updateResponseMediaType(response, targetFormat);
            if (log.isDebugEnabled()) {
                log.debug("文件格式转换完成: {} -> {}", currentFormat, targetFormat);
            }
        } else {
            log.warn("文件格式转换失败: {} -> {}", currentFormat, targetFormat);
        }
    }

    /**
     * 是否支持处理当前请求
     * 
     * @param mediaType 媒体类型
     * @param options 选项参数
     * @param fileStorageFactory 文件存储工厂
     * @return 如果支持返回true
     */
    public boolean supports(MediaType mediaType, Options options, FileStorageFactory fileStorageFactory) {
        // 检查是否有format参数
        String format = options.getString("format");
        if (StringUtils.isEmpty(format)) {
            if (log.isDebugEnabled()) {
                log.debug("没有format参数，不支持格式转换");
            }
            return false;
        }
        
        // 检查配置是否开启format功能
        if (!isFormatEnabled(fileStorageFactory)) {
            if (log.isDebugEnabled()) {
                log.debug("配置未开启format功能，不支持格式转换");
            }
            return false;
        }
        
        return true;
    }

    /**
     * 检查配置是否开启format功能
     */
    private boolean isFormatEnabled(FileStorageFactory fileStorageFactory) {
        try {
            FileStorageFactory.FileStorageSetting setting = fileStorageFactory.getFileStorageSetting();
            if (setting == null) {
                return false;
            }
            
            // 检查plugins配置中是否包含format
            String[] plugins = setting.getPlugins();
            return ArrayUtils.containsIgnoreCase(plugins, "format");
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("检查format配置失败", e);
            }
            return false;
        }
    }

    /**
     * 执行格式转换
     */
    private byte[] convertFormat(byte[] originalData, String fromFormat, String toFormat) {
        File tempInputFile = null;
        File tempOutputFile = null;
        try {
            // 使用ConvertFileSystemUtils判断是否支持转换
            if (!ConvertFileSystemUtils.supportedTypes(fromFormat, toFormat)) {
                if (log.isDebugEnabled()) {
                    log.debug("ConvertFileSystem不支持转换: {} -> {}", fromFormat, toFormat);
                }
                return null;
            }

            // 创建临时文件
            tempInputFile = File.createTempFile("convert_input_", "." + fromFormat);
            tempOutputFile = File.createTempFile("convert_output_", "." + toFormat);
            
            // 写入临时文件
            Files.write(tempInputFile.toPath(), originalData);
            
            // 创建转换器
            ConvertFileSystem convertSystem = ConvertFileSystemUtils.create(tempInputFile, toFormat);
            if (convertSystem == null) {
                if (log.isDebugEnabled()) {
                    log.debug("无法创建转换器: {} -> {}", fromFormat, toFormat);
                }
                return null;
            }

            // 执行转换
            convertSystem.convertTo(tempOutputFile);

            // 读取转换结果
            return Files.readAllBytes(tempOutputFile.toPath());

        } catch (Exception e) {
            log.error("格式转换异常: {} -> {}", fromFormat, toFormat, e);
            return null;
        } finally {
            // 清理临时文件
            if (tempInputFile != null && tempInputFile.exists()) {
                try {
                    Files.deleteIfExists(tempInputFile.toPath());
                } catch (Exception e) {
                    log.warn("删除临时输入文件失败: {}", tempInputFile.getAbsolutePath(), e);
                }
            }
            if (tempOutputFile != null && tempOutputFile.exists()) {
                try {
                    Files.deleteIfExists(tempOutputFile.toPath());
                } catch (Exception e) {
                    log.warn("删除临时输出文件失败: {}", tempOutputFile.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * 从缓存获取转换结果
     */
    private byte[] getFromCache(byte[] originalData, String fromFormat, String toFormat, FileStorageFactory.FileStorageSetting setting) {
        try {
            String cacheKey = generateCacheKey(originalData, fromFormat, toFormat);
            File cacheFile = new File(CACHE_DIR, cacheKey);

            if (!cacheFile.exists()) {
                return null;
            }

            // 获取缓存过期时间（毫秒）
            long cacheExpireTime = getCacheExpireTime(setting);
            if (cacheExpireTime <= 0) {
                // 缓存时间为0或负数，表示不使用缓存
                deleteCacheFile(cacheFile, cacheKey);
                return null;
            }

            // 检查缓存是否过期（基于文件创建时间）
            long fileCreateTime = cacheFile.lastModified();
            if (System.currentTimeMillis() - fileCreateTime > cacheExpireTime) {
                // 删除过期缓存
                deleteCacheFile(cacheFile, cacheKey);
                return null;
            }

            // 读取缓存文件
            try (FileInputStream fis = new FileInputStream(cacheFile)) {
                return IoUtils.toByteArray(fis);
            }

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("从缓存读取失败", e);
            }
            return null;
        }
    }

    /**
     * 缓存转换结果
     */
    private void cacheResult(byte[] originalData, String fromFormat, String toFormat, byte[] convertedData) {
        try {
            String cacheKey = generateCacheKey(originalData, fromFormat, toFormat);
            File cacheFile = new File(CACHE_DIR, cacheKey);

            // 写入缓存文件
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(convertedData);
                fos.flush();
            }

            if (log.isDebugEnabled()) {
                log.debug("缓存转换结果: {}", cacheKey);
            }

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("缓存转换结果失败", e);
            }
        }
    }

    /**
     * 生成缓存键
     */
    private String generateCacheKey(byte[] originalData, String fromFormat, String toFormat) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(originalData);
            md.update(fromFormat.getBytes());
            md.update(toFormat.getBytes());
            
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            
            return sb + "_" + fromFormat + "_to_" + toFormat;
            
        } catch (Exception e) {
            log.warn("生成缓存键失败", e);
            return System.currentTimeMillis() + "_" + fromFormat + "_to_" + toFormat;
        }
    }

    /**
     * 获取缓存过期时间
     *
     * @param setting 文件存储设置
     * @return 缓存过期时间（毫秒），0或负数表示不使用缓存
     */
    private long getCacheExpireTime(FileStorageFactory.FileStorageSetting setting) {
        if (setting == null) {
            return 0;
        }

        try {
            // 获取配置的缓存时间（分钟转换为毫秒）
            int cacheTimeMinutes = setting.getFormatCacheTimeMinutes();
            if (cacheTimeMinutes <= 0) {
                return 0; // 不使用缓存
            }
            return cacheTimeMinutes * 60 * 1000L;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("获取缓存过期时间失败，使用默认值24小时", e);
            }
            return 24 * 60 * 60 * 1000L;
        }
    }

    /**
     * 删除缓存文件
     */
    private void deleteCacheFile(File cacheFile, String cacheKey) {
        try {
            if (cacheFile.exists()) {
                cacheFile.delete();
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("删除缓存文件失败: {}", cacheFile.getPath(), e);
            }
        }
    }

    /**
     * 更新响应的媒体类型
     */
    private void updateResponseMediaType(ServletResponse response, String newFormat) {
        try {
            // 使用MediaTypeFactory根据文件扩展名获取MediaType
            com.chua.common.support.media.MediaType mediaType =
                MediaTypeFactory.parse("." + newFormat);

            if (mediaType != null) {
                // 设置Content-Type头
                response.setContentType(mediaType.toString());
                if (log.isDebugEnabled()) {
                    log.debug("更新响应媒体类型为: {} -> {}", newFormat, mediaType);
                }
            } else {
                // 如果找不到对应的MediaType，使用默认的application/octet-stream
                response.setContentType("application/octet-stream");
                if (log.isDebugEnabled()) {
                    log.debug("未找到格式 {} 对应的MediaType，使用默认类型", newFormat);
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("更新响应媒体类型失败: {}", newFormat, e);
            }
            // 发生异常时使用默认类型
            response.setContentType("application/octet-stream");
        }
    }

    /**
     * 解析auto格式，按html > png > pdf的优先级选择目标格式
     *
     * @param currentFormat 当前文件格式
     * @return 解析后的目标格式
     */
    private String resolveAutoFormat(String currentFormat) {
        if (StringUtils.isEmpty(currentFormat)) {
            if (log.isDebugEnabled()) {
                log.debug("当前格式为空，auto格式解析返回原格式");
            }
            return currentFormat;
        }

        // 如果当前格式已经是目标格式之一，保持不变
        if ("html".equalsIgnoreCase(currentFormat) ||
                "png".equalsIgnoreCase(currentFormat) ||
                "pdf".equalsIgnoreCase(currentFormat)) {
            if (log.isDebugEnabled()) {
                log.debug("当前格式 {} 已经是目标格式之一，保持不变", currentFormat);
            }
            return currentFormat;
        }

        // 按优先级检查转换支持：html > png > pdf
        String[] targetFormats = {"html", "png", "pdf"};

        for (String targetFormat : targetFormats) {
            try {
                if (ConvertFileSystemUtils.supportedTypes(currentFormat, targetFormat)) {
                    if (log.isDebugEnabled()) {
                        log.debug("auto格式解析：{} 支持转换为 {}", currentFormat, targetFormat);
                    }
                    return targetFormat;
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("检查转换支持时出现异常: {} -> {}", currentFormat, targetFormat, e);
                }
            }
        }

        // 如果都不支持转换，返回原格式
        if (log.isDebugEnabled()) {
            log.debug("auto格式解析：{} 不支持转换为html/png/pdf，保持原格式", currentFormat);
        }
        return currentFormat;
    }
}