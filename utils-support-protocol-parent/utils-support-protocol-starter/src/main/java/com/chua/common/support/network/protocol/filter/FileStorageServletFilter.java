package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.annotation.SpiSupport;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.base.collection.Options;
import com.chua.common.support.media.MediaType;
import com.chua.common.support.storage.oss.FileStorage;
import com.chua.common.support.storage.oss.metadata.Metadata;
import com.chua.common.support.storage.oss.request.GetObjectRequest;
import com.chua.common.support.storage.oss.result.GetObjectResult;
import com.chua.common.support.storage.oss.setting.BucketSetting;
import com.chua.common.support.network.protocol.storage.BucketAddress;
import com.chua.common.support.network.protocol.storage.FileStorageFactory;
import com.chua.common.support.network.protocol.storage.FileStorageProcessor;
import com.chua.common.support.network.protocol.storage.FileStorageProcessorContext;
import com.chua.common.support.network.protocol.storage.SignedFileAccessHandler;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.HotloadingServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.ServletFilterConfig;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.core.utils.FileUtils;
import com.chua.common.support.core.utils.IoUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.chua.common.support.core.constant.CommonConstant.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 文件存储Servlet过滤器
 * <p>
 * 完全重新实现，提供文件存储、预览、下载等功能
 * 使用SPI机制让各个功能模块可以独立扩展
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("fileStorage")
@SpiDescribe("文件存储")
@SpiSupport("http")
public class FileStorageServletFilter  extends AbstractServletFilter implements HotloadingServletFilter<FileStorageFactory.FileStorageSetting> {

    private FileStorageFactory fileStorageFactory;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // 闪图访问记录目录
    private static final String FLASH_ACCESS_DIR = System.getProperty("java.io.tmpdir") + "/flash_images/";

