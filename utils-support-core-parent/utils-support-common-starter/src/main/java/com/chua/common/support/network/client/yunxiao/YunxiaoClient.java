package com.chua.common.support.network.client.yunxiao;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.yunxiao.constant.RepoType;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 云效（Alibaba Cloud DevOps / Yunxiao）OpenAPI 链式客户端。
 *
 * <p>基于 {@link HttpClientFactory} 与 {@link Json} 封装云效制品仓库模块的 REST API，
 * 提供流式（Fluent）API 依次设置参数，最后调用终端方法触发请求执行。
 * 鉴权统一使用请求头 {@code x-yunxiao-token}（个人访问令牌）。</p>
 *
 * <p><b>中心版与 Region 版：</b>构造时通过 {@link YunxiaoClientSetting#central} 区分。
 * 中心版请求路径自动插入 {@code /organizations/{organizationId}} 前缀。</p>
 *
 * <p><b>典型用法：</b></p>
 * <pre>{@code
 * YunxiaoClientSetting setting = YunxiaoClientSetting.builder()
 *         .domain("devops.cn-hangzhou.aliyuncs.com")
 *         .token("pt-xxxx")
 *         .organizationId("60d54f3daccf2bbd6659f3ad")
 *         .central(true)
 *         .build();
 *
 * YunxiaoClient client = YunxiaoClient.create(setting);
 *
 * // 1. 查询制品仓库列表
 * List<YunxiaoRepository> repos = client.repositories().page(1).perPage(20).list();
 *
 * // 2. 查询仓库中的制品列表
 * List<YunxiaoArtifact> artifacts = client.artifacts("my-repo")
 *         .repoType(RepoType.MAVEN)
 *         .search("junit")
 *         .list();
 *
 * // 3. 查询单个制品
 * YunxiaoArtifact artifact = client.artifacts("my-repo")
 *         .repoType(RepoType.MAVEN)
 *         .get(123456L);
 *
 * // 4. 删除单个制品
 * YunxiaoDeleteResult result = client.artifacts("my-repo")
 *         .repoType(RepoType.MAVEN)
 *         .delete(123456L);
 *
 * // 5. 删除单个制品版本
 * boolean removed = client.artifacts("my-repo")
 *         .repoType(RepoType.MAVEN)
 *         .deleteVersion(123456L, 123456L);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see YunxiaoClientSetting
 */
@Slf4j
public class YunxiaoClient {

    /**
     * 云效客户端配置
     */
    private final YunxiaoClientSetting setting;

    /**
     * 云效 OpenAPI 基础路径前缀
     */
    private static final String API_PREFIX = "/oapi/v1/packages";

    /**
     * 个人访问令牌请求头名称
     */
    private static final String HEADER_TOKEN = "x-yunxiao-token";

    /**
     * 构造云效客户端。
     *
     * @param setting 客户端配置，不能为 null
     */
    public YunxiaoClient(YunxiaoClientSetting setting) {
        this.setting = setting;
    }

    /**
     * 使用指定配置创建云效客户端。
     *
     * <p>等价于 {@code new YunxiaoClient(setting)}，提供更直观的语义入口。</p>
     *
     * @param setting 客户端配置，不能为 null
     * @return 云效客户端实例
     */
    public static YunxiaoClient create(YunxiaoClientSetting setting) {
        return new YunxiaoClient(setting);
    }

    /**
     * 创建查询制品仓库列表的链式入口。
     *
     * <p>返回仓库查询构建器，可通过链式方法设置分页、类型过滤等参数，
     * 最后调用 {@link RepositoryQuery#list()} 触发请求。</p>
     *
     * @return 制品仓库查询构建器
     */
    public RepositoryQuery repositories() {
        return new RepositoryQuery();
    }

    /**
     * 创建查询指定仓库制品信息的链式入口。
     *
     * <p>返回制品查询构建器，可通过链式方法设置仓库类型、分页、检索等参数，
     * 最后调用 {@link ArtifactQuery#list()} / {@link ArtifactQuery#get(long)} /
     * {@link ArtifactQuery#delete(long)} / {@link ArtifactQuery#deleteVersion(long, long)}
     * 触发请求。</p>
     *
     * @param repoId 仓库 Id，不能为空
     * @return 制品查询构建器
     */
    public ArtifactQuery artifacts(String repoId) {
        return new ArtifactQuery(repoId);
    }

    /**
     * 拼接完整请求地址：baseUrl + 中心版组织前缀 + API 前缀 + 相对路径。
     *
     * @param path 相对路径，如 {@code "/repositories/{repoId}/artifacts"}
     * @return 完整请求地址
     */
    private String resolveUrl(String path) {
        StringBuilder url = new StringBuilder();
        url.append("https://").append(setting.getDomain()).append(API_PREFIX);
        if (setting.isCentral()) {
            url.append("/organizations/").append(setting.getOrganizationId());
        }
        url.append(path);
        return url.toString();
    }

    /**
     * 构建带鉴权头的请求构建器。
     *
     * @param path 相对路径
     * @return 已注入 x-yunxiao-token 请求头的 {@link HttpClientBuilder}
     */
    private HttpClientBuilder request(String path) {
        return HttpClientFactory.of(resolveUrl(path)).header(HEADER_TOKEN, setting.getToken());
    }

    /**
     * 制品仓库查询构建器，采用链式 API。
     *
     * <p>支持按仓库类型、仓库模式过滤，并支持分页查询。</p>
     *
 * @author CH
     * @since 4.0.0.42
     */
    public class RepositoryQuery {

        /**
         * 仓库类型过滤，多个以逗号分割，如 {@code "MAVEN,NPM"}
         */
        private String repoTypes;

        /**
         * 仓库模式过滤，多个以逗号分割，如 {@code "Hybrid,Local"}
         */
        private String repoCategories;

        /**
         * 当前页码
         */
        private Integer page;

        /**
         * 每页数据量
         */
        private Integer perPage;

        /**
         * 设置仓库类型过滤条件，多个仓库类型以逗号分割。
         *
         * @param repoTypes 仓库类型，如 {@code "MAVEN,NPM"}
         * @return 当前构建器，支持链式调用
         */
        public RepositoryQuery repoTypes(String repoTypes) {
            this.repoTypes = repoTypes;
            return this;
        }

        /**
         * 设置仓库类型过滤条件。
         *
         * @param repoTypes 仓库类型数组
         * @return 当前构建器，支持链式调用
         */
        public RepositoryQuery repoTypes(String... repoTypes) {
            this.repoTypes = String.join(",", repoTypes);
            return this;
        }

        /**
         * 设置仓库模式过滤条件，多个仓库模式以逗号分割。
         *
         * @param repoCategories 仓库模式，如 {@code "Hybrid,Local"}
         * @return 当前构建器，支持链式调用
         */
        public RepositoryQuery repoCategories(String repoCategories) {
            this.repoCategories = repoCategories;
            return this;
        }

        /**
         * 设置当前页码。
         *
         * @param page 页码
         * @return 当前构建器，支持链式调用
         */
        public RepositoryQuery page(int page) {
            this.page = page;
            return this;
        }

        /**
         * 设置每页数据量。
         *
         * @param perPage 每页数据量
         * @return 当前构建器，支持链式调用
         */
        public RepositoryQuery perPage(int perPage) {
            this.perPage = perPage;
            return this;
        }

        /**
         * 执行查询并返回制品仓库列表。
         *
         * @return 制品仓库列表，请求失败或响应异常时返回空列表
         */
        public List<YunxiaoRepository> list() {
            HttpClientBuilder builder = request("/repositories");
            if (repoTypes != null) {
                builder.query("repoTypes", repoTypes);
            }
            if (repoCategories != null) {
                builder.query("repoCategories", repoCategories);
            }
            if (page != null) {
                builder.query("page", String.valueOf(page));
            }
            if (perPage != null) {
                builder.query("perPage", String.valueOf(perPage));
            }
            return parseList(builder.get(), YunxiaoRepository.class);
        }
    }

    /**
     * 制品查询构建器，采用链式 API。
     *
     * <p>支持按仓库类型、分页、包名检索与排序查询，并提供查询单个制品、
     * 删除制品与删除制品版本等终端方法。</p>
     *
 * @author CH
     * @since 4.0.0.42
     */
    public class ArtifactQuery {

        /**
         * 仓库 Id
         */
        private final String repoId;

        /**
         * 仓库类型（必填），取值见 {@link RepoType}
         */
        private String repoType;

        /**
         * 当前页码
         */
        private Integer page;

        /**
         * 每页数据量，默认值 10
         */
        private Integer perPage;

        /**
         * 根据包名检索
         */
        private String search;

        /**
         * 排序字段，latestUpdate：按最近更新时间排序；gmtDownload：按最近下载时间排序
         */
        private String orderBy;

        /**
         * 排序顺序，asc：从小到大；desc：从大到小
         */
        private String sort;

        /**
         * 构造制品查询构建器。
         *
         * @param repoId 仓库 Id
         */
        ArtifactQuery(String repoId) {
            this.repoId = repoId;
        }

        /**
         * 设置仓库类型（必填）。
         *
         * @param repoType 仓库类型，如 {@code "MAVEN"}、{@code "GENERIC"}
         * @return 当前构建器，支持链式调用
         */
        public ArtifactQuery repoType(String repoType) {
            this.repoType = repoType;
            return this;
        }

        /**
         * 设置仓库类型（必填）。
         *
         * @param repoType 仓库类型枚举
         * @return 当前构建器，支持链式调用
         */
        public ArtifactQuery repoType(RepoType repoType) {
            this.repoType = repoType.name();
            return this;
        }

        /**
         * 设置当前页码。
         *
         * @param page 页码
         * @return 当前构建器，支持链式调用
         */
        public ArtifactQuery page(int page) {
            this.page = page;
            return this;
        }

        /**
         * 设置每页数据量。
         *
         * @param perPage 每页数据量
         * @return 当前构建器，支持链式调用
         */
        public ArtifactQuery perPage(int perPage) {
            this.perPage = perPage;
            return this;
        }

        /**
         * 设置包名检索关键字。
         *
         * @param search 包名关键字
         * @return 当前构建器，支持链式调用
         */
        public ArtifactQuery search(String search) {
            this.search = search;
            return this;
        }

        /**
         * 设置排序字段。
         *
         * @param orderBy 排序字段，latestUpdate 或 gmtDownload
         * @return 当前构建器，支持链式调用
         */
        public ArtifactQuery orderBy(String orderBy) {
            this.orderBy = orderBy;
            return this;
        }

        /**
         * 设置排序顺序。
         *
         * @param sort 排序顺序，asc 或 desc
         * @return 当前构建器，支持链式调用
         */
        public ArtifactQuery sort(String sort) {
            this.sort = sort;
            return this;
        }

        /**
         * 拼接制品列表请求的相对路径。
         *
         * @return 制品列表相对路径，如 {@code "/repositories/my-repo/artifacts"}
         */
        private String artifactsPath() {
            return "/repositories/" + repoId + "/artifacts";
        }

        /**
         * 为制品相关请求注入公共查询参数（仓库类型、分页、检索、排序）。
         *
         * @param builder 请求构建器
         */
        private void applyCommonParams(HttpClientBuilder builder) {
            builder.query("repoType", repoType);
            if (page != null) {
                builder.query("page", String.valueOf(page));
            }
            if (perPage != null) {
                builder.query("perPage", String.valueOf(perPage));
            }
            if (search != null) {
                builder.query("search", search);
            }
            if (orderBy != null) {
                builder.query("orderBy", orderBy);
            }
            if (sort != null) {
                builder.query("sort", sort);
            }
        }

        /**
         * 查询制品列表。
         *
         * @return 制品列表，请求失败或响应异常时返回空列表
         */
        public List<YunxiaoArtifact> list() {
            HttpClientBuilder builder = request(artifactsPath());
            applyCommonParams(builder);
            return parseList(builder.get(), YunxiaoArtifact.class);
        }

        /**
         * 查询单个制品信息。
         *
         * @param artifactId 制品 Id
         * @return 制品信息，请求失败或响应异常时返回 null
         */
        public YunxiaoArtifact get(long artifactId) {
            HttpClientBuilder builder = request(artifactsPath() + "/" + artifactId);
            builder.query("repoType", repoType);
            return parseObject(builder.get(), YunxiaoArtifact.class);
        }

        /**
         * 删除单个制品。
         *
         * @param artifactId 制品 Id
         * @return 删除任务结果，请求失败或响应异常时返回 null
         */
        public YunxiaoDeleteResult delete(long artifactId) {
            HttpClientBuilder builder = request(artifactsPath() + "/" + artifactId);
            builder.query("repoType", repoType);
            return parseObject(builder.delete(), YunxiaoDeleteResult.class);
        }

        /**
         * 删除单个制品版本。
         *
         * @param artifactId 制品 Id
         * @param versionId  制品版本 Id
         * @return 是否删除成功；请求失败时返回 false
         */
        public boolean deleteVersion(long artifactId, long versionId) {
            HttpClientBuilder builder = request(artifactsPath() + "/" + artifactId + "/" + versionId);
            builder.query("repoType", repoType);
            ClientResponse response = builder.delete();
            if (response == null || !response.isSuccess()) {
                return false;
            }
            return "true".equalsIgnoreCase(response.getBodyString().trim());
        }
    }

    /**
     * 将响应体反序列化为对象列表。
     *
     * @param response     HTTP 响应，不能为 null
     * @param targetClass  目标元素类型
     * @param <T>          目标元素泛型
     * @return 反序列化后的对象列表；请求失败、响应异常或解析失败时返回空列表
     */
    private static <T> List<T> parseList(ClientResponse response, Class<T> targetClass) {
        if (response == null || !response.isSuccess()) {
            return Collections.emptyList();
        }
        try {
            List<T> list = Json.fromJsonToList(response.getBodyString(), targetClass);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            log.error("[yunxiao] 解析响应列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将响应体反序列化为单个对象。
     *
     * @param response    HTTP 响应，不能为 null
     * @param targetClass 目标类型
     * @param <T>         目标泛型
     * @return 反序列化后的对象；请求失败、响应异常或解析失败时返回 null
     */
    private static <T> T parseObject(ClientResponse response, Class<T> targetClass) {
        if (response == null || !response.isSuccess()) {
            return null;
        }
        try {
            return Json.fromJson(response.getBodyString(), targetClass);
        } catch (Exception e) {
            log.error("[yunxiao] 解析响应对象失败: {}", e.getMessage());
            return null;
        }
    }
}
