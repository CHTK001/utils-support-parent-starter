package com.chua.huggingface.support;

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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hugging Face Hub 仓库客户端（模型/数据集仓库管理，SPI provider="huggingface"）。
 *
 * <p>覆盖 Hub REST API 的增删查改与文件上传下载（含 LFS 大文件）：
 * <ul>
 *   <li>{@link #getRepoInfo(String)}：仓库元信息查询（GET /api/models/{repoId}）。</li>
 *   <li>{@link #listFiles(String)}：仓库文件清单（解析 API 响应的 siblings 字段）。</li>
 *   <li>{@link #whoami()}：令牌账户信息查询（GET /api/whoami-v2）。</li>
 *   <li>{@link #downloadFile(String, String, Path)}：单文件下载（免鉴权 resolve URL，
 *       自动跟随 302 跳转至 LFS 存储）。</li>
 *   <li>{@link #downloadSnapshot(String, Path)}：git clone 完整仓库（含 LFS 大权重）。</li>
 *   <li>{@link #uploadFile(String, String, Path)}：单文件上传，走新版 commit 端点
 *       （preupload 判定 LFS/regular → LFS batch+PUT 或 base64 内联 → NDJSON commit）。</li>
 *   <li>{@link #deleteFile(String, String)}：单文件删除（commit 端点 deletedFile 操作）。</li>
 *   <li>{@link #createRepo(String, String, boolean)}：创建仓库（POST /api/repos/create）。</li>
 *   <li>{@link #deleteRepo(String)}：删除仓库（DELETE /api/repos/delete）。</li>
 *   <li>{@link #uploadSnapshot(String, Path)}：git push 上传本地仓库（LFS 大文件首选）。</li>
 * </ul>
 *
 * <p>国内网络直连 huggingface.co 可能出现 PKIX 证书校验失败，
 * 可调用 {@link #mirror()} 切换到 hf-mirror.com 镜像（仅读路径可用，
 * 写接口会被 308 跳回官方站）。</p>
 *
 * <p>典型用法：
 * <pre>{@code
 *   HuggingfaceHubClient hub = new HuggingfaceHubClient("hf_xxx-token").mirror();
 *
 *   List<String> files = hub.listFiles("segmind/tiny-sd");
 *   hub.downloadFile("segmind/tiny-sd", "config.json", Path.of("/data/cache/config.json"));
 *   Path repo = hub.downloadSnapshot("segmind/tiny-sd", Path.of("/data/cache"));
 *
 *   hub.createRepo("my-model", "model", false);
 *   hub.uploadFile("my-org/my-model", "config.json", Path.of("./local/config.json"));
 *   hub.uploadSnapshot("my-org/my-model", Path.of("./local-repo"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class HuggingfaceHubClient {

    /**
    * 默认分支
    */
    public static final String DEFAULT_REVISION = "main";

    /**
    * 访问令牌（hf_ 开头），可为 null（仅公开仓库只读）
    */
    private final String token;

    /**
    * Hub 基地址，默认官方地址，可通过 {@link #mirror()} 切换镜像
    */
    private String baseUrl = HuggingfaceConstants.DEFAULT_HUB_BASE_URL;

    /**
    * 构造 Hub 客户端（官方地址 huggingface.co）。
    *
    * @param token Hugging Face 访问令牌（Settings -> Access Tokens），允许为空（仅公开仓库只读）
    */
    public HuggingfaceHubClient(String token) {
        this.token = token;
    }

    /**
    * 构造无鉴权客户端（官方地址，仅可访问公开仓库）。
    */
    public HuggingfaceHubClient() {
        this(null);
    }

    /**
    * 切换基地址为国内镜像 hf-mirror.com（API 兼容，解决 PKIX 证书失败/直连慢问题）。
    *
    * @return this
    */
    public HuggingfaceHubClient mirror() {
        this.baseUrl = HuggingfaceConstants.MIRROR_HUB_BASE_URL;
        return this;
    }

    /**
    * 设置自定义基地址（如自建代理或企业镜像）。
    *
    * @param baseUrl Hub 基地址，如 {@code https://hf-mirror.com}
    * @return this
    */
    public HuggingfaceHubClient baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    /**
    * 获取仓库基地址（当前生效值）。
    *
    * @return 基地址
    */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
    * 查询当前令牌对应的账户信息（GET /api/whoami-v2）。
    *
    * <p>未携带令牌时抛异常（HF 对匿名 whoami 返回 401）。</p>
    *
    * @return 账户信息（name、id、isPro、orgs、auth 等）
    */
    @SuppressWarnings("unchecked")
    public Map<String, Object> whoami() {
        return requestJson("GET", "/api/whoami-v2");
    }

    /**
    * 查询仓库元信息（公开仓库免鉴权）。
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @return 仓库元信息（id、private、downloads、likes、siblings 等）
    */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRepoInfo(String repoId) {
        return requestJson("GET", formatPath(HuggingfaceConstants.PATH_API_MODEL, repoId));
    }

    /**
    * 列出仓库文件清单（公开仓库免鉴权）。
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @return 文件路径列表（来自 API 响应的 siblings.rfilename）
    */
    public List<String> listFiles(String repoId) {
        Map<String, Object> info = getRepoInfo(repoId);
        List<String> result = new ArrayList<>();
        Object siblings = info.get("siblings");
        if (siblings instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("rfilename") != null) {
                    result.add(map.get("rfilename").toString());
                }
            }
        }
        return result;
    }

    /**
    * 下载仓库单个文件到本地（默认 main 分支，公开仓库免鉴权）。
    *
    * <p>LFS 大文件由 resolve URL 302 跳转到 LFS 存储，客户端自动跟随重定向。</p>
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @param pathInRepo 仓库内文件路径（如 {@code config.json}、{@code model.safetensors}）
    * @param target 本地保存路径
    */
    public void downloadFile(String repoId, String pathInRepo, Path target) {
        downloadFile(repoId, DEFAULT_REVISION, pathInRepo, target);
    }

    /**
    * 下载仓库指定分支/revision 的单个文件到本地。
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @param revision 分支名或 commit id（如 {@code main}）
    * @param pathInRepo 仓库内文件路径
    * @param target 本地保存路径
    */
    public void downloadFile(String repoId, String revision, String pathInRepo, Path target) {
        String path = String.format(HuggingfaceConstants.PATH_RESOLVE, repoId, revision, pathInRepo);
        // 下载为幂等 GET，遇瞬时 5xx（代理/CDN 抖动）自动重试
        ClientResponse resp = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            resp = HttpClientFactory.of(baseUrl + path)
                    .header("Authorization", buildAuthHeader())
                    .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                    .readTimeout(HuggingfaceConstants.DOWNLOAD_TIMEOUT_MILLIS)
                    .get();
            if (resp.isSuccess() || resp.getStatusCode() < 500 || attempt == 3) {
                break;
            }
            long backoff = 500L * (1L << (attempt - 1));
            log.warn("[hf-hub] 下载瞬时 {}，{}ms 后重试（{}/3）: {}", resp.getStatusCode(), backoff, attempt, path);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!resp.isSuccess()) {
            throw new RuntimeException("HuggingFace 文件下载失败: " + resp.getStatusCode() + " - " + path);
        }
        try {
            File parent = target.toFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录: " + parent);
            }
            Files.write(target, resp.getBody());
        } catch (IOException e) {
            throw new RuntimeException("HuggingFace 文件落盘失败: " + e.getMessage(), e);
        }
        log.info("[hf-hub] downloaded {}/{} -> {}", repoId, pathInRepo, target);
    }

    /**
    * 通过 {@code git clone} 拉取完整 Hub 仓库到本地目录（支持 LFS 大权重）。
    *
    * <p>需本机安装 {@code git} 与 {@code git-lfs}（大文件权重走 LFS）。
    * 仓库克隆在 {@code localDir/<repoName>} 子目录下。</p>
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @param localDir 本地目标父目录（仓库克隆到其下子目录）
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
        log.info("[hf-hub] cloned {} -> {}", repoId, target);
        return target;
    }

    /**
    * 上传本地文件到 Hub 仓库（REST API，默认 main 分支）。
    *
    * <p>使用 {@code POST /api/models/{repoId}/upload/{revision}/{path}} 接口，
    * 需携带访问令牌。超大文件（GB 级 LFS 权重）建议改用 {@link #uploadSnapshot}（git push）。</p>
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @param pathInRepo 仓库内目标路径
    * @param localPath 本地文件路径
    */
    public void uploadFile(String repoId, String pathInRepo, Path localPath) {
        uploadFile(repoId, DEFAULT_REVISION, pathInRepo, localPath);
    }

    /**
    * 上传本地文件到 Hub 仓库指定分支（新版 commit 端点）。
    *
    * <p>流程：preupload 判定文件走 LFS 还是 regular →
    * LFS 文件先经 batch API 取上传 URL 并 PUT；
    * 最后以 NDJSON commit 提交（regular 内联 base64，LFS 引用 oid/size）。</p>
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @param revision 分支名（如 {@code main}）
    * @param pathInRepo 仓库内目标路径
    * @param localPath 本地文件路径
    */
    public void uploadFile(String repoId, String revision, String pathInRepo, Path localPath) {
        if (!Files.exists(localPath)) {
            throw new IllegalArgumentException("本地文件不存在: " + localPath);
        }
        byte[] content;
        try {
            content = Files.readAllBytes(localPath);
        } catch (IOException e) {
            throw new RuntimeException("读取本地文件失败: " + e.getMessage(), e);
        }
        long size = content.length;
        String oid = sha256Hex(content);

        // 1. preupload：判定上传模式
        Map<String, Object> preupload = postJson("POST",
                String.format(HuggingfaceConstants.PATH_API_PREUPLOAD, "model", repoId, revision),
                Json.toJson(Map.of("files", List.of(Map.of(
                        "path", pathInRepo,
                        "sample", Base64.getEncoder().encodeToString(sampleOf(content)),
                        "size", size)))));
        String mode = extractUploadMode(preupload, pathInRepo);
        if ("lfs".equals(mode)) {
            // 2a. LFS：batch 取上传 URL 并 PUT
            uploadLfsObject(repoId, "model", revision, pathInRepo, oid, size, content);
        }
        // 2b. regular：无需预传，commit 时内联 base64

        // 3. NDJSON commit
        Map<String, Object> fileValue = new LinkedHashMap<>();
        if ("lfs".equals(mode)) {
            fileValue.put("algo", "sha256");
            fileValue.put("oid", oid);
            fileValue.put("size", size);
            fileValue.put("path", pathInRepo);
            postCommit(repoId, "model", revision,
                    Map.of("key", "lfsFile", "value", fileValue),
                    "Upload " + pathInRepo);
        } else {
            fileValue.put("content", Base64.getEncoder().encodeToString(content));
            fileValue.put("path", pathInRepo);
            fileValue.put("encoding", "base64");
            postCommit(repoId, "model", revision,
                    Map.of("key", "file", "value", fileValue),
                    "Upload " + pathInRepo);
        }
        log.info("[hf-hub] uploaded {} -> {}/{}", localPath, repoId, pathInRepo);
    }

    /**
    * 删除 Hub 仓库中的文件（默认 main 分支）。
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @param pathInRepo 仓库内文件路径
    */
    public void deleteFile(String repoId, String pathInRepo) {
        deleteFile(repoId, DEFAULT_REVISION, pathInRepo);
    }

    /**
    * 删除 Hub 仓库指定分支中的文件（commit 端点 deletedFile 操作）。
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @param revision 分支名（如 {@code main}）
    * @param pathInRepo 仓库内文件路径
    */
    public void deleteFile(String repoId, String revision, String pathInRepo) {
        Map<String, Object> op = Map.of("key", "deletedFile", "value", Map.of("path", pathInRepo));
        postCommit(repoId, "model", revision, op, "Delete " + pathInRepo);
        log.info("[hf-hub] deleted {}/{}", repoId, pathInRepo);
    }

    /**
    * 创建新仓库（模型或数据集）。
    *
    * @param repoName 仓库名（形如 {@code owner/repo-name}）
    * @param repoType 仓库类型：{@code model} 或 {@code dataset}
    * @param isPrivate 是否私有
    */
    public void createRepo(String repoName, String repoType, boolean isPrivate) {
        // HF API 的 name 字段只接受仓库名（不含 owner），owner 单独传
        String name = repoName;
        String owner = null;
        int slash = repoName.indexOf('/');
        if (slash >= 0) {
            owner = repoName.substring(0, slash);
            name = repoName.substring(slash + 1);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("type", repoType);
        payload.put("private", isPrivate);
        if (owner != null) {
            payload.put("owner", owner);
        }
        String path = HuggingfaceConstants.PATH_API_REPOS_CREATE;
        ClientResponse resp = HttpClientFactory.of(baseUrl + path)
                .header("Authorization", buildAuthHeader())
                .json()
                .body(Json.toJson(payload))
                .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                .post();
        if (!resp.isSuccess() && resp.getStatusCode() != 409) {
            throw new RuntimeException("HuggingFace 创建仓库失败: " + resp.getStatusCode()
                    + " - " + resp.getBodyString());
        }
        log.info("[hf-hub] created repo {} ({})", repoName, repoType);
    }

    /**
    * 删除 Hub 仓库（模型或数据集）。
    *
    * <p>HF API 要求 body 为 {@code {name, organization, type}}（name 不含 owner 前缀）。</p>
    *
    * @param repoId 形如 {@code owner/repo-name}
    */
    public void deleteRepo(String repoId) {
        String name = repoId;
        String owner = null;
        int slash = repoId.indexOf('/');
        if (slash >= 0) {
            owner = repoId.substring(0, slash);
            name = repoId.substring(slash + 1);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("type", "model");
        if (owner != null) {
            payload.put("organization", owner);
        }
        String path = HuggingfaceConstants.PATH_API_REPOS_DELETE;
        ClientResponse resp = HttpClientFactory.of(baseUrl + path)
                .header("Authorization", buildAuthHeader())
                .json()
                .body(Json.toJson(payload))
                .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                .delete();
        if (!resp.isSuccess() && resp.getStatusCode() != 404) {
            throw new RuntimeException("HuggingFace 删除仓库失败: " + resp.getStatusCode()
                    + " - " + resp.getBodyString());
        }
        log.info("[hf-hub] deleted repo {}", repoId);
    }

    // ─────────────────────────── commit 端点内部实现 ───────────────────────────

    /**
    * 提交一次 commit（NDJSON body）：header 行 + 单个操作行。
    *
    * @param repoId 形如 {@code owner/repo-name}
    * @param repoType 仓库类型（model/dataset/space）
    * @param revision 分支名
    * @param operation 操作行（key/value 结构）
    * @param summary commit 摘要
    */
    private void postCommit(String repoId, String repoType, String revision,
                            Map<String, Object> operation, String summary) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("summary", summary);
        header.put("description", "");
        String ndjson = Json.toJson(Map.of("key", "header", "value", header)) + "\n"
                + Json.toJson(operation) + "\n";
        String path = String.format(HuggingfaceConstants.PATH_API_COMMIT, repoType, repoId, revision);
        ClientResponse resp = HttpClientFactory.of(baseUrl + path)
                .header("Authorization", buildAuthHeader())
                .header("Content-Type", "application/x-ndjson")
                .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                .body(ndjson)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("HuggingFace commit 失败: " + resp.getStatusCode()
                    + " - " + resp.getBodyString());
        }
        log.debug("[hf-hub] commit ok: {}", summary);
    }

    /**
    * LFS 对象预上传：batch API 取上传 URL，再 PUT 文件内容。
    */
    @SuppressWarnings("unchecked")
    private void uploadLfsObject(String repoId, String repoType, String revision,
                                 String pathInRepo, String oid, long size, byte[] content) {
        String urlPrefix = switch (repoType) {
            case "dataset" -> "datasets/";
            case "space" -> "spaces/";
            default -> "";
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "upload");
        payload.put("transfers", List.of("basic", "multipart"));
        payload.put("objects", List.of(Map.of("oid", oid, "size", size)));
        payload.put("hash_algo", "sha256");
        payload.put("ref", Map.of("name", revision));
        String path = String.format(HuggingfaceConstants.PATH_API_LFS_BATCH, urlPrefix + repoId);
        ClientResponse resp = HttpClientFactory.of(baseUrl + path)
                .header("Authorization", buildAuthHeader())
                .header("Accept", "application/vnd.git-lfs+json")
                .header("Content-Type", "application/vnd.git-lfs+json")
                .json()
                .body(Json.toJson(payload))
                .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                .post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("HuggingFace LFS batch 失败: " + resp.getStatusCode()
                    + " - " + resp.getBodyString());
        }
        Map<String, Object> body = Json.fromJson(resp.getBodyString(), Map.class);
        Object objects = body.get("objects");
        if (!(objects instanceof List<?> list) || list.isEmpty()) {
            throw new RuntimeException("LFS batch 响应缺少 objects: " + resp.getBodyString());
        }
        Map<String, Object> obj = (Map<String, Object>) list.getFirst();
        Object actions = obj.get("actions");
        if (actions == null) {
            // 对象已存在于 HF 存储，无需 PUT
            log.debug("[hf-hub] LFS 对象已存在，跳过上传: {}", pathInRepo);
            return;
        }
        Map<String, Object> actionsMap = (Map<String, Object>) actions;
        Map<String, Object> uploadAction = (Map<String, Object>) actionsMap.get("upload");
        if (uploadAction == null) {
            throw new RuntimeException("LFS batch 响应缺少 upload 动作: " + resp.getBodyString());
        }
        // 镜像场景下 batch 响应的 multipart 完成 URL 可能指向不可达 host（如 hf-mirror.org），
        // 对齐到当前 baseUrl；直接 S3 预签名 URL（含 X-Amz-* / 非本 Hub API 路径）不受影响
        String uploadUrl = alignLfsHost((String) uploadAction.get("href"));
        // multipart 分片上传（header 含 chunk_size + 分片 URL 00001..000NN）：大文件镜像站常用
        Object headerObj = uploadAction.get("header");
        if (headerObj instanceof Map<?, ?> header && header.get("chunk_size") != null) {
            uploadMultipart(uploadUrl, oid, size, content, (Map<String, Object>) header);
        } else {
            // basic 直传：href 是 S3 预签名 URL（自带 X-Amz-* 鉴权），不能附加 Authorization 头，
            // 否则 S3 报 "Only one auth mechanism allowed"；也不带自定义请求头（与 huggingface_hub 一致）
            ClientResponse putResp = HttpClientFactory.of(uploadUrl)
                    .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                    .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                    .body(content)
                    .put();
            if (!putResp.isSuccess()) {
                throw new RuntimeException("HuggingFace LFS PUT 失败: " + putResp.getStatusCode()
                        + " - " + putResp.getBodyString());
            }
        }
        // verify 步骤：把对象注册为可用，否则 commit 报 "LFS pointer pointed to a file that does not exist"
        Object verifyActionObj = actionsMap.get("verify");
        if (verifyActionObj != null) {
            // 镜像场景（hf-mirror.com）下 batch 响应可能返回 hf-mirror.org 等不可达 host，
            // 需对齐到当前 baseUrl 的 host（S3 预签名 upload URL 除外，由 S3 直接可达）
            String verifyUrl = alignLfsHost((String) ((Map<String, Object>) verifyActionObj).get("href"));
            ClientResponse verifyResp = HttpClientFactory.of(verifyUrl)
                    .header("Authorization", buildAuthHeader())
                    .header("Content-Type", "application/json")
                    .json()
                    .body(Json.toJson(Map.of("oid", oid, "size", size)))
                    .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                    .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                    .post();
            if (!verifyResp.isSuccess()) {
                throw new RuntimeException("HuggingFace LFS verify 失败: " + verifyResp.getStatusCode()
                        + " - " + verifyResp.getBodyString());
            }
        }
        log.debug("[hf-hub] LFS 上传完成: {}/{}", repoId, pathInRepo);
    }

    /**
    * multipart 分片上传：按 chunk_size 切分文件，逐片 PUT 到 S3 分片 URL，
    * 最后 PUT 完成 URL（complete_multipart）结束。
    *
    * @param completeUrl multipart 完成 URL
    * @param oid         LFS oid（sha256）
    * @param size        文件总大小
    * @param content     完整文件内容
    * @param header      batch 响应 header：含 chunk_size 与 00001..000NN 分片 URL
    */
    private void uploadMultipart(String completeUrl, String oid, long size, byte[] content,
                                 Map<String, Object> header) {
        int chunkSize = Integer.parseInt(String.valueOf(header.get("chunk_size")));
        // 分片 URL：header 里 "00001".."000NN"（S3 预签名 UploadPart URL，不能附加自定义头）
        List<String> partUrls = new ArrayList<>();
        for (int i = 1; i * (long) chunkSize < size || (i == 1 && size == 0); i++) {
            String key = String.format("%05d", i);
            Object url = header.get(key);
            if (url == null) {
                break;
            }
            partUrls.add((String) url);
        }
        if (partUrls.isEmpty()) {
            throw new RuntimeException("LFS multipart header 缺少分片 URL (00001..): " + header.keySet());
        }
        log.info("[hf-hub] LFS multipart 上传: {} bytes, {} parts x {}B", size, partUrls.size(), chunkSize);

        // 收集每个分片 PUT 响应的 ETag，完成 multipart 时需要
        List<Map<String, Object>> parts = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < partUrls.size(); i++) {
            int len = (int) Math.min(chunkSize, content.length - offset);
            byte[] part = new byte[len];
            System.arraycopy(content, offset, part, 0, len);
            offset += len;
            // S3 预签名分片 URL：不带 Authorization/自定义头（与 huggingface_hub 一致）
            ClientResponse partResp = HttpClientFactory.of(partUrls.get(i))
                    .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                    .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                    .body(part)
                    .put();
            if (!partResp.isSuccess()) {
                throw new RuntimeException("LFS multipart 分片 " + (i + 1) + "/" + partUrls.size()
                        + " PUT 失败: " + partResp.getStatusCode() + " - " + partResp.getBodyString());
            }
            // ETag 响应头大小写不确定（S3 返回 "ETag"），做不区分大小写的取值
            String etag = getHeaderIgnoreCase(partResp, "etag");
            parts.add(Map.of("partNumber", i + 1, "etag", etag != null ? etag : ""));
            log.debug("[hf-hub] multipart 分片 {}/{} 已上传 (etag={})", i + 1, partUrls.size(), etag);
        }

        // 完成 multipart：POST oid/size/parts（含各分片 ETag）到完成 URL
        // （官方 HF 与镜像站均用 POST；缺 parts 或 partNumber 会返回 400）
        Map<String, Object> doneBody = new LinkedHashMap<>();
        doneBody.put("oid", oid);
        doneBody.put("size", size);
        doneBody.put("parts", parts);
        ClientResponse doneResp = HttpClientFactory.of(completeUrl)
                .header("Authorization", buildAuthHeader())
                .header("Content-Type", "application/json")
                .json()
                .body(Json.toJson(doneBody))
                .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                .post();
        if (!doneResp.isSuccess()) {
            throw new RuntimeException("LFS multipart 完成失败: " + doneResp.getStatusCode()
                    + " - " + doneResp.getBodyString());
        }
        log.info("[hf-hub] LFS multipart 上传完成: {} bytes", size);
    }

    /**
    * 从响应头中不区分大小写地取值。
    *
    * @param resp      客户端响应
    * @param headerName 头名（小写或任意大小写）
    * @return 头值；不存在返回 null
    */
    private String getHeaderIgnoreCase(ClientResponse resp, String headerName) {
        if (resp == null) {
            return null;
        }
        String direct = resp.getHeader(headerName);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        for (Map.Entry<String, String> entry : resp.getHeaders().toMap().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
                String v = entry.getValue();
                return v == null || v.isBlank() ? null : v;
            }
        }
        return null;
    }

    /**
    * 解析 preupload 响应，返回指定路径的上传模式（lfs/regular）。
    * @param preupload 方法入参 preupload
    * @param pathInRepo 路径InRepo，不允许为 null
    * @return 结果字符串
    */
    @SuppressWarnings("unchecked")
    private String extractUploadMode(Map<String, Object> preupload, String pathInRepo) {
        Object files = preupload.get("files");
        if (files instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map
                        && pathInRepo.equals(map.get("path"))) {
                    return String.valueOf(map.get("uploadMode"));
                }
            }
        }
        throw new RuntimeException("preupload 响应未包含路径 " + pathInRepo + ": " + preupload);
    }

    /**
    * 执行 JSON 请求（POST/DELETE）并解析 Map 响应。
    * @param method 方法，不允许为 null
    * @param path 路径，不允许为 null
    * @param body 请求体，不允许为 null
    * @return 结果映射，无数据时为空映射
    */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String method, String path, String body) {
        var builder = HttpClientFactory.of(baseUrl + path)
                .header("Authorization", buildAuthHeader())
                .header("Content-Type", "application/json")
                .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS)
                .body(body);
        ClientResponse resp = "DELETE".equals(method) ? builder.delete() : builder.post();
        if (!resp.isSuccess()) {
            throw new RuntimeException("HuggingFace API 调用失败: " + resp.getStatusCode()
                    + " - " + resp.getBodyString());
        }
        return Json.fromJson(resp.getBodyString(), Map.class);
    }

    /**
    * 文件采样（前 512 字节，与 huggingface_hub 一致，用于 preupload 判定 LFS/regular）。
    * @param content 内容，不允许为 null
    * @return 结果值
    */
    private static byte[] sampleOf(byte[] content) {
        return java.util.Arrays.copyOf(content, (int) Math.min(512, content.length));
    }

    /**
    * 计算 SHA-256 十六进制摘要（LFS oid）。
    * @param content 内容，不允许为 null
    * @return 结果字符串
    */
    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
    * 通过 {@code git push} 上传本地仓库到 Hub 远端（LFS 大文件首选）。
    *
    * <p>需本机安装 {@code git} 与 {@code git-lfs}。本地必须已是 git 仓库（{@code .git}），
    * 并配置好用户名/邮箱（{@code git config user.name/email}）。</p>
    *
    * @param repoId 形如 {@code my-org/my-model}
    * @param localRepoPath 本地 git 仓库根目录
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
        push.add("HEAD:" + DEFAULT_REVISION);
        if (exec(push, localRepoPath) != 0) {
            throw new RuntimeException("git push 失败: " + repoId);
        }
        log.info("[hf-hub] pushed {} -> {}", localRepoPath, repoId);
    }

    /**
    * 将 LFS verify/commit 等 API URL 的 host 对齐到当前 baseUrl 的 host。
    *
    * <p>镜像场景（如 hf-mirror.com）下 batch 响应可能返回 hf-mirror.org 等不可达 host，
    * 仅当 URL 属于本 Hub API（非 S3 预签名）时对齐。</p>
    *
    * @param url 原始 URL（可为 null）
    * @return 对齐后的 URL
    */
    private String alignLfsHost(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String baseHost = java.net.URI.create(baseUrl).getHost();
            String urlHost = uri.getHost();
            if (baseHost == null || urlHost == null || baseHost.equals(urlHost)) {
                return url;
            }
            // 仅对齐本 Hub API 路径（info/lfs、commit、preupload、multipart 完成等），
            // 直接 S3 预签名 URL（cas-bridge.xethub.hf.co / *.s3 / *.cloudfront）不在此列
            if (!url.contains("/info/lfs/") && !url.contains("/commit/") && !url.contains("/preupload/")
                    && !url.contains("/api/")) {
                return url;
            }
            String authority = uri.getAuthority();
            int port = uri.getPort();
            String newAuthority = port > 0 ? baseHost + ":" + port : baseHost;
            return url.replaceFirst(java.util.regex.Pattern.quote(authority), newAuthority);
        } catch (Exception e) {
            log.debug("[hf-hub] LFS host 对齐失败，使用原始 URL: {}", e.getMessage());
            return url;
        }
    }

    /**
    * 执行带鉴权的 HTTP 请求并解析 JSON 响应。
    *
    * <p>GET 为幂等读操作，遇瞬时 5xx（代理/CDN 抖动）自动重试；
    * POST/DELETE 非幂等，不重试。</p>
    * @param method 方法，不允许为 null
    * @param path 路径，不允许为 null
    * @return 结果映射，无数据时为空映射
    */
    @SuppressWarnings("unchecked")
    private Map<String, Object> requestJson(String method, String path) {
        var builder = HttpClientFactory.of(baseUrl + path)
                .header("Authorization", buildAuthHeader())
                .accept("application/json")
                .connectTimeout(HuggingfaceConstants.CONNECT_TIMEOUT_MILLIS)
                .readTimeout(HuggingfaceConstants.DOWNLOAD_TIMEOUT_MILLIS);
        boolean idempotent = !"POST".equals(method) && !"DELETE".equals(method);
        int maxAttempts = idempotent ? 3 : 1;
        ClientResponse resp = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            resp = switch (method) {
                case "POST" -> builder.post();
                case "DELETE" -> builder.delete();
                default -> builder.get();
            };
            if (resp.isSuccess() || resp.getStatusCode() < 500 || attempt == maxAttempts) {
                break;
            }
            long backoff = 500L * (1L << (attempt - 1));
            log.warn("[hf-hub] 瞬时 {}，{}ms 后重试（{}/{}）: {}", resp.getStatusCode(), backoff, attempt, maxAttempts, path);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!resp.isSuccess()) {
            throw new RuntimeException("HuggingFace API 调用失败: " + resp.getStatusCode()
                    + " - " + path);
        }
        return Json.fromJson(resp.getBodyString(), Map.class);
    }

    /**
     * 格式化路径。
     *
     * @param template 模板，不允许为 null
     * @param repoId repoID，不允许为 null
     * @return 结果字符串
     */
    private String formatPath(String template, String repoId) {
        return String.format(template, repoId);
    }

    /**
    * 构建带鉴权信息的 git URL（oauth2:&lt;token&gt;@ 形式）。
    * @param repoId repoID，不允许为 null
    * @return 结果字符串
    */
    private String buildAuthenticatedGitUrl(String repoId) {
        if (token == null || token.isBlank()) {
            return baseUrl + "/" + repoId + ".git";
        }
        return baseUrl + "/oauth2:" + token + "@" + repoId + ".git";
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
            log.info("[hf-hub] exec: {} (cwd={})", String.join(" ", cmd), workDir);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[hf-hub] {}", line);
                }
            }
            if (!process.waitFor(HuggingfaceConstants.UPLOAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
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
