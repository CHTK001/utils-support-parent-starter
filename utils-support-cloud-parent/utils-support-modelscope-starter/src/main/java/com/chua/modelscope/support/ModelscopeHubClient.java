package com.chua.modelscope.support;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 模型scope Hub 模型权重下载/上传工具。
 *
 * <p>提供两种方式：
 * <ul>
 *   <li>{@link #downloadSnapshot(String, Path)}：调用 {@code git clone}（含 LFS）
 *       拉取完整模型仓库到本地目录。需本机安装 {@code git} 与 {@code git-lfs}。
 *       鉴权使用 {@code https://oauth2:<TOKEN>@www.modelscope.cn/...}。</li>
 *   <li>{@link #downloadFile(String, String, Path)}：通过 Hub 公开文件下载 URL
 * 拉取单个文件（不依赖 Git）。</li>
 *   <li>{@link #uploadSnapshot(String, Path)}：通过 {@code git push} 上传本地仓库
 * 到 模型scope 远端。需本机安装 Git 并配置 LFS。</li>
 *   <li>{@link #listFiles(String)}：通过 Hub REST API 列出仓库文件清单。</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>{@code
 *   ModelscopeHubClient hub = new ModelscopeHubClient("ms-xxx-token");
 *   hub.downloadSnapshot("microsoft/Mage-Flow-Turbo", Path.of("/data/cache"));
 *   hub.uploadSnapshot("my-org/my-model", Path.of("./local-repo"));
 * }</pre>of("./local-repo"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ModelscopeHubClient {

    private final String token; // 令牌

    /**
     * 构造 Hub 客户端。
     *
     * @param token 模型scope 访问令牌（个人中心 -> 访问令牌）。允许为空，但下载公开仓库/调用无鉴权 API 仍可工作。
     */
    public ModelscopeHubClient(String token) {
        this.token = token;
    }

    /**
     * 构造无鉴权客户端（仅可访问公开仓库）。
     */
    public ModelscopeHubClient() {
        this(null);
    }

    /**
     * 列出 模型scope 仓库文件清单（公开仓库免鉴权）。
     *
     * @param repoId 形如 {@code owner/repo-name}
     * @return 文件路径列表
     */
    @SuppressWarnings("unchecked")
    public List<String> listFiles(String repoId) {
        String url = ModelscopeConstants.DEFAULT_HUB_BASE_URL + "/api/v1/models/" + repoId + "/repo/files";
        try {
            ClientResponse resp = HttpClientFactory.of(url)
                    .header("Authorization", buildAuthHeader())
                    .connectTimeout(ModelscopeConstants.CONNECT_TIMEOUT_MILLIS)
                    .readTimeout(ModelscopeConstants.READ_TIMEOUT_MILLIS)
                    .get();
            if (!resp.isSuccess()) {
                throw new RuntimeException("ModelScope 列出文件失败: " + resp.getStatusCode() + " - " + resp.getBodyString());
            }
            Map<String, Object> root = Json.fromJson(resp.getBodyString(), Map.class);
            Object data = root.get("data");
            if (data instanceof List<?> list) {
                List<String> result = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map && map.get("Path") != null) {
                        result.add(map.get("Path").toString());
                    } else if (item != null) {
                        result.add(item.toString());
                    }
                }
                return result;
            }
            return List.of();
        } catch (Exception e) {
            throw new RuntimeException("ModelScope 列出文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载 模型scope 仓库的单个文件（不依赖 Git）。
     *
     * @param repoId 形如 {@code owner/repo-name}
     * @param pathInRepo 仓库内文件路径（如 {@code config.json}）
     * @param target 本地保存路径
     */
    public void downloadFile(String repoId, String pathInRepo, Path target) {
        String url = ModelscopeConstants.DEFAULT_HUB_BASE_URL + "/" + repoId
                + "/resolve/master/" + pathInRepo;
        try {
            ClientResponse resp = HttpClientFactory.of(url)
                    .header("Authorization", buildAuthHeader())
                    .connectTimeout(ModelscopeConstants.CONNECT_TIMEOUT_MILLIS)
                    .readTimeout(ModelscopeConstants.READ_TIMEOUT_MILLIS)
                    .get();
            if (!resp.isSuccess()) {
                throw new RuntimeException("ModelScope 文件下载失败: " + resp.getStatusCode() + " - " + url);
            }
            File parent = target.toFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录: " + parent);
            }
            Files.write(target, resp.getBody());
            log.info("[modelscope-hub] downloaded {} -> {}", url, target);
        } catch (IOException e) {
            throw new RuntimeException("ModelScope 文件下载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过 {@code git clone} 拉取完整 模型scope 仓库到本地目录（支持 LFS）。
     *
     * <p>需本机安装 {@code git} 与 {@code git-lfs}（ModelScope 大文件权重走 LFS）。
     * 仓库克隆在 {@code localDir/<repoName>} 子目录下。
     *
     * @param repoId 形如 {@code owner/repo-name}
     * @param localDir 本地目标父目录（仓库会克隆到其下子目录）
     * @return 克隆后的仓库根目录
     */
    public Path downloadSnapshot(String repoId, Path localDir) {
        if (!Files.exists(localDir)) {
            try {
                Files.createDirectories(localDir);
            } catch (IOException e) {
                throw new RuntimeException("无法创建本地目录: " + localDir, e);
            }
        }
        String repoName = repoId.contains("/") ? repoId.substring(repoId.indexOf('/') + 1) : repoId;
        Path target = localDir.resolve(repoName);
        String authUrl = buildAuthenticatedGitUrl(repoId);
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");
        cmd.add(authUrl);
        cmd.add(target.toString());
        if (exec(cmd, localDir) != 0) {
            throw new RuntimeException("git clone 失败: " + repoId);
        }
        log.info("[modelscope-hub] cloned {} -> {}", repoId, target);
        return target;
    }

    /**
     * 通过 {@code git push} 上传本地仓库到 模型scope 远端。
     *
     * <p>需本机安装 {@code git} 与 {@code git-lfs}。本地必须已是 git 仓库（{@code .git}），
     * 并配置好用户名/邮箱（{@code git config user.name/email}）。
     *
     * @param repoId 形如 {@code my-org/my-model}
     * @param localRepoPath 本地 Git 仓库根目录
     */
    public void uploadSnapshot(String repoId, Path localRepoPath) {
        if (!Files.exists(localRepoPath.resolve(".git"))) {
            throw new IllegalArgumentException("本地路径不是 git 仓库（缺少 .git 目录）: " + localRepoPath);
        }
        String authUrl = buildAuthenticatedGitUrl(repoId);
        List<String> setUrl = new ArrayList<>();
        setUrl.add("git");
        setUrl.add("remote");
        setUrl.add("set-url");
        setUrl.add("origin");
        setUrl.add(authUrl);
        if (exec(setUrl, localRepoPath) != 0) {
            throw new RuntimeException("git remote set-url 失败: " + repoId);
        }
        List<String> push = new ArrayList<>();
        push.add("git");
        push.add("push");
        push.add("origin");
        push.add("HEAD:master");
        if (exec(push, localRepoPath) != 0) {
            throw new RuntimeException("git push 失败: " + repoId);
        }
        log.info("[modelscope-hub] pushed {} -> {}", localRepoPath, repoId);
    }

    /**
     * 构建带鉴权信息的 Git URL。
     * 有 token 时嵌入 oauth2 凭据用于 pull/push；无 token 时返回匿名公开地址。
     *
     * @param repoId 仓库标识，如 "owner/name"
     * @return 完整的 git 克隆/推送地址
     */
    private String buildAuthenticatedGitUrl(String repoId) {
        if (token == null || token.isBlank()) {
            return ModelscopeConstants.DEFAULT_HUB_BASE_URL + "/" + repoId + ".git";
        }
        return ModelscopeConstants.DEFAULT_HUB_BASE_URL + "/oauth2:" + token + "@" + repoId + ".git";
    }

    /**
     * 构建Auth请求头。
     *
     * @return 结果字符串
     */
    private String buildAuthHeader() {
        if (token == null || token.isBlank()) {
            return "";
        }
        return "Bearer " + token;
    }

    /**
     * 执行外部命令并等待完成。
     *
     * @param cmd 命令与参数
     * @param workDir 工作目录
     * @return 进程退出码
     */
    private int exec(List<String> cmd, Path workDir) {
        try {
            log.info("[modelscope-hub] exec: {} (cwd={})", String.join(" ", cmd), workDir);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[modelscope-hub] {}", line);
                }
            }
            if (!process.waitFor(ModelscopeConstants.READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("命令执行超时: " + String.join(" ", cmd));
            }
            return process.exitValue();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("命令执行失败: " + String.join(" ", cmd), e);
        }
    }
}
