package com.chua.huggingface.support;

/**
 * Hugging Face 常量。
 *
 * <p>HF Hub 提供 REST API 用于仓库增删查改、文件上传下载（含 LFS 大文件），
 * 以及免鉴权的公开文件下载。所有 Hub 客户端均基于这些默认地址，便于集中覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class HuggingfaceConstants {

    /**
     * Hugging Face 官方 Hub 地址（模型/数据集托管，Git LFS）
     */
    public static final String DEFAULT_HUB_BASE_URL = "https://huggingface.co";

    /**
     * Hugging Face 国内镜像地址（加速下载）
     */
    public static final String MIRROR_HUB_BASE_URL = "https://hf-mirror.com";

    /**
     * 文件下载路径（免鉴权）：/{repoId}/resolve/{revision}/{path}
     */
    public static final String PATH_RESOLVE = "/%s/resolve/%s/%s";

    /**
     * 仓库文件列表 API：GET /api/models/{repoId}
     */
    public static final String PATH_API_MODEL = "/api/models/%s";

    /**
     * 上传文件 API：POST /api/models/{repoId}/upload/{revision}
     */
    public static final String PATH_API_UPLOAD = "/api/models/%s/upload/%s";

    /**
     * 删除文件 API：DELETE /api/models/{repoId}/delete/{revision}/{path}
     */
    public static final String PATH_API_DELETE = "/api/models/%s/delete/%s/%s";

    /**
     * 创建仓库 API：POST /api/repos/create
     */
    public static final String PATH_API_REPOS_CREATE = "/api/repos/create";

    /**
     * 删除仓库 API：DELETE /api/repos/delete
     */
    public static final String PATH_API_REPOS_DELETE = "/api/repos/delete";

    /**
     * 上传超时（毫秒）：LFS 大文件可能很慢
     */
    public static final long UPLOAD_TIMEOUT_MILLIS = 600_000L;

    /**
     * 下载超时（毫秒）
     */
    public static final long DOWNLOAD_TIMEOUT_MILLIS = 600_000L;

    /**
     * 连接超时（毫秒）
     */
    public static final long CONNECT_TIMEOUT_MILLIS = 15_000L;

    private HuggingfaceConstants() {
    }
}
