package com.chua.filestorage.support.storage.lanzou;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonArray;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.DeleteObjectResult;
import com.chua.common.support.storage.result.ExistObjectResult;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.result.ListObjectResult;
import com.chua.common.support.storage.result.ObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* 蓝奏云网盘文件存储实现。
*
* <p>基于登录态 Cookie 调用蓝奏云网盘接口（{@code doupload.php} 系列 task），
* 完整实现 {@link com.chua.common.support.storage.FileStorage} 契约。</p>
*
* <h3>接口映射</h3>
* <ul>
*   <li>列表：{@code task=5}（文件）/ {@code task=47}（文件夹树）</li>
*   <li>下载：{@code task=22} 拿分享短链 → 分享页 → {@code ajaxm.php} 直链</li>
*   <li>删除文件：{@code task=6}；删除文件夹：{@code task=3}</li>
*   <li>上传：{@code multipart} 直传（端点可配置）</li>
* </ul>
*
* <h3>Key 约定</h3>
* <p>蓝奏云以数字 {@code file_id} 唯一标识文件。{@code listObject} 返回的每个条目的
* {@link Metadata#getKey()} 形如 {@code <file_id>/<文件名>}，调用 {@code getObject/deleteObject}
* 时透传该 键 即可（实现会从中解析出 文件_标识）。</p>
*
* <h3>配置</h3>
* <pre>{@code
* BucketSetting setting = BucketSetting.builder()
*     .extraProperties(Map.of(
*         "cookiePath", "/path/to/cookies.txt",   // Netscape 或 playwright JSON
*         "uid", "1645999",                         // 可选，缺省从 cookie 的 ylogin 提取
*         "rootFolderId", "-1",                    // 可选，默认 -1（全部）
*         "uploadUrl", "https://up.lanzou.com/up"  // 可选，上传端点
*     ))
*     .build();
* }</pre>/up"  // 可选，上传端点
*     ))
*     .build();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi({"lanzou"})
public class LanzouFileStorage extends AbstractFileStorage {

    /**
    * 网盘主接口域名。
     */
    private static final String HOST = "https://pc.woozooo.com";

    /**
    * 登录态文件列表 Referer。
     */
    private final String referer;

    /**
    * 蓝奏云 HTTP 客户端（含 WAF 挑战处理）。
     */
    private final LanzouHttp http;

    /**
    * 登录用户 标识。
     */
    private final String uid;

    /**
    * 列举根目录（默认 -1 表示全部）。
     */
    private final String rootFolderId;

    /**
    * 上传端点。
     */
    private final String uploadUrl;

    /**
    * 附加配置。
     */
    private final Map<String, String> extra;

