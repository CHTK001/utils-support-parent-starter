package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.ModelRegistry;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * ONNX 权重位置解析工具。
 *
 * <p>统一「外部目录 / 显式路径 / 注册表 / classpath」的取值顺序，供各类
 * {@code *Translator} 复用，避免每个类各写一套回退逻辑：</p>
 * <ol>
 *   <li><b>框架注入路径</b>：{@code ITranslatorDelegate} 通过反射调用 {@code setModelPath}
 *       注入的绝对路径（{@code injectedPath}）；</li>
 *   <li><b>外部目录</b>：{@code modelId} 本身是绝对目录时，取目录内的目标文件；</li>
 *   <li><b>注册表</b>：{@link ModelRegistry#resolveModelPath(String)}，覆盖模型根目录、
 *       下载缓存（{@code downloadUrl} 自动下载所得）等；</li>
 *   <li><b>classpath</b>：由各 Translator 自行用 {@code NativeLoader} 按 jar 内路径解压兜底。</li>
 * </ol>
 *
 * <p>关于 modelId 与路径：内置模型的 {@code modelId} 是标识（如 {@code fastsam}），
 * 其文件位置由注册表的 {@code relativePath} 决定；而「外部目录绑定」的
 * {@code modelId} <strong>就是目录路径本身</strong>。本工具负责区分这两种情形，
 * 保证「modelId 是标识、路径只是取值」这一约定不被破坏。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ModelResourceResolver {

    /**
     * 工具类私有构造。
     */
    private ModelResourceResolver() {
    }

    /**
     * 解析 ONNX 权重文件位置。
     *
     * @param injectedPath 框架注入的路径 / 外部目录，可为 {@code null}
     * @param modelId      模型标识（内置模型为标识，外部目录为目录路径）
     * @param registryPath 注册表中的相对路径（{@code Entry.relativePath()}），可空
     * @param fileName     目录内期望的文件名（如 {@code inference.onnx}）
     * @return 权重文件绝对路径；三级均未命中时返回 {@code null}
     */
    public static Path resolveOnnx(String injectedPath, String modelId, String registryPath, String fileName) {
        // ① 框架注入的路径（可能是文件，也可能是目录）
        Path hit = fromPath(injectedPath, fileName);
        if (hit != null) {
            return hit;
        }
        // ② modelId 即外部目录
        if (modelId != null && !modelId.isBlank() && !isRegistryId(modelId)) {
            hit = fromPath(modelId, fileName);
            if (hit != null) {
                return hit;
            }
        }
        // ③ 注册表解析（relativePath → 模型根目录 → 下载缓存）
        if (modelId != null && !modelId.isBlank()) {
            try {
                Path resolved = ModelRegistry.resolveModelPath(modelId);
                if (resolved != null && Files.isRegularFile(resolved)) {
                    return resolved;
                }
            } catch (RuntimeException e) {
                // 注册表未登记该模型时忽略，交由调用方兜底
            }
        }
        if (registryPath != null && !registryPath.isBlank()) {
            try {
                Path direct = Paths.get(registryPath);
                if (Files.isRegularFile(direct)) {
                    return direct;
                }
                if (Files.isDirectory(direct)) {
                    Path inside = fromPath(registryPath, fileName);
                    if (inside != null) {
                        return inside;
                    }
                }
            } catch (InvalidPathException ignored) {
                // 非法路径字面量，继续返回 null
            }
        }
        return null;
    }

    /**
     * 解析附带附属文件的模型目录（如 rec 需要 inference.yml 字典配置）。
     *
     * @param injectedPath 框架注入的路径 / 外部目录，可为 {@code null}
     * @param modelId      模型标识
     * @param registryPath 注册表相对路径，可空
     * @param fileName     主文件名
     * @return 模型所在目录；未命中时返回 {@code null}
     */
    public static Path resolveModelDir(String injectedPath, String modelId, String registryPath, String fileName) {
        Path file = resolveOnnx(injectedPath, modelId, registryPath, fileName);
        return file == null ? null : file.getParent();
    }

    /**
     * 从给定路径（文件或目录）解析目标文件。
     *
     * @param path     文件路径或目录路径，可为 {@code null}
     * @param fileName 目录内期望的文件名
     * @return 命中的文件；未命中返回 {@code null}
     */
    private static Path fromPath(String path, String fileName) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path p = Paths.get(path.trim());
            if (Files.isRegularFile(p)) {
                return p;
            }
            if (Files.isDirectory(p)) {
                Path named = p.resolve(fileName);
                if (Files.isRegularFile(named)) {
                    return named;
                }
                // 目录内未按标准命名时，取第一个 .onnx
                try (Stream<Path> s = Files.list(p)) {
                    List<Path> onnx = s.filter(x -> x.getFileName().toString()
                                    .toLowerCase().endsWith(".onnx"))
                            .toList();
                    if (!onnx.isEmpty()) {
                        return onnx.getFirst();
                    }
                } catch (java.io.IOException e) {
                    // 目录不可读，按未命中处理
                    return null;
                }
            }
        } catch (InvalidPathException ignored) {
            // 非路径字面量（如 "paddleocrv6-det"），返回 null
        }
        return null;
    }

    /**
     * 判断是否为「注册表标识」而非路径。
     *
     * <p>含路径分隔符或盘符的一律视为路径；其余按标识处理。</p>
     *
     * @param modelId 模型标识或路径
     * @return true 表示注册表标识
     */
    private static boolean isRegistryId(String modelId) {
        return !modelId.contains("/") && !modelId.contains("\\") && !modelId.contains(":");
    }
}
