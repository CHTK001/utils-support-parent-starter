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
     * 文件下载路径（免鉴权）：/{repoid}/resolve/{revision}/{路径}
     */
    public static final String PATH_RESOLVE = "/%s/resolve/%s/%s";

    /**
     * 仓库文件列表 API：获取 /api/模型/{repoid}
     */
    public static final String PATH_API_MODEL = "/api/models/%s";

    /**
     * 创建仓库 API：POST /api/repos/创建
     */
    public static final String PATH_API_REPOS_CREATE = "/api/repos/create";

    /**
     * 删除仓库 API：删除 /api/repos/删除（主体: {名称, 组织, 类型}）
     */
    public static final String PATH_API_REPOS_DELETE = "/api/repos/delete";

    /**
     * 提交 API（NDJSON）：POST /api/{repo类型}s/{repoid}/commit/{revision}
     */
    public static final String PATH_API_COMMIT = "/api/%ss/%s/commit/%s";

    /**
     * 预上传 API（判定 LFS/regular）：POST /api/{repo类型}s/{repoid}/preupload/{revision}
     */
    public static final String PATH_API_PREUPLOAD = "/api/%ss/%s/preupload/%s";

    /**
     * LFS 批量 API：POST /{url前缀}{repoid}.Git/信息/lfs/对象/批量
     */
    public static final String PATH_API_LFS_BATCH = "/%s.git/info/lfs/objects/batch";

    /**
     * 上传超时（毫秒）：LFS 大文件可能很慢
     */
    public static final long UPLOAD_TIMEOUT_MILLIS = 600_000L;

    /**
     * 下载超时（毫秒）
     */
    public static final long DOWNLOAD_TIMEOUT_MILLIS = 600_000L;

    /**
     * 连接超时（毫秒）：镜像/跨国网络连接可达 10s+，留足余量
     */
    public static final long CONNECT_TIMEOUT_MILLIS = 60_000L;

    /**
     * huggingface常量。
     */
    private HuggingfaceConstants() {
    }
}
