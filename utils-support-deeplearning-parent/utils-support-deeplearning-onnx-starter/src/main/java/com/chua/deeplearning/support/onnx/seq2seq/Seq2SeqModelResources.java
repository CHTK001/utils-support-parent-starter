package com.chua.deeplearning.support.onnx.seq2seq;

import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Seq2Seq 模型资源定位器。
 * <p>模型文件获取优先级：<b>嵌入式（classpath jar）优先</b> → 本地缓存 →
 * <b>modelscope downloadUrl 下载</b>。缓存根目录取系统属性
 * {@code deeplearning.model.cache-dir}，缺省为 {@code %TEMP%/chua-dl-models}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class Seq2SeqModelResources {

    /**
     * JVM 属性：深度学习模型缓存目录。
     */
    private static final String PROP_CACHE_DIR = "deeplearning.model.cache-dir";

    /**
     * JVM 属性：临时目录。
     */
    private static final String PROP_TMPDIR = "java.io.tmpdir";

    /**
     * 默认缓存根目录名。
     */
    private static final String DEFAULT_CACHE_NAME = "chua-dl-models";

    /**
     * modelscope 下载基础地址。
     */
    private static final String MODEL_SCOPE_BASE = "https://www.modelscope.cn/models/%s/resolve/master/%s";

    /**
     * 连接超时。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 请求超时。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);

    /**
     * 用户代理。
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) chua-deeplearning/4.0.0.42";

    /** 创建 Seq2SeqModelResources 实例 */
    private Seq2SeqModelResources() {
    }

    /**
     * 解析模型缓存目录：
     * 已完整则直接返回；否则按嵌入式 → modelscope 下载补齐。
     *
     * @param def 模型定义
     * @return 存放全部模型文件的本地目录
     */
    public static Path resolve(Seq2SeqModelDefinition def) {
        Path target = cacheRoot().resolve(def.modelId());
        if (isComplete(def, target)) {
            return target;
        }
        if (extractEmbedded(def, target)) {
            return target;
        }
        downloadMissing(def, target);
        return target;
    }

    /**
     * 判断缓存目录是否已包含全部必需文件。
     *
     * @param def    模型定义
     * @param target 目标目录
     * @return true 表示文件齐全
     */
    private static boolean isComplete(Seq2SeqModelDefinition def, Path target) {
        if (!Files.isDirectory(target)) {
            return false;
        }
        for (String file : def.requiredFiles()) {
            Path p = target.resolve(file);
            if (!Files.isRegularFile(p)) {
                return false;
            }
            try {
                if (Files.size(p) == 0L) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 优先从嵌入式 classpath jar 提取模型资源。
     *
     * @param def    模型定义
     * @param target 目标目录
     * @return true 表示提取成功且文件齐全
     */
    private static boolean extractEmbedded(Seq2SeqModelDefinition def, Path target) {
        String base = def.classpathBase();
        if (base == null || base.isBlank()) {
            return false;
        }
        try {
            // 探测 classpath 是否真的存在该资源目录，避免 NativeLoader 抛错
            boolean exists = Thread.currentThread().getContextClassLoader()
                    .getResources(base).hasMoreElements();
            if (!exists) {
                return false;
            }
            try {
                Files.createDirectories(target);
                NativeLoader.of("seq2seq-" + def.modelId())
                        .from(T5Seq2SeqOrtTranslator.class.getClassLoader())
                        .basePath(base)
                        .toTarget(target)
                        .glob("*")
                        .withMd5(true)
                        .extractOnly(true)
                        .load();
            } catch (Exception e) {
                log.warn("[seq2seq] {} 嵌入资源提取失败，降级 modelscope 下载: {}", def.modelId(), e.getMessage());
                return false;
            }
            return isComplete(def, target);
        } catch (Exception e) {
            log.warn("[seq2seq] {} 无嵌入式资源，走 modelscope 下载: {}", def.modelId(), e.getMessage());
            return false;
        }
    }

    /**
     * 从 modelscope 补齐缺失的模型文件。
     *
     * @param def    模型定义
     * @param target 目标目录
     */
    private static void downloadMissing(Seq2SeqModelDefinition def, Path target) {
        if (def.modelscopeRepo() == null || def.modelscopeRepo().isBlank()) {
            throw new IllegalStateException("[seq2seq] 模型[" + def.modelId() + "]无 modelscope 仓库，且嵌入式资源缺失");
        }
        try {
            Files.createDirectories(target);
        } catch (Exception e) {
            throw new IllegalStateException("[seq2seq] 创建缓存目录失败: " + target, e);
        }
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        for (int i = 0; i < def.downloadFiles().size(); i++) {
            String remote = def.downloadFiles().get(i);
            Path local = target.resolve(def.requiredFiles().get(i));
            boolean present;
            try {
                present = Files.isRegularFile(local) && Files.size(local) > 0L;
            } catch (IOException e) {
                present = false;
            }
            if (present) {
                continue;
            }
            String url = String.format(MODEL_SCOPE_BASE, def.modelscopeRepo(), remote);
            log.info("[seq2seq] {} 开始下载: {} -> {}", def.modelId(), remote, local);
            try {
                download(client, url, local);
            } catch (Exception e) {
                throw new IllegalStateException("[seq2seq] 模型[" + def.modelId() + "] 下载失败: " + url + ", " + e.getMessage(), e);
            }
        }
        log.info("[seq2seq] {} 模型文件就绪: {}", def.modelId(), target);
    }

    /**
     * 下载单个文件到本地。
     *
     * @param client HTTP 客户端
     * @param url    下载地址
     * @param target 本地文件
     * @throws Exception 下载异常
     */
    private static void download(HttpClient client, String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() >= 400) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
    }

    /**
     * 获取模型缓存根目录。
     *
     * @return 缓存根路径
     */
    public static Path cacheRoot() {
        String prop = System.getProperty(PROP_CACHE_DIR);
        if (prop == null || prop.isBlank()) {
            String tmp = System.getProperty(PROP_TMPDIR, ".");
            prop = Path.of(tmp, DEFAULT_CACHE_NAME).toString();
        }
        return Path.of(prop);
    }
}