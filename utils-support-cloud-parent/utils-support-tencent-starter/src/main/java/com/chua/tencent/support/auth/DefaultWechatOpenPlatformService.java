package com.chua.tencent.support.auth;

import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.open.api.WxOpenComponentService;
import me.chanjar.weixin.open.api.WxOpenService;
import me.chanjar.weixin.open.api.impl.WxOpenInMemoryConfigStorage;
import me.chanjar.weixin.open.api.impl.WxOpenServiceImpl;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
* 微信开放平台服务默认实现
* <p>
* 使用 文件engine 存储 openid 映射关系，支持自动持久化到 JSON 文件。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DefaultWechatOpenPlatformService implements WechatOpenPlatformService {

    /** 获取用户向量接口地址 */
    private static final String GET_USER_VECTOR_URL = "https://api.weixin.qq.com/cgi-bin/component/getuservector";
    /** JSON 对象映射器 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 微信平台用户表名 */
    private static final String TABLE_NAME = "wechat_platform_user";

    /**
    * openid -> 平台用户信息（用于通过openid反查unionid）
     */
    private final ConcurrentHashMap<String, WechatPlatformUser> openIdIndex = new ConcurrentHashMap<>();

    /**
    * unionid -> 该用户在所有应用下的openid列表
     */
    private final ConcurrentHashMap<String, List<WechatPlatformUser>> unionUserMap = new ConcurrentHashMap<>();

    /** 微信开放平台服务 */
    private final WxOpenService wxOpenService;
    /** 第三方平台应用 appid */
    private final String componentAppId;
    /** 引擎实例 */
    private final WechatFileEngine engine;
    /** 标识 序列号 */
    private long idSequence = 0;

    /**
    * 创建 默认wechat打开platform服务 实例
    * @param componentAppId 组件appid
    * @param componentAppId 字符串
    * @param componentAppId 字符串
    * @param componentAppSecret 组件appsecret
    * @param storagePath storage路径
     */
    public DefaultWechatOpenPlatformService(String componentAppId, String componentAppSecret, String storagePath) {
        this.componentAppId = componentAppId;
        this.wxOpenService = buildWxOpenService(componentAppId, componentAppSecret);
        this.engine = initEngine(storagePath);
        loadFromEngine();
    }

    /**
    * 构建Wx打开服务
    *
    * @param componentAppId 组件appid
    * @param componentAppSecret 组件appsecret
    * @return 构建wx打开服务的结果
     */
    private WxOpenService buildWxOpenService(String componentAppId, String componentAppSecret) {
        WxOpenInMemoryConfigStorage config = new WxOpenInMemoryConfigStorage();
        config.setWxOpenInfo(componentAppId, componentAppSecret, null, null);
        WxOpenServiceImpl service = new WxOpenServiceImpl();
        service.setWxOpenConfigStorage(config);
        return service;
    }

    /**
    * 初始化Engine
    *
    * @param storagePath storage路径
    * @return 初始化engine的结果
     */
    private WechatFileEngine initEngine(String storagePath) {
        WechatFileEngine fileEngine = new WechatFileEngine();
        fileEngine.load(TABLE_NAME, storagePath, "json");
        fileEngine.autoPersist(TABLE_NAME, true);
        return fileEngine;
    }

    /**
    * 从 Engine 加载数据到内存索引
     */
    private void loadFromEngine() {
        List<WechatPlatformUser> users = engine.loadWithJackson(TABLE_NAME, WechatPlatformUser.class);
        if (users.isEmpty()) {
            return;
        }

        for (WechatPlatformUser user : users) {
            if (user.getId() != null && user.getId() > idSequence) {
                idSequence = user.getId();
            }
            buildIndex(user);
        }
        log.info("从持久化文件加载 {} 条用户映射", users.size());
    }

    /**
    * 构建索引
    *
    * @param user 用户
     */
    private void buildIndex(WechatPlatformUser user) {
        // 更新unionid索引
        unionUserMap.compute(user.getUnionId(), (key, list) -> {
            if (list == null) {
                list = new ArrayList<>();
            }
            list.removeIf(u -> u.getAppId() != null && u.getAppId().equals(user.getAppId()));
            list.add(user);
            return list;
        });

        // 更新openid索引
        openIdIndex.put(user.getOpenId(), user);
    }

    @Override
    /** 获取全部打开标识byunionid */
    public List<WechatPlatformUser> getAllOpenIdsByUnionId(String unionId) {
        if (StringUtils.isEmpty(unionId)) {
            return Collections.emptyList();
        }
        List<WechatPlatformUser> users = unionUserMap.get(unionId);
        return users != null ? Collections.unmodifiableList(users) : Collections.emptyList();
    }

    @Override
    /** 获取打开idbyunionid */
    public WechatPlatformUser getOpenIdByUnionId(String unionId, WechatPlatformUser.PlatformType platformType) {
        List<WechatPlatformUser> users = getAllOpenIdsByUnionId(unionId);
        return users.stream()
                .filter(u -> u.getPlatformType() == platformType)
                .findFirst()
                .orElse(null);
    }

    @Override
    /** 获取official账户打开标识 */
    public String getOfficialAccountOpenId(String unionId) {
        WechatPlatformUser user = getOpenIdByUnionId(unionId, WechatPlatformUser.PlatformType.OFFICIAL_ACCOUNT);
        return user != null ? user.getOpenId() : null;
    }

    @Override
    /** 获取miniapp打开标识 */
    public String getMiniAppOpenId(String unionId) {
        WechatPlatformUser user = getOpenIdByUnionId(unionId, WechatPlatformUser.PlatformType.MINI_APP);
        return user != null ? user.getOpenId() : null;
    }

    @Override
    /** 保存或更新 */
    public void saveOrUpdate(WechatPlatformUser user) {
        if (user == null || user.getUnionId() == null || user.getOpenId() == null) {
            return;
        }

        // 查找是否已存在
        WechatPlatformUser existing = openIdIndex.get(user.getOpenId());
        if (existing != null) {
            // 更新已有记录
            existing.setNickname(user.getNickname());
            existing.setAvatar(user.getAvatar());
            existing.setPlatformType(user.getPlatformType());
            buildIndex(existing);
            log.debug("更新用户映射: unionId={}, appId={}, openId={}", user.getUnionId(), user.getAppId(), user.getOpenId());
        } else {
            // 新增记录
            user.setId(++idSequence);
            buildIndex(user);
            log.debug("新增用户映射: unionId={}, appId={}, openId={}", user.getUnionId(), user.getAppId(), user.getOpenId());
        }

        // 持久化到文件
        persistToEngine();
    }

    /**
    * 将所有内存索引数据持久化到 Engine
     */
    private void persistToEngine() {
        List<WechatPlatformUser> allUsers = new ArrayList<>();
        for (List<WechatPlatformUser> users : unionUserMap.values()) {
            allUsers.addAll(users);
        }
        engine.store(TABLE_NAME, allUsers);
        engine.save(TABLE_NAME);
    }

    @Override
    /** 转换转为official打开标识 */
    public String convertToOfficialOpenId(String miniAppOpenId) {
        if (StringUtils.isEmpty(miniAppOpenId)) {
            return null;
        }

        WechatPlatformUser miniAppUser = openIdIndex.get(miniAppOpenId);
        if (miniAppUser == null) {
            log.warn("未找到小程序用户映射: miniAppOpenId={}", miniAppOpenId);
            return null;
        }

        return getOfficialAccountOpenId(miniAppUser.getUnionId());
    }

    @Override
    /** 转换转为official打开标识 */
    public String convertToOfficialOpenId(String miniAppId, String miniAppOpenId) {
        if (StringUtils.isEmpty(miniAppId) || StringUtils.isEmpty(miniAppOpenId)) {
            return null;
        }

        // 先尝试从本地映射查找
        String localResult = convertToOfficialOpenId(miniAppOpenId);
        if (localResult != null) {
            return localResult;
        }

        // 本地没有，调用微信开放平台API
        try {
            String unionId = getUnionIdFromWechatApi(miniAppId, miniAppOpenId);
            if (unionId != null) {
                saveOrUpdate(WechatPlatformUser.builder()
                        .unionId(unionId)
                        .appId(miniAppId)
                        .openId(miniAppOpenId)
                        .platformType(WechatPlatformUser.PlatformType.MINI_APP)
                        .build());

                return getOfficialAccountOpenId(unionId);
            }
        } catch (Exception e) {
            log.error("调用微信API获取unionid失败: miniAppId={}, miniAppOpenId={}", miniAppId, miniAppOpenId, e);
        }

        return null;
    }

    /**
    * 获取unionid从wechatapi
    *
    * @param authorizerAppId authorizerappid
    * @param openid openid
    * @return 获取unionid从wechatapi的结果
     */
    private String getUnionIdFromWechatApi(String authorizerAppId, String openid) {
        try {
            WxOpenComponentService componentService = wxOpenService.getWxOpenComponentService();
            String componentAccessToken = componentService.getComponentAccessToken(false);

            String url = "https://api.weixin.qq.com/cgi-bin/component/api_get_authorizer_info?component_access_token=" + componentAccessToken;
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("component_appid", componentAppId);
            requestBody.put("authorizer_appid", authorizerAppId);

            String responseText = doPost(url, OBJECT_MAPPER.writeValueAsString(requestBody));
            Map<String, Object> response = OBJECT_MAPPER.readValue(responseText, new TypeReference<Map<String, Object>>() {});

            Integer errcode = (Integer) response.get("errcode");
            if (errcode != null && errcode != 0) {
                return null;
            }

            Map<String, Object> authorizerInfo = (Map<String, Object>) response.get("authorizer_info");
            if (authorizerInfo != null) {
                return (String) authorizerInfo.get("unionid");
            }

            return null;
        } catch (Exception e) {
            log.error("获取authorizer_info失败: authorizerAppId={}", authorizerAppId, e);
            return null;
        }
    }

    /**
    * 执行post
    *
    * @param urlStr urlstr
    * @param body 主体
    * @return 执行post的结果
     */
    private String doPost(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        InputStream is = responseCode >= 200 && responseCode < 300 ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
        }

        if (responseCode >= 400) {
            throw new RuntimeException("HTTP请求失败: " + responseCode + " " + sb);
        }

        return sb.toString();
    }
}
