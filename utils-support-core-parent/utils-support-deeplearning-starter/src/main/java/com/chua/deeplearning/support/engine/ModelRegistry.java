package com.chua.deeplearning.support.engine;

import ai.djl.translate.Translator;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 模型注册表。
 * <p>子模块通过 {@link #register(String, String, Class, Class, Class, String)} 静态注册模型，
 * 主框架启动时扫描 SPI 触发注册；Translator 类在首次推理时懒加载。</p>
 * <p>模型路径解析顺序：绝对路径 → 模型根目录相对路径 → classpath/JAR 内嵌资源（必要时解压到临时目录）。</p>
 *
 * @since 4.0.0.42
 * @author CH
 */
@Slf4j
public final class ModelRegistry {

    /**
     * JVM 属性：临时目录
     */
    private static final String SYS_TMPDIR = "java.io.tmpdir";

    /**
     * 深度学习模型缓存目录名
     */
    private static final String CACHE_DIR_NAME = "chua-dl-models";

    /**
     * 默认模型根目录
     */
    private static final String DEFAULT_MODEL_ROOT_DIR = "models/onnx";

    /**
     * 系统属性：模型根目录
     */
    private static final String PROP_MODEL_ROOT_DIR = "deeplearning.model.root-dir";

    /**
     * 系统属性：模型下载缓存目录
     */
    private static final String PROP_MODEL_CACHE_DIR = "deeplearning.model.cache-dir";

    /** REGISTRY */
    private static final Map<String, Entry> REGISTRY = new ConcurrentHashMap<>();
    /** CLASSPATCACHE */
    private static final Map<String, Path> CLASSPATH_CACHE = new ConcurrentHashMap<>();
    /** 模型根dir */
    private static volatile String modelRootDir = initModelRootDir();
    /** extract根 */
    private static volatile Path extractRoot = initExtractRoot();
    /** downloader */
    private static volatile ModelDownloader downloader = new DefaultModelDownloader();

    /**
      * 类路径 资源前缀
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
      * 类路径 模型资源子路径：onnx
     */
    private static final String CLASSPATH_MODEL_ONNX = "models/onnx/";

    /**
      * 类路径 模型资源子路径：pytorch
     */
    private static final String CLASSPATH_MODEL_PYTORCH = "models/pytorch/";

    /**
      * 类路径 模型资源子路径：飞桨
     */
    private static final String CLASSPATH_MODEL_PADDLE = "models/paddle/";

    /**
      * 类路径 模型资源子路径：tensorflow
     */
    private static final String CLASSPATH_MODEL_TENSORFLOW = "models/tensorflow/";

    /**
      * 类路径 模型资源子路径：safetensors
     */
    private static final String CLASSPATH_MODEL_SAFETENSORS = "models/safetensors/";

    /**
      * 类路径 模型资源子路径前缀
     */
    private static final String CLASSPATH_MODEL_PREFIX = "models/";

    /**
     * 模型文件后缀名列表
     */
    private static final String[] MODEL_SUFFIXES = {".onnx", ".pt", ".pth", ".pdmodel", ".pb", ".safetensors"};

    /**
     * 临时文件前缀
     */
    private static final String TEMP_FILE_PREFIX = "dl-";

    /**
     * 部分文件后缀
     */
    private static final String PART_SUFFIX = ".part";

    /**
     * 默认连接超时时间（毫秒）
     */
    private static final int DEFAULT_CONNECT_TIMEOUT = 15000;

    /**
     * 默认读取超时时间（毫秒）
     */
    private static final int DEFAULT_READ_TIMEOUT = 30000;

    /**
      * 默认 用户-智能体
     */
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0";

    /**
      * HTTP 位置 头
     */
    private static final String HEADER_LOCATION = "Location";

    /**
     * 解压缓冲区大小
     */
    private static final int UNZIP_BUFFER_SIZE = 8192;

    /**
     * 下载目录名
     */
    private static final String DOWNLOAD_DIR = "download";

    /**
     * ONNX 附加文件后缀
     */
    private static final String ONNX_EXTRA_FILE_SUFFIX = ".extra_file";

    /**
      * ONNX 外部权重后缀（多文件模型：模型.onnx + 模型.onnx_数据）
     */
    private static final String ONNX_DATA_SUFFIX = "_data";

    /**
     * Safetensors 索引文件后缀
     */
    private static final String SAFETENSORS_INDEX_SUFFIX = ".index.json";

    /**
     * 重定向 HTTP 状态码下限
     */
    private static final int HTTP_REDIRECT_MIN = 300;

    /**
     * 重定向 HTTP 状态码上限
     */
    private static final int HTTP_REDIRECT_MAX = 400;

    /**
     * 模型注册条目。
     *
     * @param modelId             模型标识
     * @param translatorClassName DJL Translator 全限定类名
     * @param inputType           输入类型
     * @param outputType          输出类型
     * @param capabilityInterface 能力接口
     * @param relativePath        相对模型根目录 / 类路径 路径，可为 空
     * @param downloadUrl         远程下载地址，空表示不下载
     * @param downloadMirrors     备用下载地址列表（主地址失败时依次尝试），可为 空
     * @param compress            下载文件是否为压缩包
      * @param downloadFileName    压缩包内目标文件名（compress=true 时生效）
      * @param hardwareConfig      硬件配置（设备/显存上限/推荐标记），可为 空
      */
    public record Entry(String modelId, String translatorClassName,
                        Class<?> inputType, Class<?> outputType,
                        Class<?> capabilityInterface, String relativePath,
                        String downloadUrl, List<String> downloadMirrors,
                        boolean compress, String downloadFileName,
                        com.chua.deeplearning.support.model.HardwareConfig hardwareConfig) {

        /**
         * 是否作为对应能力类型的推荐模型（便捷方法）。
         *
         * @return true 表示推荐
         */
        public boolean isRecommended() {
            return hardwareConfig != null && hardwareConfig.recommended();
        }
    }

    /**
     * 按类名注册模型（推荐，避免注册期加载 Translator）。
     *
     * @param modelId             模型标识
     * @param translatorClassName Translator 全限定类名
     * @param inputType           输入类型
     * @param outputType          输出类型
     * @param capabilityInterface 能力接口
     * @param relativePath        相对路径或 类路径 路径
     */
    public static void register(String modelId, String translatorClassName,
                                Class<?> inputType, Class<?> outputType,
                                Class<?> capabilityInterface, String relativePath) {
        REGISTRY.put(modelId, new Entry(modelId, translatorClassName, inputType, outputType, capabilityInterface, relativePath, null, null, false, null, null));
        log.debug("[deeplearning-engine] ModelRegistry register: {} -> {}", modelId, translatorClassName);
    }

    /**
     * 按类名注册模型（含远程下载信息）。
     *
     * @param modelId             模型标识
     * @param translatorClassName Translator 全限定类名
     * @param inputType           输入类型
     * @param outputType          输出类型
     * @param capabilityInterface 能力接口
     * @param relativePath        相对路径或 类路径 路径
     * @param downloadUrl         远程下载地址
     * @param compress            是否压缩包
     * @param downloadFileName    压缩包内目标文件名
     */
    public static void register(String modelId, String translatorClassName,
                                Class<?> inputType, Class<?> outputType,
                                Class<?> capabilityInterface, String relativePath,
                                String downloadUrl, boolean compress, String downloadFileName) {
        register(modelId, translatorClassName, inputType, outputType, capabilityInterface, relativePath,
                downloadUrl, null, compress, downloadFileName);
    }

    /**
     * 按类名注册模型（含远程下载信息与备用镜像地址）。
     *
     * @param modelId             模型标识
     * @param translatorClassName Translator 全限定类名
     * @param inputType           输入类型
     * @param outputType          输出类型
     * @param capabilityInterface 能力接口
     * @param relativePath        相对路径或 类路径 路径
     * @param downloadUrl         远程下载地址
     * @param downloadMirrors     备用下载地址列表（主地址失败时依次尝试），可为 空
     * @param compress            是否压缩包
     * @param downloadFileName    压缩包内目标文件名
     */
    public static void register(String modelId, String translatorClassName,
                                Class<?> inputType, Class<?> outputType,
                                Class<?> capabilityInterface, String relativePath,
                                String downloadUrl, List<String> downloadMirrors,
                                boolean compress, String downloadFileName) {
        register(modelId, translatorClassName, inputType, outputType, capabilityInterface, relativePath,
                downloadUrl, downloadMirrors, compress, downloadFileName, null);
    }

    /**
     * 按类名注册模型（含远程下载信息与备用镜像地址、硬件配置）。
     *
     * @param modelId             模型标识
     * @param translatorClassName Translator 全限定类名
     * @param inputType           输入类型
     * @param outputType          输出类型
     * @param capabilityInterface 能力接口
     * @param relativePath        相对路径或 类路径 路径
     * @param downloadUrl         远程下载地址
     * @param downloadMirrors     备用下载地址列表（主地址失败时依次尝试），可为 空
     * @param compress            是否压缩包
     * @param downloadFileName    压缩包内目标文件名
     * @param hardwareConfig      硬件配置（设备/显存上限/推荐标记），可为 空
     */
    public static void register(String modelId, String translatorClassName,
                                Class<?> inputType, Class<?> outputType,
                                Class<?> capabilityInterface, String relativePath,
                                String downloadUrl, List<String> downloadMirrors,
                                boolean compress, String downloadFileName,
                                com.chua.deeplearning.support.model.HardwareConfig hardwareConfig) {
        List<String> mirrors = resolveMirrors(downloadUrl, downloadMirrors);
        REGISTRY.put(modelId, new Entry(modelId, translatorClassName, inputType, outputType, capabilityInterface,
                relativePath, downloadUrl, mirrors, compress, downloadFileName, hardwareConfig));
        log.debug("[deeplearning-engine] ModelRegistry register: {} -> {} (downloadUrl={}, mirrors={})",
                modelId, translatorClassName, downloadUrl, mirrors);
    }

    /**
     * 解析备用镜像地址列表。
     *
     * <p>主地址为 huggingface.co 且未显式提供镜像时，自动追加 hf-mirror.com 备用镜像
     * （国内可直接访问），保证下载失败时可自动切换。</p>
     *
     * @param downloadUrl     主下载地址
     * @param downloadMirrors 显式备用地址，可为 空
     * @return 镜像地址列表，可为 空
     */
    private static List<String> resolveMirrors(String downloadUrl, List<String> downloadMirrors) {
        List<String> mirrors = downloadMirrors == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(downloadMirrors);
        if (downloadUrl != null && downloadUrl.contains("huggingface.co") && mirrors.stream().noneMatch(m -> m != null && m.contains("hf-mirror.com"))) {
            mirrors.add(downloadUrl.replace("huggingface.co", "hf-mirror.com"));
        }
        return mirrors.isEmpty() ? null : List.copyOf(mirrors);
    }

    /**
      * 按 类 注册模型。
     *
     * @param modelId             模型标识
     * @param translatorClass     Translator 类
     * @param inputType           输入类型
     * @param outputType          输出类型
     * @param capabilityInterface 能力接口
     * @param relativePath        相对路径
     */
    public static void register(String modelId, Class<?> translatorClass,
                                Class<?> inputType, Class<?> outputType,
                                Class<?> capabilityInterface, String relativePath) {
        register(modelId, translatorClass.getName(), inputType, outputType, capabilityInterface, relativePath);
    }

    /**
      * 按 类 注册模型（无相对路径）。
     *
     * @param modelId             模型标识
     * @param translatorClass     Translator 类
     * @param inputType           输入类型
     * @param outputType          输出类型
     * @param capabilityInterface 能力接口
     */
    public static void register(String modelId, Class<?> translatorClass,
                                Class<?> inputType, Class<?> outputType,
                                Class<?> capabilityInterface) {
        register(modelId, translatorClass.getName(), inputType, outputType, capabilityInterface, null);
    }

    /**
     * 按模型标识查询。
     *
     * @param modelId 模型标识
     * @return 注册条目
     */
    public static Entry get(String modelId) {
        return REGISTRY.get(modelId);
    }

    /**
     * 获取全部注册项。
     *
     * @return 只读集合
     */
    public static Collection<Entry> getAll() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    /**
     * 按能力接口查询全部已注册模型。
     *
     * <p>capabilityInterface 为模型注册时声明的能力接口（如 {@code ImageDetector.class}、
     * {@code FeatureExtractor.class}、{@code ImageClassifier.class} 等），
     * 据此可精确枚举"具备某能力的所有模型"，供统一能力清单与前端展示使用。</p>
     *
     * @param capabilityInterface 能力接口（可为 空，返回全部）
     * @return 匹配的注册条目列表
     */
    public static List<Entry> getAllByCapability(Class<?> capabilityInterface) {
        if (capabilityInterface == null) {
            return new ArrayList<>(REGISTRY.values());
        }
        return REGISTRY.values().stream()
                .filter(e -> e.capabilityInterface() != null)
                .filter(e -> capabilityInterface.equals(e.capabilityInterface())
                        || capabilityInterface.isAssignableFrom(e.capabilityInterface()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
      * 按能力接口查询全部已注册模型 标识。
     *
     * @param capabilityInterface 能力接口
     * @return 模型 标识 列表
     */
    public static List<String> getModelIdsByCapability(Class<?> capabilityInterface) {
        return getAllByCapability(capabilityInterface).stream()
                .map(Entry::modelId)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 判断指定能力接口下是否已注册模型。
     *
     * @param capabilityInterface 能力接口
     * @return true 表示存在
     */
    public static boolean hasCapability(Class<?> capabilityInterface) {
        return !getAllByCapability(capabilityInterface).isEmpty();
    }

    /**
     * 初始化模型根目录：优先读系统属性 {@code deeplearning.model.root-dir}，
     * 未配置时使用默认 {@code models/onnx}（相对当前工作目录）。
     *
     * @return 模型根目录
     */
    private static String initModelRootDir() {
        String prop = System.getProperty(PROP_MODEL_ROOT_DIR);
        return (prop != null && !prop.isBlank()) ? prop.trim() : DEFAULT_MODEL_ROOT_DIR;
    }

    /**
     * 初始化模型下载缓存目录：优先读系统属性 {@code deeplearning.model.cache-dir}，
     * 未配置时使用 {@code %TEMP%/chua-dl-models}。
     *
     * @return 缓存目录
     */
    private static Path initExtractRoot() {
        String prop = System.getProperty(PROP_MODEL_CACHE_DIR);
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop.trim());
        }
        return Paths.get(System.getProperty(SYS_TMPDIR), CACHE_DIR_NAME);
    }

    /**
     * 设置模型根目录（文件系统）。
     *
     * @param dir 根目录
     */
    public static void setModelRootDir(String dir) {
        modelRootDir = dir;
    }

    /**
     * 获取模型根目录。
     *
     * @return 根目录
     */
    public static String getModelRootDir() {
        return modelRootDir;
    }

    /**
      * 设置 类路径 模型解压缓存目录。
     *
     * @param dir 缓存目录
     */
    public static void setExtractRoot(Path dir) {
        extractRoot = dir;
    }

    /**
     * 解析模型路径。
     * <ol>
     *   <li>相对路径 / 绝对路径（文件系统）</li>
     *   <li>classpath: 前缀或 classpath 资源（含 JAR 内嵌，自动解压到临时目录）</li>
     *   <li>{modelRoot}/{modelId}.{ext}</li>
     *   <li>递归扫描 modelRoot</li>
     *   <li>Entry 中配置 downloadUrl 时，自动下载到缓存目录（compress=true 时解压 zip）</li>
     * </ol>
     *
     * @param modelId 模型标识
     * @return 本地可读 路径（类路径 资源会解压到缓存）
     */
    public static Path resolveModelPath(String modelId) {
        Entry entry = REGISTRY.get(modelId);
        Path root = Paths.get(modelRootDir);

        if (entry != null && entry.relativePath() != null && !entry.relativePath().isBlank()) {
            Path resolved = resolveConfiguredPath(entry.relativePath());
            if (resolved != null) {
                return resolved;
            }
        }

        // 带 downloadUrl 的多文件/非标准扩展名模型：优先命中下载缓存目录（download/<modelId>/<file>），
 // 避免在所有本地 降级 失败后仍尝试联网下载。
        if (entry != null && entry.downloadUrl() != null && !entry.downloadUrl().isBlank()) {
            Path dlTarget = cachedDownloadTarget(modelId, entry);
            if (dlTarget != null) {
                return dlTarget;
            }
        }

        String[] suffixes = MODEL_SUFFIXES;
        for (String suffix : suffixes) {
            Path exact = root.resolve(modelId + suffix);
            if (Files.exists(exact)) {
                return exact;
            }
            Path fromCp = resolveClasspathResource(modelId + suffix);
            if (fromCp != null) {
                return fromCp;
            }
            Path fromCpModels = resolveClasspathResource(CLASSPATH_MODEL_PREFIX + modelId + suffix);
            if (fromCpModels != null) {
                return fromCpModels;
            }
            Path fromCpOnnx = resolveClasspathResource(CLASSPATH_MODEL_ONNX + modelId + suffix);
            if (fromCpOnnx != null) {
                return fromCpOnnx;
            }
            Path fromCpPytorch = resolveClasspathResource(CLASSPATH_MODEL_PYTORCH + modelId + suffix);
            if (fromCpPytorch != null) {
                return fromCpPytorch;
            }
            Path fromCpPaddle = resolveClasspathResource(CLASSPATH_MODEL_PADDLE + modelId + suffix);
            if (fromCpPaddle != null) {
                return fromCpPaddle;
            }
            Path fromCpTf = resolveClasspathResource(CLASSPATH_MODEL_TENSORFLOW + modelId + suffix);
            if (fromCpTf != null) {
                return fromCpTf;
            }
            Path fromCpSafetensors = resolveClasspathResource(CLASSPATH_MODEL_SAFETENSORS + modelId + suffix);
            if (fromCpSafetensors != null) {
                return fromCpSafetensors;
            }
        }

        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            for (String suffix : suffixes) {
                                if (name.equalsIgnoreCase(modelId + suffix)) {
                                    return true;
                                }
                            }
                            return false;
                        })
                        .findFirst()
                        .orElse(root.resolve(modelId + ".onnx"));
            } catch (Exception ex) {
                return root.resolve(modelId + ".onnx");
            }
        }

        // 本地全部未命中，尝试远程下载
        Path downloaded = tryDownloadFromRemote(modelId, entry);
        if (downloaded != null) {
            return downloaded;
        }

        return root.resolve(modelId + ".onnx");
    }

    /**
     * 从下载缓存目录解析已缓存模型文件（如 GGUF）。
     *
     * @param modelId 模型标识
     * @param entry   注册条目
     * @return 命中缓存的路径；未命中返回 空
     */
    private static Path cachedDownloadTarget(String modelId, Entry entry) {
        Path downloadDir = extractRoot.resolve(DOWNLOAD_DIR).resolve(modelId);
        String fileName = entry.downloadFileName() != null && !entry.downloadFileName().isBlank()
                ? entry.downloadFileName()
                : entry.downloadUrl().substring(entry.downloadUrl().lastIndexOf('/') + 1);
        Path target = downloadDir.resolve(fileName);
        try {
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                log.info("[deeplearning-engine] 命中 GGUF 下载缓存: {} -> {}", modelId, target);
                return target;
            }
        } catch (Exception e) {
            log.debug("[deeplearning-engine] 缓存检查失败: {} -> {}", modelId, e.getMessage());
        }
        return null;
    }

    /**
      * 尝试从 Entry 配置的 downloadurl 下载模型。
     *
     * <p>依次尝试主地址与备用镜像地址（{@link Entry#downloadMirrors()}），
      * 任一地址下载成功即返回，全部失败返回 空。</p>
     *
     * @param modelId 模型标识
     * @param entry   注册条目
     * @return 下载后的本地路径，下载失败返回 空
     */
    private static Path tryDownloadFromRemote(String modelId, Entry entry) {
        if (entry == null || entry.downloadUrl() == null || entry.downloadUrl().isBlank()) {
            return null;
        }
        List<String> urls = new ArrayList<>();
        urls.add(entry.downloadUrl());
        if (entry.downloadMirrors() != null) {
            for (String mirror : entry.downloadMirrors()) {
                if (mirror != null && !mirror.isBlank() && !urls.contains(mirror)) {
                    urls.add(mirror);
                }
            }
        }
        Path downloadDir = extractRoot.resolve(DOWNLOAD_DIR).resolve(modelId);
        String fileName = entry.downloadFileName() != null && !entry.downloadFileName().isBlank()
                ? entry.downloadFileName()
                : entry.downloadUrl().substring(entry.downloadUrl().lastIndexOf('/') + 1);
        if (fileName.isBlank()) {
            fileName = modelId + MODEL_SUFFIXES[0];
        }
        Path target = downloadDir.resolve(fileName);

        try {
            if (Files.exists(target) && Files.size(target) > 0) {
                log.info("[deeplearning-engine] 模型已存在缓存，跳过下载: {} -> {}", modelId, target);
                return finishDownload(modelId, entry, urls.get(0), target, downloadDir);
            }
        } catch (IOException e) {
            log.debug("[deeplearning-engine] 模型缓存检查失败: {} -> {}", modelId, e.getMessage());
        }

        Throwable lastError = null;
        for (String url : urls) {
            try {
                log.info("[deeplearning-engine] 开始下载模型: {} -> {}", modelId, url);
                Files.createDirectories(downloadDir);
                Path downloaded = downloader.download(url, target);
                log.info("[deeplearning-engine] 模型下载完成: {} -> {}", modelId, downloaded);
                return finishDownload(modelId, entry, url, downloaded, downloadDir);
            } catch (Exception e) {
                lastError = e;
                log.warn("[deeplearning-engine] 下载模型失败（将尝试下一个地址）: {} -> {}: {}", modelId, url, e.getMessage());
            }
        }
        log.warn("[deeplearning-engine] 远程下载模型全部失败: {} -> {}: {}",
                modelId, entry.downloadUrl(), lastError == null ? "未知错误" : lastError.getMessage());
        return null;
    }

    /**
     * 完成下载后续处理：附加文件下载、解压等。
     *
     * @param modelId    模型标识
     * @param entry      注册条目
     * @param baseUrl    实际使用的下载地址
     * @param target     下载后的文件
     * @param downloadDir 下载目录
     * @return 最终可加载路径
     */
    private static Path finishDownload(String modelId, Entry entry, String baseUrl, Path target, Path downloadDir) {
        try {
 // ONNX .onnx 可能附带同名权重文件：优先下载标准命名的 .onnx_数据（多文件导出），
 // 其次兼容遗留的 .extra_文件 命名
            if (target.toString().toLowerCase().endsWith(MODEL_SUFFIXES[0])) {
                String fileName = target.getFileName().toString();
                String[] extraSuffixes = {
                        fileName + ONNX_DATA_SUFFIX,          // model.onnx -> model.onnx_data
                        fileName.substring(0, fileName.length() - MODEL_SUFFIXES[0].length()) + ONNX_DATA_SUFFIX + ".onnx", // 模型.onnx_数据（兜底）
                        fileName + ONNX_EXTRA_FILE_SUFFIX, // 模型.onnx.extra_文件（兼容）
                };
                for (String extraName : extraSuffixes) {
                    Path extraFile = target.resolveSibling(extraName);
                    if (Files.exists(extraFile) && Files.size(extraFile) > 0) {
                        log.info("[deeplearning-engine] ONNX 附加文件已存在: {}", extraFile);
                        continue;
                    }
                    String extraUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + extraName;
                    try {
                        log.info("[deeplearning-engine] 开始下载 ONNX 附加文件: {} -> {}", modelId, extraUrl);
                        downloader.download(extraUrl, extraFile);
                        if (Files.exists(extraFile) && Files.size(extraFile) > 0) {
                            log.info("[deeplearning-engine] ONNX 附加文件下载完成: {} -> {}", modelId, extraFile);
                        } else {
                            log.warn("[deeplearning-engine] ONNX 附加文件为空，继续尝试: {}", extraUrl);
                        }
                    } catch (Exception e) {
                        log.warn("[deeplearning-engine] 下载 ONNX 附加文件失败（部分模型不需要）: {} -> {}: {}", modelId, extraUrl, e.getMessage());
                    }
                }
            }

 // Safetensors 分片模型可能附带同名 .safetensors.索引.json，自动下载
            if (target.toString().toLowerCase().endsWith(MODEL_SUFFIXES[5])) {
                Path indexFile = target.resolveSibling(target.getFileName() + SAFETENSORS_INDEX_SUFFIX);
                if (!Files.exists(indexFile) || Files.size(indexFile) == 0) {
                    String indexUrl = baseUrl + SAFETENSORS_INDEX_SUFFIX;
                    try {
                        log.info("[deeplearning-engine] 开始下载 Safetensors 索引文件: {} -> {}", modelId, indexUrl);
                        downloader.download(indexUrl, indexFile);
                        log.info("[deeplearning-engine] Safetensors 索引文件下载完成: {} -> {}", modelId, indexFile);
                    } catch (Exception e) {
                        log.debug("[deeplearning-engine] 下载 Safetensors 索引文件失败（单文件模型无索引）: {} -> {}: {}", modelId, indexUrl, e.getMessage());
                    }
                }
            }

            if (entry.compress() && target.toString().toLowerCase().endsWith(".zip")) {
                Path unzipDir = downloadDir.resolve(modelId + "_unzipped");
                if (Files.exists(unzipDir)) {
                    log.info("[deeplearning-engine] 解压目录已存在，跳过解压: {}", unzipDir);
                } else {
                    log.info("[deeplearning-engine] 开始解压模型: {} -> {}", target, unzipDir);
                    unzip(target, unzipDir);
                    log.info("[deeplearning-engine] 模型解压完成: {}", unzipDir);
                }
                if (entry.downloadFileName() != null && !entry.downloadFileName().isBlank()) {
                    Path resolved = unzipDir.resolve(entry.downloadFileName());
                    if (Files.exists(resolved)) {
                        return resolved;
                    }
                }
                return unzipDir;
            }

            return target;
        } catch (Exception e) {
            log.warn("[deeplearning-engine] 模型下载后处理失败: {} -> {}: {}", modelId, baseUrl, e.getMessage());
            return target;
        }
    }

    /**
      * 解析配置中的相对/绝对/类路径 路径。
     *
     * @param configured 配置路径
     * @return 本地 路径，找不到返回 空
     */
    public static Path resolveConfiguredPath(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String path = configured.trim().replace('\\', '/');
        if (path.startsWith(CLASSPATH_PREFIX)) {
            return resolveClasspathResource(path.substring(CLASSPATH_PREFIX.length()).replaceFirst("^/+", ""));
        }

        Path absolute = Paths.get(configured);
        if (absolute.isAbsolute() && Files.exists(absolute)) {
            return absolute;
        }

        Path underRoot = Paths.get(modelRootDir).resolve(configured);
        if (Files.exists(underRoot)) {
            return underRoot;
        }

        // 相对当前工作目录
        if (Files.exists(absolute)) {
            return absolute.toAbsolutePath().normalize();
        }

 // 类路径 多种前缀尝试
        Path cp1 = resolveClasspathResource(path);
        if (cp1 != null) {
            return cp1;
        }
        Path cp2 = resolveClasspathResource(CLASSPATH_MODEL_PREFIX + path);
        if (cp2 != null) {
            return cp2;
        }
        Path cp3 = resolveClasspathResource(CLASSPATH_MODEL_ONNX + path);
        if (cp3 != null) {
            return cp3;
        }
        Path cp4 = resolveClasspathResource(CLASSPATH_MODEL_PYTORCH + path);
        if (cp4 != null) {
            return cp4;
        }
        Path cp5 = resolveClasspathResource(CLASSPATH_MODEL_PADDLE + path);
        if (cp5 != null) {
            return cp5;
        }
        Path cp6 = resolveClasspathResource(CLASSPATH_MODEL_TENSORFLOW + path);
        if (cp6 != null) {
            return cp6;
        }
        Path cp7 = resolveClasspathResource(CLASSPATH_MODEL_SAFETENSORS + path);
        if (cp7 != null) {
            return cp7;
        }
        // 仅文件名
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            Path byName = resolveClasspathResource(path.substring(slash + 1));
            if (byName != null) {
                return byName;
            }
        }
        return null;
    }

    /**
      * 从 类路径 / JAR 解析资源；文件: 直接返回，jar: 解压到缓存。
     *
     * @param resourcePath 资源路径（无 类路径: 前缀）
     * @return 本地 路径 或 空
     */
    public static Path resolveClasspathResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        String normalized = resourcePath.replace('\\', '/').replaceFirst("^/+", "");
        Path cached = CLASSPATH_CACHE.get(normalized);
        if (cached != null && Files.exists(cached)) {
            return cached;
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ModelRegistry.class.getClassLoader();
        }
        URL url = loader.getResource(normalized);
        if (url == null) {
            url = ModelRegistry.class.getClassLoader().getResource(normalized);
        }
        if (url == null) {
            return null;
        }

        try {
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                Path filePath = Paths.get(url.toURI());
                if (Files.exists(filePath)) {
                    CLASSPATH_CACHE.put(normalized, filePath);
                    return filePath;
                }
            }
            Path extracted = extractClasspathResource(normalized, url);
            CLASSPATH_CACHE.put(normalized, extracted);
            return extracted;
        } catch (Exception ex) {
            log.warn("[deeplearning-engine] classpath 模型解析失败: {} -> {}", normalized, ex.getMessage());
            return null;
        }
    }

    /**
     * extract类路径resource
     *
     * @param resourcePath resource路径
     * @param url url
     * @return extract类路径resource的结果
     */
    private static Path extractClasspathResource(String resourcePath, URL url) throws IOException {
        Path target = extractRoot.resolve(resourcePath);
        Files.createDirectories(target.getParent());
        if (Files.exists(target) && Files.size(target) > 0 && isUpToDate(target, url)) {
            return target;
        }
        try (InputStream in = url.openStream()) {
            if (in == null) {
                throw new IOException("resource stream is null: " + resourcePath);
            }
            Path tmp = Files.createTempFile(target.getParent(), TEMP_FILE_PREFIX, PART_SUFFIX);
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                Files.deleteIfExists(tmp);
                throw ex;
            }
        }
        log.info("[deeplearning-engine] classpath 模型已解压: {} -> {}", resourcePath, target);
        return target;
    }

    /**
      * 校验已解压的缓存文件是否与 类路径 资源一致（按大小比对，避免模型 jar 更新后继续使用旧缓存）。
     *
     * <p>jar: 资源通过 {@link URL#openConnection()} 获取 {@code Content-Length}；
      * 文件: 资源直接比对文件大小。大小未知时保守返回 false，触发重新解压。</p>
     *
     * @param target 缓存文件
     * @param url    类路径 资源 URL
     * @return true 表示缓存与资源一致
     */
    private static boolean isUpToDate(Path target, URL url) {
        try {
            long remote;
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                Path src = Paths.get(url.toURI());
                remote = Files.size(src);
            } else {
                remote = url.openConnection().getContentLengthLong();
            }
            return remote > 0 && remote == Files.size(target);
        } catch (Exception ex) {
            log.debug("[deeplearning-engine] classpath 模型缓存校验失败: {}", ex.getMessage());
            return false;
        }
    }

    /**
      * 创建懒加载 itranslator 包装。
     *
     * @param modelId   模型标识
     * @param modelPath 模型路径
     * @return ITranslator
     */
    public static ITranslator<Object, Object> createTranslator(String modelId, Path modelPath) {
        return createTranslator(modelId, modelPath, null);
    }

    /**
     * 创建懒加载 Translator（可携带运行参数）。
     *
     * @param modelId  模型标识
     * @param modelPath 模型路径（可空，懒解析）
     * @param options  运行参数（阈值/iou阈值/输入大小/candidates 等，可空）
     * @return 懒加载 Translator
     */
    public static ITranslator<Object, Object> createTranslator(String modelId, Path modelPath, Map<String, Object> options) {
        Entry entry = REGISTRY.get(modelId);
        if (entry == null) {
            throw new IllegalStateException("Model not registered: " + modelId);
        }
        return new LazyDjlTranslator(modelId, modelPath, entry.translatorClassName(), options);
    }

    /**
      * 通过 SPI 扫描所有 模型registrar 并触发类加载/注册。
     */
    public static void discoverAll() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        String resource = "META-INF/extensions/" + ModelRegistrar.class.getName();
        try {
            Enumeration<URL> urls = loader.getResources(resource);
            int count = 0;
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (InputStream in = url.openStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        String className = line.contains("=") ? line.substring(line.indexOf('=') + 1).trim() : line;
                        try {
                            ReflectUtils.forName(className, loader);
                            count++;
                        } catch (Throwable ex) {
                            log.warn("[deeplearning-engine] ModelRegistrar load failed: {} -> {}", className, ex.getMessage());
                        }
                    }
                }
            }
            log.info("[deeplearning-engine] ModelRegistry SPI loaded {} registrar class(es), entries={}", count, REGISTRY.size());
        } catch (Exception e) {
            log.warn("[deeplearning-engine] ModelRegistrar discover error: {}", e.getMessage());
        }
    }

    /**
     * 懒加载 DJL Translator：首次推理时才实例化模型。
     */
    private static final class LazyDjlTranslator implements ITranslator<Object, Object>, AutoCloseable, DetectionConfigurable {

        /** 模型标识 */
        private final String modelId;
        /** 模型路径 */
        private final Path modelPath;
        /** Translatorclass名称 */
        private final String translatorClassName;
        /** 运行参数（阈值 等，首次实例化前可注入） */
        private volatile Map<String, Object> options;
        /** delegate */
        private volatile ITranslator<Object, Object> delegate;

        /**
          * 创建 lazydjltranslator 实例
         * @param modelId 模型标识
         * @param modelPath 路径
         * @param modelId 字符串
         * @param modelPath 模型路径
         * @param translatorClassName translator类名称
         * @return LazyDjlTranslator的结果
         */
        private LazyDjlTranslator(String modelId, Path modelPath, String translatorClassName) {
            this(modelId, modelPath, translatorClassName, null);
        }

        /**
          * 创建 lazydjltranslator 实例（携带运行参数）。
         *
         * @param modelId 模型标识
         * @param modelPath 模型路径
         * @param translatorClassName translator类名称
         * @param options 运行参数（可空）
         * @return LazyDjlTranslator的结果
         */
        private LazyDjlTranslator(String modelId, Path modelPath, String translatorClassName, Map<String, Object> options) {
            this.modelId = modelId;
            this.modelPath = modelPath;
            this.translatorClassName = translatorClassName;
            this.options = options;
        }

        /**
         * 注入运行参数（仅首次实例化前生效）。
         *
         * @param options 参数键值对
         */
        @Override
        public void configure(Map<String, Object> options) {
            if (options == null || options.isEmpty()) {
                return;
            }
            synchronized (this) {
                if (delegate != null) {
                    log.warn("[deeplearning-engine] 模型 {} 已初始化，运行参数注入被忽略: {}", modelId, options.keySet());
                    return;
                }
                this.options = options == null ? null : new java.util.LinkedHashMap<>(options);
            }
        }

        /**
         * Ensure
         *
         * @return ensure的结果
         */
        private ITranslator<Object, Object> ensure() {
            if (delegate == null) {
                synchronized (this) {
                    if (delegate == null) {
                        Path path = modelPath;
                        if (path == null || !Files.exists(path)) {
                            path = resolveModelPath(modelId);
                        }
                        Object translator = newTranslatorInstance(translatorClassName, options);
                        // options 中可注入 per-model 设备设置：device=auto|cpu|gpu|cuda
                        String deviceSetting = (options != null && options.get("device") != null)
                                ? String.valueOf(options.get("device")) : null;
                        if (translator instanceof ITranslator<?, ?> itranslator) {
 // 原生 itranslator：直接包装，不经过 DJL
                            delegate = new ITranslatorDelegate(modelId, path, itranslator);
                        } else {
                            Translator<?, ?> djlTranslator = (Translator<?, ?>) translator;
                            String engine = DjlModelFactory.resolveEngine(path);
                            delegate = new DjlModelTranslator(modelId, path, engine, deviceSetting, djlTranslator);
                        }
                    }
                }
            }
            return delegate;
        }

        @Override
        /** 名称 */
        public String name() {
            return modelId;
        }

        @Override
        /** Translate */
        public Object translate(Object input) {
            return ensure().translate(input);
        }

        @Override
        /** 关闭 */
        public void close() {
            if (delegate != null) {
                if (delegate instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        /**
         * 返回内部真实 Translator 实例（初始化后）。
         *
         * @return 已初始化的内部 Translator
         */
        public ITranslator<Object, Object> unwrap() {
            return ensure();
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * 新translatorinstance
     *
     * @param translatorClassName translator类名称
     * @return 新translatorinstance的结果
     */
    private static Object newTranslatorInstance(String translatorClassName) {
        return newTranslatorInstance(translatorClassName, null);
    }

    /**
      * 实例化 Translator：期权 非空时优先带参构造
     * （{@code ctor(DetectionConfiguration)} → {@code ctor(Map)}），均不可用则回退无参默认值。
     *
     * @param translatorClassName Translator 类名
     * @param options 运行参数（可空）
     * @return Translator 实例
     */
    private static Object newTranslatorInstance(String translatorClassName, Map<String, Object> options) {
        try {
            Class<?> translatorClass = ReflectUtils.forName(translatorClassName);
            if (translatorClass == null) {
 // 新版 reflect工具.for名称 吞掉 CNFE 返回 空；此处转译以维持原异常路径
                throw new ClassNotFoundException(translatorClassName);
            }
            if (options != null && !options.isEmpty()) {
                try {
                    java.lang.reflect.Constructor<?> cfgCtor =
                            translatorClass.getDeclaredConstructor(com.chua.deeplearning.support.ai.DetectionConfiguration.class);
                    com.chua.deeplearning.support.ai.DetectionConfiguration cfg =
                            new com.chua.deeplearning.support.ai.DetectionConfiguration.DetectionConfigurationBuilder()
                                    .systemOption(new java.util.LinkedHashMap<>(options))
                                    .build();
                    return cfgCtor.newInstance(cfg);
                } catch (ReflectiveOperationException noCfgCtor) {
                    try {
                        java.lang.reflect.Constructor<?> mapCtor =
                                translatorClass.getDeclaredConstructor(java.util.Map.class);
                        return mapCtor.newInstance(new java.util.LinkedHashMap<>(options));
                    } catch (ReflectiveOperationException noMapCtor) {
                        log.warn("[deeplearning-engine] Translator {} 不支持运行参数注入（缺少 DetectionConfiguration/Map 构造），使用默认值: {}",
                                translatorClassName, options.keySet());
                    }
                }
            }
            try {
 // 优先标准反射：JDK 17+ 方法处理 受模块访问限制，reflect工具.instantiate 可能静默返回 空
                return translatorClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException reflectionEx) {
                Object fallback = ReflectUtils.instantiate(translatorClass);
                if (fallback == null) {
                    throw new IllegalStateException(
                            "实例化 Translator 失败: " + translatorClassName
                                    + "（若提示缺少无参构造，请补充 public XxxTranslator() 或默认参数构造）",
                            reflectionEx);
                }
                return fallback;
            }
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Translator 类不可用: " + translatorClassName, ex);
        }
    }

    /**
     * 原生 {@link ITranslator} 包装器。
     *
     * <p>用于将实现自有 {@link ITranslator} 接口的翻译器（不依赖 DJL）适配为
     * {@link DjlModelTranslator} 兼容的委托。此类与 {@link DjlModelTranslator}
      * 具有相同的对外形态（名称/translate/关闭），便于 lazydjltranslator 统一持有。</p>
     */
    private static final class ITranslatorDelegate implements ITranslator<Object, Object>, AutoCloseable {

        /** 模型标识 */
        private final String modelId;
        private final ITranslator<?, ?> translator;

        /**
          * 创建 itranslatordelegate 实例
         * @param modelId 模型标识
         * @param modelPath 路径
         * @param translator itranslator
         * @param translator translator
         * @param modelPath 模型路径
         * @return ITranslatorDelegate的结果
         */
        private ITranslatorDelegate(String modelId, Path modelPath, ITranslator<?, ?> translator) {
            this.modelId = modelId;
            this.translator = translator;
            injectModelPath(translator, modelId, modelPath);
        }

        /**
         * 若原生 Translator 提供 {@code setModelPath(String)} 方法，则通过反射注入
          * Registry 中声明的相对路径（类路径 资源或下载缓存路径）。
         *
         * @param translator 原生 Translator 实例
         * @param modelId    模型 标识（降级：entry 路径为空时使用 模型标识.后缀）
         * @param modelPath  解析后的模型绝对路径
         */
        private static void injectModelPath(ITranslator<?, ?> translator, String modelId, Path modelPath) {
            if (translator == null) {
                return;
            }
            try {
                String value = null;
                Entry entry = REGISTRY.get(modelId);
                if (entry != null && entry.relativePath() != null && !entry.relativePath().isBlank()) {
                    // 1) 优先解析 entry.relativePath() 为文件系统绝对路径（classpath URL 转为 file path）
                    value = resolveToAbsolutePathString(entry.relativePath());
                }
                if (value == null && modelPath != null) {
                    value = modelPath.toString();
                }
                ReflectUtils.invoke(translator, "setModelPath", void.class, new Class<?>[]{String.class}, value);
                log.info("[deeplearning-engine] injectModelPath {} -> {}", modelId, value);
            } catch (Throwable ex) {
 // reflect工具.invoke 不抛 检查 异常（无 设置模型路径 时返回 空 或内部吞掉），
                // 该 translator 不接受注入或注入失败时静默跳过
                log.debug("[deeplearning-engine] injectModelPath failed for {}: {}", modelId, ex.getMessage());
            }
        }

        /**
          * 将 entry.relative路径() 解析为文件系统绝对路径字符串。
         *
         * <p>优先级：</p>
         * <ol>
         *   <li>如果传入参数已是绝对路径且存在 → 直接返回</li>
         *   <li>classpath: 前缀 → 通过 getResource 解析为 file URL</li>
         *   <li>裸相对路径 → 通过 getResource 解析为 file URL</li>
         *   <li>否则返回原字符串（让 translator 内部再尝试）</li>
         * </ol>
         * @param configured configured
         * @return resolve转为absolute路径字符串的结果
         */
        private static String resolveToAbsolutePathString(String configured) {
            if (configured == null || configured.isBlank()) {
                return null;
            }
            String rel = configured;
            if (rel.startsWith("classpath:")) {
                rel = rel.substring("classpath:".length());
            }
            try {
                java.net.URL url = Thread.currentThread().getContextClassLoader().getResource(rel);
                if (url == null) {
                    url = ModelRegistry.class.getClassLoader().getResource(rel);
                }
                if (url != null && "file".equals(url.getProtocol())) {
                    try {
                        return java.nio.file.Paths.get(url.toURI()).toString();
                    } catch (java.net.URISyntaxException ue) {
                        return new java.io.File(url.getPath()).getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) {
            }
 // 兜底：原样返回（部分 translator 支持 类路径: 前缀或工作目录相对路径）
            return configured;
        }

        @Override
        /** 名称 */
        public String name() {
            return modelId;
        }

        @Override
        @SuppressWarnings("unchecked")
        /**
         * Translate
         *
         * @param input 输入
         * @return translate的结果
         */
        public Object translate(Object input) {
            return ((ITranslator<Object, Object>) translator).translate(input);
        }

        @Override
        /** 关闭 */
        public void close() {
            if (translator instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }

        /**
         * 返回原生 Translator 实例。
         *
         * @return 原生 Translator
         */
        public ITranslator<?, ?> unwrap() {
            return translator;
        }
    }

    /**
     * 模型下载器。
     * @author CH
     * @since 4.0.0
     */
    @FunctionalInterface
    public interface ModelDownloader {

        /**
         * 下载 URL 到目标路径。
         *
         * @param url    远程地址
         * @param target 本地目标路径
         * @return 实际落盘路径
         * @throws Exception 下载异常
         */
        Path download(String url, Path target) throws Exception;
    }

    /**
      * 默认模型下载器（基于 httpurlconnection）。
     */
    public static final class DefaultModelDownloader implements ModelDownloader {

        @Override
        /** Download */
        public Path download(String url, Path target) throws Exception {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
                conn.setReadTimeout(DEFAULT_READ_TIMEOUT);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
                int code = conn.getResponseCode();
                if (code >= HTTP_REDIRECT_MIN && code < HTTP_REDIRECT_MAX) {
                    String loc = conn.getHeaderField(HEADER_LOCATION);
                    if (loc != null) {
                        return download(loc, target);
                    }
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("下载失败，HTTP " + code + ": " + url);
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = conn.getInputStream()) {
                    Path tmp = target.resolveSibling(target.getFileName() + PART_SUFFIX);
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return target;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    /**
     * 设置模型下载器。
     *
     * @param downloader 下载器
     */
    public static void setDownloader(ModelDownloader downloader) {
        ModelRegistry.downloader = downloader;
    }

    /**
      * 解压 压缩 到目标目录。
     *
     * @param zipPath 压缩 文件路径
     * @param target  目标目录
     * @throws IOException IO 异常
     */
    public static void unzip(Path zipPath, Path target) throws IOException {
        Files.createDirectories(target);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = target.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(target.normalize())) {
                    throw new IOException("Zip entry 跳出目标目录: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    try (OutputStream out = Files.newOutputStream(resolved)) {
                        byte[] buf = new byte[UNZIP_BUFFER_SIZE];
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
