package com.chua.maven.support;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven 构建后的部署客户端。
 * <p>
 * 基于编译结果中的产物文件，将产物部署到 {@link MavenDeployTarget} 目标。
 * 内置代理创建本地部署目标，同时支持各种自定义实现（如 SSH）。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 方式一：本地部署（快捷方式）
 * MavenClient.create()
 *     .projectPath("pom.xml")
 *     .goal("clean", "package")
 *     .compileAndDeploy()
 *     .deployTo("/opt/app/");
 *
 * // 方式二：指定部署目标
 * LocalDeployTarget target = new LocalDeployTarget("/opt/app");
 * MavenClient.create()
 *     .projectPath("pom.xml")
 *     .goal("clean", "package")
 *     .compileAndDeploy()
 *     .deployTo(target);
 *
 * // 方式三：已有编译结果
 * MavenCompileResult result = MavenClient.create()
 *     .projectPath("pom.xml")
 *     .goal("package")
 *     .execute();
 *
 * MavenDeployClient.from(result)
 *     .onDeploy(new MavenDeployCallback() {
 *         public void onDeploySuccess(List&lt;String&gt; paths) {
 *             System.out.println("部署到: " + paths);
 *         }
 *     })
 *     .deployTo(new LocalDeployTarget("/opt/app"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MavenDeployClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MavenDeployClient.class);

    /**
     * 编译结果
     */
    private final MavenCompileResult result;

    /**
     * 部署回调
     */
    private MavenDeployCallback deployCallback;

    public MavenDeployClient(MavenCompileResult result) {
        this.result = result;
    }

    /**
     * 从编译结果创建
     *
     * @param result 编译结果
     * @return 部署客户端
     */
    public static MavenDeployClient from(MavenCompileResult result) {
        return new MavenDeployClient(result);
    }

    /**
     * 设置部署回调
     *
     * @param callback 回调
     * @return this
     */
    public MavenDeployClient onDeploy(MavenDeployCallback callback) {
        this.deployCallback = callback;
        return this;
    }

    /**
     * 获取编译结果
     *
     * @return 编译结果
     */
    public MavenCompileResult getResult() {
        return result;
    }

    /**
     * 是否可以部署
     *
     * @return true 编译成功且有产物
     */
    public boolean canDeploy() {
        return result.isSuccess() && result.hasArtifacts();
    }

    // ==================== 核心部署方法 ====================

    /**
     * 部署到指定目标
     *
     * @param target 部署目标（如 LocalDeployTarget、SshDeployTarget）
     * @return 部署后的文件列表（目标路径）
     */
    public List<String> deployTo(MavenDeployTarget target) {
        if (!canDeploy()) {
            throw new MavenDeployException("无法部署: 编译失败或无产物");
        }

        notifyStart(target.name());

        // 连接目标
        try {
            target.connect();
            if (deployCallback != null) {
                target.setCallback(deployCallback);
            }
        } catch (Exception e) {
            notifyFailure(e);
            throw new MavenDeployException("部署目标连接失败: " + target.name(), e);
        }

        List<String> deployed = new ArrayList<>();
        try {
            List<String> artifacts = result.getArtifacts();
            int count = artifacts.size();

            target.createDirectory(".");
            for (int i = 0; i < count; i++) {
                String artifactPath = artifacts.get(i);
                File sourceFile = new File(artifactPath);
                target.upload(artifactPath, sourceFile.getName());
                deployed.add(sourceFile.getName());

                int percent = (i + 1) * 100 / count;
                notifyProgress("已部署: " + sourceFile.getName(), percent);
            }

            log.info("部署成功 [{} 个文件] -> {}", deployed.size(), target.name());
            notifySuccess(deployed);
        } catch (Exception e) {
            notifyFailure(e);
            throw new MavenDeployException("部署执行失败: " + e.getMessage(), e);
        } finally {
            try {
                target.disconnect();
            } catch (Exception ex) {
                log.warn("断开目标连接异常: {}", ex.getMessage());
            }
        }

        return deployed;
    }

    // ==================== 快捷部署方法 ====================

    /**
     * 部署到本地目录（快捷方式，每次创建新 LocalDeployTarget）
     *
     * @param targetDir 目标目录
     * @return 部署后的文件列表
     */
    public List<String> deployTo(String targetDir) {
        return deployTo(new LocalDeployTarget(targetDir));
    }

    /**
     * 部署到文件（复制到本地指定文件路径）
     *
     * @param targetFilePath 目标文件完整路径
     * @return 目标文件路径
     */
    public String deployToFile(String targetFilePath) {
        if (!canDeploy()) {
            throw new MavenDeployException("无法部署: 编译失败或无产物");
        }

        File targetFile = new File(targetFilePath);
        String main = result.getMainArtifact();
        if (main == null) {
            throw new MavenDeployException("无产物可部署");
        }

        notifyStart("文件部署: " + targetFilePath);

        try {
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            Files.copy(Path.of(main), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("文件部署: {} -> {}", main, targetFilePath);
            List<String> paths = List.of(targetFilePath);
            notifySuccess(paths);
            return targetFilePath;
        } catch (IOException e) {
            notifyFailure(e);
            throw new MavenDeployException("文件部署失败: " + e.getMessage(), e);
        }
    }

    // ==================== 原产物操作 ====================

    /**
     * 重命名产物
     *
     * @param newName 新文件名
     * @return 重命名后的文件路径
     */
    public String rename(String newName) {
        String sourcePath = result.getMainArtifact();
        if (sourcePath == null) {
            throw new MavenDeployException("无产物可重命名");
        }
        File sourceFile = new File(sourcePath);
        File parentDir = sourceFile.getParentFile();
        File renamedFile = new File(parentDir, newName);
        try {
            Files.move(sourceFile.toPath(), renamedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("重命名: {} -> {}", sourcePath, renamedFile.getAbsolutePath());
            return renamedFile.getAbsolutePath();
        } catch (IOException e) {
            throw new MavenDeployException("重命名失败: " + e.getMessage(), e);
        }
    }

    // ==================== 回调通知 ====================

    private void notifyStart(String info) {
        if (deployCallback != null) {
            try {
                deployCallback.onDeployStart(info);
            } catch (Exception e) {
                log.warn("部署开始回调异常: {}", e.getMessage());
            }
        }
    }

    private void notifyProgress(String message, int percent) {
        if (deployCallback != null) {
            try {
                deployCallback.onDeployProgress(message, percent);
            } catch (Exception e) {
                log.warn("部署进度回调异常: {}", e.getMessage());
            }
        }
    }

    private void notifySuccess(List<String> deployedPaths) {
        if (deployCallback != null) {
            try {
                deployCallback.onDeploySuccess(deployedPaths);
            } catch (Exception e) {
                log.warn("部署成功回调异常: {}", e.getMessage());
            }
        }
    }

    private void notifyFailure(Exception exception) {
        if (deployCallback != null) {
            try {
                deployCallback.onDeployFailure(exception);
            } catch (Exception ex) {
                log.warn("部署失败回调异常: {}", ex.getMessage());
            }
        }
    }
}