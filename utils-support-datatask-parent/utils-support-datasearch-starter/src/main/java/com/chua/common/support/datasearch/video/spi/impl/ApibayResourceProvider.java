package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Apibay（The Pirate Bay 官方 JSON 接口）磁力资源提供者。
 *
 * <p>数据源：<a href="https://apibay.org">apibay.org</a>，搜索接口
 * {@code /q.php?q={关键词}} 返回 JSON 数组，每项包含
 * {@code name}、{@code info_hash}（40 位十六进制）、{@code size}（字节）、
 * {@code seeders}/{@code leechers}、{@code added}（Unix 秒）、{@code category} 等字段，
 * 因此可直接由 infohash 拼接标准磁力链接。</p>
 *
 * <p>该接口一次返回整页（最多约 100 条）且不支持页码，{@code page > 1} 时返回空页；
 * 关键词建议使用英文/罗马字（站点对中文关键词命中率很低）。</p>
 *
 * <p>该站可直连，代理配置见 {@link MagnetSupport}（直连失败时也可走代理）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("apibay")
public class ApibayResourceProvider extends AbstractResourceProvider {

    /**
     * 站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://apibay.org";

    /**
     * infohash（40 位十六进制）
     */
    private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-fA-F]{40}");

    /**
     * 空结果占位 infohash
     */
    private static final String EMPTY_HASH = "0000000000000000000000000000000000000000";

    /**
     * 创建 Apibay资源提供者 实例
     */
    public ApibayResourceProvider() {
        super();
    }

    /**
     * 创建 Apibay资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public ApibayResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("apibay");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        if (page > 1) {
            // 该接口不支持翻页
            return ReturnPageResult.empty();
        }
        try {
            String url = getBaseUrl() + "/q.php?q=" + MagnetSupport.encode(keyword);
            String body = MagnetSupport.get(url, getBaseUrl() + "/");
            JsonNode root = Json.parse(body);
            if (root == null || !root.isArray()) {
                return ReturnPageResult.error("apibay 返回格式异常");
            }

            List<VideoInfoResult> results = Wraps.list();
            for (int i = 0; i < root.size(); i++) {
                VideoInfoResult info = parseItem(root.get(i));
                if (info != null) {
                    results.add(info);
                }
            }
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(1)
                    .pageSize(results.size())
                    .total(results.size())
                    .totalPages(1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("apibay 磁力检索失败: " + e.getMessage());
        }
    }

    /**
     * 解析单条 JSON 结果。
     *
     * @param item JSON 节点
     * @return 视频信息，无效项返回 空
     */
    private VideoInfoResult parseItem(JsonNode item) {
        if (item == null || item.isMissingValue()) {
            return null;
        }
        String name = MagnetSupport.clean(item.get("name").toStringValue(""));
        String hash = MagnetSupport.clean(item.get("info_hash").toStringValue(""));
        if (!StringUtils.hasText(name) || !HASH_PATTERN.matcher(hash).matches()
                || EMPTY_HASH.equalsIgnoreCase(hash)) {
            return null;
        }

        long sizeBytes = item.get("size").toLongValue(0L);
        String size = formatSize(sizeBytes);
        String seeders = MagnetSupport.clean(item.get("seeders").toStringValue(""));
        String leechers = MagnetSupport.clean(item.get("leechers").toStringValue(""));
        String category = MagnetSupport.clean(item.get("category").toStringValue(""));
        String files = MagnetSupport.clean(item.get("num_files").toStringValue(""));
        String imdb = MagnetSupport.clean(item.get("imdb").toStringValue(""));
        long added = item.get("added").toLongValue(0L);

        String magnet = MagnetSupport.magnet(hash, name);
        VideoInfoResult info = new VideoInfoResult();
        info.setVideoOriginId(hash.toLowerCase(Locale.ROOT));
        info.setVideoName(name);
        info.setVideoTitle(name);
        info.setVideoPlatform(name());
        info.setVideoUrl(magnet);
        info.setDownloadUrls(magnet);
        info.setVideoSize(size);
        info.setVideoType(categoryLabel(category));
        info.setVideoCategory(categoryKind(category));
        info.setVideoPublishDate(toTime(added));
        info.setVideoPopularity(seeders);
        info.setVideoRemark(buildRemark(seeders, leechers, files));
        info.setVideoDescription(imdb);
        info.setTags("magnet,apibay,thepiratebay");
        info.setDownloadList(List.of(MagnetSupport.download(name, magnet, size, name(),
                info.getVideoPublishDate() == null ? "" : info.getVideoPublishDate().toString(),
                categoryLabel(category))));
        return info;
    }

    /**
     * 分类编码转可读分类。
     *
     * @param category 站点分类编码（如 201、207）
     * @return 分类名称
     */
    private static String categoryLabel(String category) {
        if (!StringUtils.hasText(category)) {
            return "";
        }
        switch (category.trim()) {
            case "201":
            case "204":
            case "205":
            case "206":
                return "Movies";
            case "202":
            case "208":
                return "TV shows";
            case "203":
                return "Music videos";
            case "207":
                return "TV shows HD";
            default:
                return category.trim();
        }
    }

    /**
     * 分类编码转统一分类。
     *
     * @param category 站点分类编码
     * @return 统一分类（movie/tv/video）
     */
    private static String categoryKind(String category) {
        if (!StringUtils.hasText(category)) {
            return "video";
        }
        String value = category.trim();
        if (!value.startsWith("2")) {
            return "video";
        }
        switch (value) {
            case "202":
            case "207":
            case "208":
                return "tv";
            default:
                return "movie";
        }
    }

    /**
     * 字节数转可读大小。
     *
     * @param bytes 字节数
     * @return 可读大小，无效值返回 空字符串
     */
    private static String formatSize(long bytes) {
        if (bytes <= 0L) {
            return "";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int unit = 0;
        while (value >= 1024D && unit < units.length - 1) {
            value /= 1024D;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    /**
     * Unix 秒转时间。
     *
     * @param seconds Unix 秒
     * @return 时间，无效值返回 空
     */
    private static LocalDateTime toTime(long seconds) {
        if (seconds <= 0L) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.systemDefault());
    }

    /**
     * 构建备注（做种/下载/文件数）。
     *
     * @param seeders  做种数
     * @param leechers 下载数
     * @param files    文件数
     * @return 备注文本
     */
    private static String buildRemark(String seeders, String leechers, String files) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(seeders)) {
            builder.append("做种=").append(seeders);
        }
        if (StringUtils.hasText(leechers)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("下载=").append(leechers);
        }
        if (StringUtils.hasText(files) && !"0".equals(files)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("文件数=").append(files);
        }
        return builder.toString();
    }

    /**
     * 获取站点地址（支持 {@link VideoSource} 注入覆盖）。
     *
     * @return 站点地址（无末尾斜杠）
     */
    protected String getBaseUrl() {
        if (videoSource != null && StringUtils.hasText(videoSource.getVideoSourceUrl())) {
            String url = videoSource.getVideoSourceUrl().trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return DEFAULT_BASE_URL;
    }

    /**
     * 提供者名称。
     *
     * @return 名称
     */
    public String name() {
        return "apibay";
    }
}