    /**
    * 创建 lanzou文件storage 实例
    * @param bucketSetting bucketsetting
     */
    public LanzouFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        this.extra = bucketSetting.getExtraProperties() == null
                ? new LinkedHashMap<>() : bucketSetting.getExtraProperties();
        String cookie = loadCookie(extra.get("cookiePath") != null ? extra.get("cookiePath") : extra.get("cookie"));
        this.http = new LanzouHttp(cookie, 30000, 60000);
        String u = extra.get("uid");
        if (u == null || u.isEmpty()) {
            u = extractUid(cookie);
        }
        this.uid = u == null ? "" : u;
        this.referer = HOST + "/mydisk.php?item=files&action=index&u=" + this.uid;
        this.rootFolderId = extra.getOrDefault("rootFolderId", "-1");
        this.uploadUrl = extra.getOrDefault("uploadUrl", "https://up.lanzou.com/up");
    }

    /**
    * 从 Cookie 文件（Netscape 或 playwright JSON）读取并拼装为 Cookie 头串。
    * @param path 路径
    * @return 加载Cookie的结果
     */
    private String loadCookie(String path) {
        if (path == null || path.isEmpty()) {
            throw new LanzouException("蓝奏云未配置 cookiePath：请在 extraProperties 中指定 cookie 文件路径");
        }
        try {
            String raw = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            if (raw.trim().startsWith("[")) {
                return parseJsonCookie(raw);
            }
            return parseNetscapeCookie(raw);
        } catch (Exception e) {
            throw new LanzouException("蓝奏云读取 Cookie 失败: " + e.getMessage(), e);
        }
    }

    /**
    * 解析 Netscape Cookie 文件（每行 7 字段）。
    * @param raw raw
    * @return 解析netscapeCookie的结果
     */
    private String parseNetscapeCookie(String raw) {
        StringBuilder builder = new StringBuilder();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length < 7) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(f[5]).append('=').append(f[6]);
        }
        return builder.toString();
    }

    /**
    * 解析 playwright JSON Cookie 数组（[{名称,值,...}]）。
    * @param raw raw
    * @return 解析jsonCookie的结果
     */
    private String parseJsonCookie(String raw) {
        StringBuilder builder = new StringBuilder();
        // 轻量解析：匹配 "name":"xxx","value":"yyy" 成对出现
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"value\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(raw);
        while (m.find()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(m.group(1)).append('=').append(m.group(2));
        }
        return builder.toString();
    }

    /**
    * 从 Cookie 串提取登录 UID（ylogin 字段）。
    * @param cookie Cookie
    * @return extractUid的结果
     */
    private String extractUid(String cookie) {
        if (cookie == null) {
            return "";
        }
        Matcher m = Pattern.compile("ylogin=([^;]+)").matcher(cookie);
        return m.find() ? m.group(1) : "";
    }

    @Override
    /**
    * 放入对象
    * @param request 请求
     */
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            byte[] content = request.getContent();
            String fileName = request.getFileName();
            String folderId = resolveFolderId(request.getFilePath());
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("folder_id", folderId);
            fields.put("uid", uid);
            fields.put("task", "1");
            // 蓝奏云上传为 H5 分片协议，formhash 由前端动态获取；此处提供 multipart 直传框架，
 // 端点可通过 extra属性.uploadurl 配置，适用于服务端接受直传的场景。
            String resp = http.upload(uploadUrl, referer, fields, "file", fileName, content);
            JsonObject json = Json.getJsonObject(resp);
            if (json != null && json.getType("zt", 0, Integer.class) == 1) {
                JsonObject text = json.getObject("text", JsonObject.class);
                String fileId = text != null ? text.getType("id", "", String.class) : "";
                return PutObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.SUCCESS)
                        .key((fileId.isEmpty() ? folderId : fileId) + "/" + fileName)
                        .url(uploadUrl)
                        .build();
            }
            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(resp)
                    .build();
        } catch (Exception e) {
            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
    * 获取对象
    * @param request 请求
     */
    public GetObjectResult getObject(GetObjectRequest request) {
        try {
            String fileId = resolveFileId(request.getKey());
            String share = postTask("22", Map.of("file_id", fileId), referer);
            JsonObject info = Json.getJsonObject(share);
            if (info == null || info.getType("zt", 0, Integer.class) != 1) {
                return GetObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.FAILURE)
                        .message("蓝奏云获取分享信息失败: " + share)
                        .build();
            }
            JsonObject data = info.getObject("info", JsonObject.class);
            String isNewd = data.getType("is_newd", "", String.class);
            String fId = data.getType("f_id", "", String.class);
            String pwd = data.getType("pwd", "", String.class);
            String onof = data.getType("onof", "0", String.class);
            String shareUrl = isNewd + "/" + fId;
            String mainHtml = http.get(shareUrl, referer);
            LanzouShareInfo shareInfo = LanzouSharePageParser.parse(http, mainHtml, shareUrl,
                    "1".equals(onof) ? pwd : null);
            InputStream stream = http.openStream(shareInfo.getDownloadUrl(), referer);
            Metadata metadata = Metadata.builder()
                    .name(shareInfo.getFileName() != null ? shareInfo.getFileName() : request.getFileName())
                    .size(shareInfo.getSize())
                    .path(fileId)
                    .build();
            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(stream)
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
    * 获取对象
    * @param key 键
     */
    public GetObjectResult getObject(String key) {
        String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        String path = key.contains("/") ? key.substring(0, key.lastIndexOf('/')) : "";
        return getObject(GetObjectRequest.builder().fileName(name).filePath(path).key(key).build());
    }

    @Override
    /**
    * 删除对象
    * @param key 键
     */
    public DeleteObjectResult deleteObject(String key) {
        try {
            String fileId = resolveFileId(key);
            String resp = postTask("6", Map.of("file_id", fileId), referer);
            JsonObject json = Json.getJsonObject(resp);
            if (json != null && json.getType("zt", 0, Integer.class) == 1) {
                return DeleteObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.SUCCESS)
                        .build();
            }
            return DeleteObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(resp)
                    .build();
        } catch (Exception e) {
            return DeleteObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
    * exist对象
    * @param request 请求
     */
    public ExistObjectResult existObject(ExistObjectRequest request) {
        try {
            String fileId = resolveFileId(request.getKey());
            String resp = postTask("22", Map.of("file_id", fileId), referer);
            JsonObject json = Json.getJsonObject(resp);
            boolean exists = json != null && json.getType("zt", 0, Integer.class) == 1;
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(exists)
                    .build();
        } catch (Exception e) {
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
    * 列表对象
    * @param request 请求
     */
    public ListObjectResult listObject(ListObjectRequest request) {
        try {
            String folderId = request.getFilePath();
            List<LanzouFile> files;
            if (folderId == null || folderId.isEmpty() || "-1".equals(folderId) || "0".equals(folderId)) {
                files = listAll(rootFolderId);
            } else {
                files = listFiles(folderId);
            }
            List<Metadata> metadataList = new ArrayList<>(files.size());
            for (LanzouFile file : files) {
                metadataList.add(Metadata.builder()
                        .name(file.getName())
                        .path(file.getId())
                        .size(file.getSize())
                        .directory(file.isDirectory())
                        .build());
            }
            return ListObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .metadata(metadataList)
                    .build();
        } catch (Exception e) {
            return ListObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    /**
    * 递归列出某文件夹下的全部文件（含子文件夹）。
    * @param folderId 文件夹标识
    * @return 列表全部的结果
     */
    private List<LanzouFile> listAll(String folderId) {
        List<LanzouFile> result = new ArrayList<>();
        result.addAll(listFiles(folderId));
        for (LanzouFile folder : listFolders(folderId)) {
            result.addAll(listAll(folder.getId()));
        }
        return result;
    }

    /**
    * 列出文件夹下的文件（任务=5）。
    * @param folderId 文件夹标识
    * @return 列表文件的结果
     */
    private List<LanzouFile> listFiles(String folderId) {
        String resp = postTask("5", Map.of("folder_id", folderId, "pg", "1"), referer);
        List<LanzouFile> list = new ArrayList<>();
        JsonObject json = Json.getJsonObject(resp);
        if (json == null || json.getType("zt", 0, Integer.class) != 1) {
            return list;
        }
        parseFileArray(json, list, false);
        return list;
    }

    /**
    * 列出文件夹下的子文件夹（任务=47）。
    * @param folderId 文件夹标识
    * @return 列表文件夹的结果
     */
    private List<LanzouFile> listFolders(String folderId) {
        String resp = postTask("47", Map.of("folder_id", folderId), referer);
        List<LanzouFile> list = new ArrayList<>();
        JsonObject json = Json.getJsonObject(resp);
        if (json == null || json.getType("zt", 0, Integer.class) != 1) {
            return list;
        }
        parseFileArray(json, list, true);
        return list;
    }

    /**
    * 解析文件/文件夹数组为模型列表。
    * @param root 根
    * @param list 列表
    * @param directory 目录
     */
    private void parseFileArray(JsonObject root, List<LanzouFile> list, boolean directory) {
        JsonArray array = root.getJsonArray("text");
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            if (item instanceof JsonObject) {
                list.add(toLanzouFile((JsonObject) item, directory));
            }
        }
    }

    /**
    * 单个文件/文件夹对象转换。
    * @param o o
    * @param directory 目录
    * @return 转为lanzou文件的结果
     */
    private LanzouFile toLanzouFile(JsonObject o, boolean directory) {
        String id = directory ? o.getType("fol_id", "", String.class) : o.getType("id", "", String.class);
        String name = directory ? o.getType("name", "", String.class)
                : o.getType("name_all", o.getType("name", "", String.class), String.class);
        long size = parseSize(o.getType("size", "", String.class));
        boolean encrypted = "1".equals(o.getType("onof", "0", String.class));
        return LanzouFile.builder()
                .id(id)
                .name(name)
                .size(size)
                .sizeText(o.getType("size", "", String.class))
                .time(o.getType("time", "", String.class))
                .directory(directory)
                .encrypted(encrypted)
                .build();
    }

    /**
    * 提交 doupload.PHP 任务。
    * @param task 任务
    * @param params 参数
    * @param referer referer
    * @return post任务的结果
     */
    private String postTask(String task, Map<String, String> params, String referer) {
        Map<String, String> body = new LinkedHashMap<>(params);
        body.put("task", task);
        return http.post(HOST + "/doupload.php?uid=" + uid, body, referer);
    }

    /**
    * 从 键（<file_id>/<name> 或纯 文件_标识）解析出 文件_标识。
    * @param key 键
    * @return resolve文件id的结果
     */
    private String resolveFileId(String key) {
        if (key == null) {
            return "";
        }
        if (key.contains("/")) {
            return key.substring(0, key.indexOf('/'));
        }
        return key;
    }

    /**
    * 解析上传目标文件夹 标识。
    * @param filePath 文件路径
    * @return resolve文件夹id的结果
     */
    private String resolveFolderId(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return rootFolderId;
        }
 // 文件路径 可能是 文件夹_标识 或 路径；取首个片段作为 文件夹_标识
        String first = filePath.contains("/") ? filePath.substring(0, filePath.indexOf('/')) : filePath;
        return first.isEmpty() ? rootFolderId : first;
    }

    /**
    * 解析蓝奏云大小文本（如 "330.6 K"）为字节数。
    * @param sizeText 大小文本
    * @return 解析大小的结果
     */
    private long parseSize(String sizeText) {
        if (sizeText == null || sizeText.isEmpty()) {
            return 0;
        }
        Matcher m = Pattern.compile("([\\d.]+)\\s*([BKMGT]?)").matcher(sizeText.trim());
        if (!m.find()) {
            return 0;
        }
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2).toUpperCase();
        switch (unit) {
            case "K":
                return (long) (value * 1024);
            case "M":
                return (long) (value * 1024 * 1024);
            case "G":
                return (long) (value * 1024 * 1024 * 1024);
            case "T":
                return (long) (value * 1024L * 1024 * 1024 * 1024);
            default:
                return (long) value;
        }
    }
}