    // 初始化闪图访问记录目录
    static {
        try {
            java.io.File dir = new java.io.File(FLASH_ACCESS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        } catch (Exception e) {
            // 忽略初始化错误
        }
    }

    // 闪图记录，记录已访问的图片
    private static final ConcurrentHashMap<String, AtomicBoolean> FLASH_IMAGE_RECORDS = new ConcurrentHashMap<>();

    // 缓存BucketAddress解析结果，避免重复解析
    private final Map<String, BucketAddress> bucketAddressCache = new ConcurrentHashMap<>(256);

    // 缓存文件存储Bean名称查找结果
    private final Map<String, String> beanNameCache = new ConcurrentHashMap<>(128);

    public FileStorageServletFilter(FileStorageFactory.FileStorageSetting config) {
        if (initialized.compareAndSet(false, true)) {
            initializeFileStorageFactory(config);

            log.info("FileStorageServletFilter初始化完成");
        }
    }

    @Override
    public void init(ServletFilterConfig config) throws Exception {

    }

    /**
     * 初始化文件存储工厂
     */
    private void initializeFileStorageFactory(FileStorageFactory.FileStorageSetting setting) {
        // 从配置中获取设置
        this.fileStorageFactory = FileStorageFactory.create(setting);

        // 添加默认存储 - 参考FileStorageChainFilter的实现
        if (setting.isOpenWebjars()) {
            fileStorageFactory.addFileStorage("webjars",
                    FileStorage.createStorage("classpath", BucketSetting.builder().bucket("webjars").build()));
            fileStorageFactory.addFileStorage("assets",
                    FileStorage.createStorage("classpath", BucketSetting.builder().bucket("assets").build()));
        }

        if (setting.isOpenRemoteFile()) {
            fileStorageFactory.addFileStorage("remote",
                    FileStorage.createStorage("remote", BucketSetting.builder().bucket("remote").build()));
        }
    }

    /**
     * 创建文件存储设置
     */
    private FileStorageFactory.FileStorageSetting createFileStorageSetting(ServletFilterConfig config) {
        FileStorageFactory.FileStorageSetting.FileStorageSettingBuilder builder = FileStorageFactory.FileStorageSetting
                .builder();

        // 从配置中读取参数 - 参考FileStorageChainFilter的配置项
        String openDownload = config.getInitParameter("openDownload");
        if (StringUtils.isNotEmpty(openDownload)) {
            builder.openDownload(Boolean.parseBoolean(openDownload));
        }

        String openPreview = config.getInitParameter("openPreview");
        if (StringUtils.isNotEmpty(openPreview)) {
            builder.openPreview(Boolean.parseBoolean(openPreview));
        }

        String openPlugin = config.getInitParameter("openPlugin");
        if (StringUtils.isNotEmpty(openPlugin)) {
            builder.openPlugin(Boolean.parseBoolean(openPlugin));
        }

        String openSetting = config.getInitParameter("openSetting");
        if (StringUtils.isNotEmpty(openSetting)) {
            builder.openSetting(Boolean.parseBoolean(openSetting));
        }

        String openRange = config.getInitParameter("openRange");
        if (StringUtils.isNotEmpty(openRange)) {
            builder.openRange(Boolean.parseBoolean(openRange));
        }

        String openWatermark = config.getInitParameter("openWatermark");
        if (StringUtils.isNotEmpty(openWatermark)) {
            builder.openWatermark(Boolean.parseBoolean(openWatermark));
        }

        String openWebjars = config.getInitParameter("openWebjars");
        if (StringUtils.isNotEmpty(openWebjars)) {
            builder.openWebjars(Boolean.parseBoolean(openWebjars));
        }

        String openRemoteFile = config.getInitParameter("openRemoteFile");
        if (StringUtils.isNotEmpty(openRemoteFile)) {
            builder.openRemoteFile(Boolean.parseBoolean(openRemoteFile));
        }

        // 读取插件配置
        String plugins = config.getInitParameter("plugins");
        if (StringUtils.isNotEmpty(plugins)) {
            builder.plugins(StringUtils.splitAndTrim(plugins, ","));
        }

        // 读取下载用户代理配置
        String downloadUserAgent = config.getInitParameter("downloadUserAgent");
        if (StringUtils.isNotEmpty(downloadUserAgent)) {
            builder.downloadUserAgent(StringUtils.splitAndTrim(downloadUserAgent, ","));
        }

        return builder.build();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        // 参考FileStorageChainFilter的逻辑：检查服务是否启用
        if (!fileStorageFactory.openDownload() && !fileStorageFactory.openPreview()) {
            sendXmlErrorResponse(response, 400, "不支持预览", request.getUrl());
            return;
        }

        try {
            String url = request.getUrl();
            if (StringUtils.isEmpty(url)) {
                sendXmlErrorResponse(response, 400, "请求URL为空", url);
                return;
            }

            // 解析URL和参数
            BucketAddress bucketAddress = getBucketAddressFromCache(url);

            // 检查是否为短链接格式：/s/{shareId}，使用 SPI 转换为真实地址
            if (isShortLinkUrl(url)) {
                bucketAddress = resolveShortLink(bucketAddress, url);
                if (bucketAddress == null) {
                    sendXmlErrorResponse(response, 404, "链接不存在或已失效", url);
                    return;
                }
            }

            Options options = bucketAddress.getOptions();

            // 处理文件访问
            handleRegularFileAccess(request, response, bucketAddress, options, url);

        } catch (Exception e) {
            log.error("FileStorageServletFilter处理失败", e);
            sendXmlErrorResponse(response, 500, "文件处理失败", request.getUrl());
        }
    }

    /**
     * 解析短链接，使用 SPI 转换为真实文件地址
     *
     * @param bucketAddress 短链接地址
     * @param url           原始 URL
     * @return 转换后的真实文件地址，null 表示无效
     */
    private BucketAddress resolveShortLink(BucketAddress bucketAddress, String url) {
        SignedFileAccessHandler handler = ServiceProvider.of(SignedFileAccessHandler.class).getDefault();
        if (handler == null) {
            log.warn("未找到短链接处理器: url={}", url);
            return null;
        }
        return handler.resolve(bucketAddress);
    }

    /**
     * 处理文件访问
     */
    private void handleRegularFileAccess(ServletRequest request, ServletResponse response,
                                       BucketAddress bucketAddress, Options options, String url) throws Exception {
        String beanName = getBeanFileStorageBeanFromCache(bucketAddress);
        FileStorage fileStorage = fileStorageFactory.get(beanName);

        if (fileStorage == null) {
            sendXmlErrorResponse(response, 404, "文件存储不存在: " + beanName, url);
            return;
        }

        String mode = options.getString("mode", "preview");
        if (StringUtils.isEmpty(mode)) {
            sendXmlErrorResponse(response, 400, "不支持的操作模式", url);
            return;
        }

        // 验证预览模式
        if (PREVIEW.equalsIgnoreCase(mode) && !fileStorageFactory.openPreview()) {
            sendXmlErrorResponse(response, 400, "不支持预览", url);
            return;
        }

        // 验证下载模式
        if (DOWNLOAD.equalsIgnoreCase(mode) && !fileStorageFactory.openDownload()) {
            sendXmlErrorResponse(response, 400, "不支持下载", url);
            return;
        }

        // 验证信息模式
        if (INFO.equalsIgnoreCase(mode) && !fileStorageFactory.openSetting()) {
            sendXmlErrorResponse(response, 400, "不支持信息查看", url);
            return;
        }

        String path = bucketAddress.getPath(beanName);
        GetObjectResult getResult = fileStorage.getObject(GetObjectRequest.builder()
                .fileName(FileUtils.getName(path))
                .filePath(FileUtils.getPath(path))
                .build());

        if (!getResult.getResultCode().isSuccessful()) {
            sendXmlErrorResponse(response, 400, getResult.getMessage(), url);
            return;
        }

        // 复用公共文件处理逻辑
        processFileContent(request, response, getResult, options, mode, path, beanName);

        // 根据mode设置Content-Type
        if (DOWNLOAD.equalsIgnoreCase(mode)) {
            response.setContentType("application/octet-stream");
        }
    }

    /**
     * 公共文件处理逻辑
     */
    private void processFileContent(ServletRequest request, ServletResponse response,
                                  GetObjectResult getResult, Options options, String mode,
                                  String path, String beanName) throws Exception {
        Metadata metadata = getResult.getMetadata();

        // 记录调试信息
        if (log.isDebugEnabled()) {
            log.debug("文件:{}大小: {}",
                    metadata == null ? "" : metadata.getFilename(),
                    metadata == null ? -1 : metadata.getFileSize());
        }

        // 读取文件内容并设置响应
        byte[] content = IoUtils.toByteArrayQuietly(getResult.getInputStream(), options.getString("charset"));
        response.setBody(content);
        response.setStatus(200);
        response.addHeader("Content-Type", getResult.getMediaType().toString());

        // 处理文件内容 - 使用SPI处理器链替代原有的硬编码过滤器
        handleFileContentWithProcessors(request, response, getResult, options, mode, path, beanName);
        // 将options的mode注册到request
        request.setAttribute("mode", mode);
    }

    /**
     * 使用SPI处理器链处理文件内容
     * 全新实现，通过SPI处理不同的mode
     */
    private void handleFileContentWithProcessors(ServletRequest request, ServletResponse response,
            GetObjectResult getResult, Options options, String mode,
            String path, String beanName) throws Exception {

        Metadata metadata = getResult.getMetadata();
        MediaType mediaType = getResult.getMediaType();

        // 创建处理器上下文
        FileStorageProcessorContext processorContext = new FileStorageProcessorContext()
                .setRequest(request)
                .setResponse(response)
                .setFileStorageFactory(fileStorageFactory)
                .setFileStorage(fileStorageFactory.get(beanName))
                .setGetObjectResult(getResult)
                .setMetadata(metadata)
                .setMediaType(mediaType)
                .setOptions(options)
                .setMode(mode)
                .setPath(path)
                .setBeanName(beanName)
                .setUrl(request.getUrl());

        ServiceProvider<FileStorageProcessor> serviceProvider = ServiceProvider.of(FileStorageProcessor.class);
        FileStorageProcessor fileStorageProcessor = serviceProvider.getExtension(mode);
        if (null != fileStorageProcessor) {
            fileStorageProcessor.process(processorContext);
        }
    }

    /**
     * 从缓存获取BucketAddress，避免重复解析
     */
    private BucketAddress getBucketAddressFromCache(String url) {
        return new BucketAddress(url, fileStorageFactory.getFileStorageSetting().getSettings());
    }

    /**
     * 从缓存获取Bean名称，避免重复查找
     */
    private String getBeanFileStorageBeanFromCache(BucketAddress bucketAddress) {
        String[] bucketList = bucketAddress.getBucketList();
        for (String bucket : bucketList) {
            String normalizedBucket = StringUtils.startWithAppend(bucket, SYMBOL_LEFT_SLASH);
            if (fileStorageFactory.get(normalizedBucket) != null) {
                return bucket;
            }

            normalizedBucket = StringUtils.removeStart(bucket, SYMBOL_LEFT_SLASH);
            if (fileStorageFactory.get(normalizedBucket) != null) {
                return normalizedBucket;
            }
        }
        return SYMBOL_LEFT_SLASH;
    }
    /**
     * 清理缓存（当配置更新时调用）
     */
    private void clearCaches() {
        bucketAddressCache.clear();
        beanNameCache.clear();
        FLASH_IMAGE_RECORDS.clear();
        if (log.isDebugEnabled()) {
            log.debug("清理FileStorageServletFilter缓存");
        }
    }

    @Override
    public String getFilterName() {
        return "FileStorageServletFilter";
    }

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public String getDescription() {
        return "文件存储Servlet过滤器 - 提供文件预览、下载等功能，支持SPI扩展";
    }

    @Override
    public boolean matches(ServletRequest request) {
        return true;
    }

    @Override
    public void destroy() {
        try {
            // 清理缓存
            clearCaches();

            // 清理闪图访问记录目录
            clearFlashAccessDir();

            // 重置初始化状态
            initialized.set(false);

            log.info("FileStorageServletFilter已销毁");

        } catch (Exception e) {
            log.error("FileStorageServletFilter销毁失败", e);
        }
    }

    /**
     * 清理闪图访问记录目录
     */
    private void clearFlashAccessDir() {
        try {
            java.io.File dir = new java.io.File(FLASH_ACCESS_DIR);
            if (dir.exists() && dir.isDirectory()) {
                java.io.File[] files = dir.listFiles();
                if (files != null) {
                    for (java.io.File file : files) {
                        if (file.getName().endsWith(".accessed")) {
                            file.delete();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清理闪图访问记录目录失败", e);
        }
    }

    @Override
    public FileStorageFactory.FileStorageSetting getConfigurationObject() {
        return fileStorageFactory.getFileStorageSetting();
    }

    @Override
    public void updateConfigurationObject(FileStorageFactory.FileStorageSetting config) {
        fileStorageFactory.setFileStorageSetting(config);
    }

    /**
     * 添加文件存储
     */
    public void addFileStorage(String name, FileStorage fileStorage) {
        fileStorageFactory.addFileStorage(name, fileStorage);
    }

    /**
     * 移除文件存储
     */
    public boolean containsFileStorage(String name) {
        return fileStorageFactory.containsFileStorage(name);
    }

    /**
     * 移除文件存储
     */
    public void removeFileStorage(String name) {
        fileStorageFactory.removeFileStorage(name);
    }

    /**
     * 获取文件存储
     */
    public FileStorage getFileStorage(String bucket) {
        return fileStorageFactory.getFileStorage(bucket);
    }

    /**
     * 发送XML格式的错误响应
     */
    private void sendXmlErrorResponse(ServletResponse response, int statusCode, String message, String url) {
        try {
            // 构建错误信息
            StringBuilder xmlBuilder = new StringBuilder();
            xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            xmlBuilder.append("<error>");
            xmlBuilder.append("<code>").append(statusCode).append("</code>");
            xmlBuilder.append("<message><![CDATA[").append(message).append("]]></message>");
            xmlBuilder.append("<url><![CDATA[").append(url).append("]]></url>");
            xmlBuilder.append("<timestamp>").append(System.currentTimeMillis()).append("</timestamp>");

            // 添加签名
            Map<String, String> params = new HashMap<>();
            params.put("code", String.valueOf(statusCode));
            params.put("message", message);
            params.put("url", url);
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            String signature = FileStorageSignatureUtils.generateSignature(params);
            xmlBuilder.append("<signature>").append(signature).append("</signature>");

            xmlBuilder.append("</error>");

            response.setStatus(statusCode);
            response.setContentType("application/xml;charset=UTF-8");
            response.setBody(xmlBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("发送XML错误响应失败", e);
            // 如果XML构建失败，发送简单的错误信息
            response.setStatus(statusCode);
            response.setContentType("text/plain;charset=UTF-8");
            response.setBody(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.HTTPS};
    }

    /**
     * 判断是否为短链接格式
     * 短链接格式：/s/{shareId}
     *
     * @param url 请求 URL
     * @return 是否为短链接格式
     */
    private boolean isShortLinkUrl(String url) {
        if (StringUtils.isEmpty(url)) {
            return false;
        }
        // 移除查询参数
        int queryIndex = url.indexOf('?');
        String path = queryIndex > 0 ? url.substring(0, queryIndex) : url;
        // 检查是否以 /s/ 开头且后面有内容
        return path.startsWith("/s/") && path.length() > 3;
    }
}
