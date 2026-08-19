package com.chua.deeplearning.support.safetensors.download;

import com.chua.deeplearning.support.safetensors.SafeTensorServiceClient;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SafeTensor 模型下载管理器。
 * <p>
 * 自动从 ModelScope / HuggingFace 等来源下载模型 safetensor 文件。
 * 优先通过 Python 推理服务下载，失败时回退到本地 Java 直接下载。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SafeTensorModelDownloadManager {

    /** 客户端 */
    private final SafeTensorServiceClient client;

    /** 模型根目录 */
    /** 模型根级 */
    private final Path modelRoot;

    public SafeTensorModelDownloadManager(SafeTensorServiceClient client) {
        String root = System.getProperty("safetensor.model.root", "D:/safetensor_models");
        this.modelRoot = Path.of(root);
        this.client = client;
    }

    public SafeTensorModelDownloadManager(SafeTensorServiceClient client, Path modelRoot) {
        this.modelRoot = modelRoot;
        this.client = client;
    }

    /**
     * 确保模型已下载，如果不存在则自动下载。
     *
     * @param modelName 模型名称
     * @param source 来源（modelscope / huggingface）
     * @param revision 版本（可选）
     * @return 模型本地路径
     */
    public String ensureModel(String modelName, String source, String revision) {
        String localName = modelName.replace("/", "_");
        Path modelPath = modelRoot.resolve(localName);

        if (Files.isDirectory(modelPath)) {
            log.info("[SafeTensor] 模型已存在: {}", modelPath);
            return modelPath.toString();
        }

        log.info("[SafeTensor] 开始下载模型: {} ({})", modelName, source);
        try {
            var result = client.downloadModel(modelName, source, revision);
            log.info("[SafeTensor] 模型下载成功: {}", result.get("path"));
            return String.valueOf(result.get("path"));
        } catch (Exception e) {
            log.warn("[SafeTensor] 自动下载失败，尝试 Python 直接下载: {}", e.getMessage());
            return downloadDirect(modelName, source, revision, modelPath);
        }
    }

    /**
     * 通过 Python 命令行直接下载模型。
     */
    private String downloadDirect(String modelName, String source, String revision, Path targetPath) {
        try {
            Files.createDirectories(targetPath);
            if (!"modelscope".equals(source)) {
                log.warn("[SafeTensor] 不支持的下载来源: {}，返回目标路径", source);
                return targetPath.toString();
            }
            log.info("[SafeTensor] Python 直接下载: {} -> {}", modelName, targetPath);
            ProcessBuilder pb = new ProcessBuilder("python", "-m", "modelscope.hub.snapshot_download",
                    modelName, "--cache-dir", modelRoot.toString());
            if (revision != null && !revision.isBlank()) {
                pb.command().add("--revision");
                pb.command().add(revision);
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit == 0) {
                log.info("[SafeTensor] 直接下载成功: {}", modelName);
                return targetPath.toString();
            }
            log.warn("[SafeTensor] 直接下载失败 exit={}: {}", exit, output);
        } catch (Exception e) {
            log.warn("[SafeTensor] 直接下载失败: {}", e.getMessage());
        }
        return targetPath.toString();
    }
}
