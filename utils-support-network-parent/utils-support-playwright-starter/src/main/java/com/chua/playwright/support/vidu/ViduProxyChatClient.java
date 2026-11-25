// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.playwright.support.vidu;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.generation.VideoGenerationResult;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Vidu 逆向代理对话客户端。
 *
 * <p>基于 {@link ViduBrowserSession} 在 Playwright 浏览器页面内以 Cookie 登录态
 * 调用 {@code service.vidu.cn} 的任务接口：创建文生视频任务 → 轮询任务状态 →
 * 解析视频结果。与 Vidu 网页前端的请求链路一致，{@code appKey} 为 Cookie 串。</p>
 *
 * <p>SPI 名称：{@code vidu-proxy}。本实现只承载视频生成
 * （{@link #generateVideo}），文本对话不支持。</p>
 *
 * <p>用法：
 * <pre>{@code
 * ChatClient client = ChatClient.create("vidu-proxy", "sessionid=...; uid=...");
 * VideoGenerationResult result = client.model("3.2")
 *     .generateVideo("一只柯基在海边奔跑", "16:9");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Slf4j
@Spi("vidu-proxy")
@ConditionalOnClass("com.microsoft.playwright.Playwright")
public class ViduProxyChatClient implements ChatClient {

    /**
     * 默认 Vidu 中国站服务基址。
     */
    private static final String DEFAULT_BASE_URL = "https://service.vidu.cn";

    /**
     * 创建任务端点路径。
     */
    private static final String CREATE_TASK_PATH = "/vidu/v1/tasks";

    /**
     * 默认模型版本（vidu.cn 网页端当前默认 3.2）。
     */
    private static final String DEFAULT_MODEL_VERSION = "3.2";

    /**
     * 默认时长（秒，网页端 V2.0 档位支持 4 / 8）。
     */
    private static final String DEFAULT_DURATION = "4";

    /**
     * 默认分辨率。
     */
    private static final String DEFAULT_RESOLUTION = "720p";

    /**
     * 默认宽高比。
     */
    private static final String DEFAULT_ASPECT_RATIO = "16:9";

    /**
     * 默认超时时间（秒）。
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * 轮询间隔（毫秒）。
     */
    private static final long POLL_INTERVAL_MILLIS = 5000;

    /**
     * 浏览器会话。
     */
    private final ViduBrowserSession session;

    /**
     * 客户端配置。
     */
    private final ChatClientSetting setting;

    /**
     * 当前模型名称（映射为 Vidu 的 model_version）。
     */
    private String model;

    /**
     * 构造 Vidu 逆向代理对话客户端。
     *
     * @param setting 客户端配置，其中 appKey 为 Cookie 串
     */
    public ViduProxyChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.session = new ViduBrowserSession(setting.getAppKey());
        this.session.init();
    }

    /**
     * Model
     */
    @Override
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    /**
     * 本实现仅承载视频生成，不支持文本对话。
     */
    @Override
    public String chatSync(String prompt) {
        throw new UnsupportedOperationException("vidu-proxy 仅支持视频生成（generateVideo），不支持文本对话");
    }

    /**
     * GenerateVideo
     * @param prompt prompt
     * @param ratio ratio
     * @param cameraMovement cameraMovement
     * @param refImageKey refImageKey
     * @param timeoutSeconds timeoutSeconds
     */
    @Override
    public VideoGenerationResult generateVideo(String prompt, String ratio,
                                               String cameraMovement, String refImageKey,
                                               int timeoutSeconds) {
        return doGenerateVideo(prompt, ratio, cameraMovement, timeoutSeconds);
    }

    /**
     * GenerateVideo
     */
    @Override
    public VideoGenerationResult generateVideo(String prompt, String ratio) {
        return doGenerateVideo(prompt, ratio, null, 0);
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        session.close();
    }

    /**
     * 创建任务并轮询到终态。
     *
     * @param prompt         提示词
     * @param ratio          宽高比，可为空
     * @param cameraMovement 镜头运动，可为空
     * @param timeoutSeconds 超时秒数，非正数时用默认超时
     * @return 视频生成结果
     */
    private VideoGenerationResult doGenerateVideo(String prompt, String ratio,
                                                  String cameraMovement, int timeoutSeconds) {
        if (StringUtils.isEmpty(prompt)) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        String baseUrl = setting.getBaseUrl() != null && !setting.getBaseUrl().isBlank()
                ? setting.getBaseUrl() : DEFAULT_BASE_URL;

        // 请求体顶层形态需以带登录态 Cookie 的真实提交为准；字段名与取值范围
        // 来自 vidu.cn 前端 settings 配置快照（model_version / duration /
        // resolution / aspect_ratio / movement_amplitude / sample_count ...）。
        JsonObject settings = JsonObject.create()
                .fluent("model_version", StringUtils.isNotEmpty(model) ? model : DEFAULT_MODEL_VERSION)
                .fluent("duration", DEFAULT_DURATION)
                .fluent("resolution", DEFAULT_RESOLUTION)
                .fluent("aspect_ratio", StringUtils.isNotEmpty(ratio) ? ratio : DEFAULT_ASPECT_RATIO)
                .fluent("movement_amplitude", StringUtils.isNotEmpty(cameraMovement) ? cameraMovement : "auto")
                .fluent("sample_count", 1)
                .fluent("schedule_mode", "normal")
                .fluent("codec", "h264");

        JsonObject body = JsonObject.create()
                .fluent("type", "text2video")
                .fluent("prompt", prompt)
                .fluent("settings", settings);

        Map<String, Object> created = session.fetchJson("POST", baseUrl + CREATE_TASK_PATH, body.toJSONString());
        String taskId = extractTaskId(created);
        if (StringUtils.isEmpty(taskId)) {
            throw new RuntimeException("Vidu 任务创建失败: " + created);
        }
        log.info("Vidu 任务已创建: {}", taskId);
        return pollVideoResult(baseUrl, taskId, prompt, timeoutSeconds);
    }

    /**
     * 轮询任务状态直到成功 / 失败 / 超时。
     *
     * @param baseUrl        服务基址
     * @param taskId         任务 ID
     * @param prompt         提示词
     * @param timeoutSeconds 超时秒数
     * @return 视频生成结果
     */
    private VideoGenerationResult pollVideoResult(String baseUrl, String taskId,
                                                  String prompt, int timeoutSeconds) {
        long timeoutMillis = (timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds) * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> response = session.fetchJson("GET",
                    baseUrl + CREATE_TASK_PATH + "/" + taskId, null);
            Map<String, Object> task = unwrapTask(response);
            String state = taskState(task);
            if (isSuccessState(state)) {
                return parseVideoResult(task, prompt);
            }
            if (isFailureState(state)) {
                throw new RuntimeException("Vidu 视频生成失败(" + state + "): " + failureMessage(task));
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Vidu 轮询被中断", e);
            }
        }
        throw new RuntimeException("Vidu 视频生成超时（" + (timeoutMillis / 1000) + "s），任务 ID: " + taskId);
    }

    /**
     * 从创建任务响应中提取任务 ID。
     *
     * @param response 创建任务响应
     * @return 任务 ID，提取不到返回 {@code null}
     */
    private static String extractTaskId(Map<String, Object> response) {
        String id = getStr(response, "id", "task_id", "taskId");
        if (StringUtils.isNotEmpty(id)) {
            return id;
        }
        for (String key : new String[]{"data", "task"}) {
            Map<?, ?> nested = getMap(response, key);
            if (nested != null) {
                id = getStr(nested, "id", "task_id", "taskId");
                if (StringUtils.isNotEmpty(id)) {
                    return id;
                }
            }
        }
        return null;
    }

    /**
     * 解包任务对象：兼容裸任务体与 {code, data} / {task} 包装。
     *
     * @param response 任务查询响应
     * @return 任务对象映射
     */
    private static Map<String, Object> unwrapTask(Map<String, Object> response) {
        for (String key : new String[]{"data", "task"}) {
            Map<?, ?> nested = getMap(response, key);
            if (nested != null && (nested.containsKey("state") || nested.containsKey("status")
                    || nested.containsKey("creations"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> task = (Map<String, Object>) nested;
                return task;
            }
        }
        return response;
    }

    /**
     * 读取任务状态字段（state / status）。
     *
     * @param task 任务对象
     * @return 状态小写字符串，无状态时为空串
     */
    private static String taskState(Map<String, Object> task) {
        String state = getStr(task, "state", "status");
        return state == null ? "" : state.trim().toLowerCase();
    }

    /**
     * 是否成功终态。
     *
     * @param state 状态
     * @return true 表示成功
     */
    private static boolean isSuccessState(String state) {
        return "success".equals(state) || "succeeded".equals(state)
                || "completed".equals(state) || "done".equals(state);
    }

    /**
     * 是否失败终态。
     *
     * @param state 状态
     * @return true 表示失败
     */
    private static boolean isFailureState(String state) {
        return "failed".equals(state) || "fail".equals(state) || "error".equals(state)
                || "cancelled".equals(state) || "canceled".equals(state);
    }

    /**
     * 读取失败原因。
     *
     * @param task 任务对象
     * @return 失败原因文本
     */
    private static String failureMessage(Map<String, Object> task) {
        String message = getStr(task, "err_msg", "fail_reason", "error_message", "message");
        return StringUtils.isEmpty(message) ? "未知原因" : message;
    }

    /**
     * 解析视频结果。
     *
     * @param task   成功终态的任务对象
     * @param prompt 提示词
     * @return 视频生成结果
     */
    private VideoGenerationResult parseVideoResult(Map<String, Object> task, String prompt) {
        List<VideoGenerationResult.GeneratedVideo> videos = Wraps.list();
        List<?> creations = getList(task, "creations", "videos", "results");
        if (creations != null) {
            for (Object item : creations) {
                if (item instanceof Map<?, ?> video) {
                    String videoUrl = getStr(video, "url", "video_url");
                    if (StringUtils.isEmpty(videoUrl)) {
                        continue;
                    }
                    videos.add(new VideoGenerationResult.GeneratedVideo(
                            videoUrl,
                            getStr(video, "cover_url", "cover"),
                            toInt(video, "width"),
                            toInt(video, "height"),
                            toDouble(video, "duration")
                    ));
                }
            }
        }
        if (videos.isEmpty()) {
            // 兼容任务对象本身直接携带视频字段的返回形态
            String videoUrl = getStr(task, "url", "video_url");
            if (StringUtils.isNotEmpty(videoUrl)) {
                videos.add(new VideoGenerationResult.GeneratedVideo(
                        videoUrl,
                        getStr(task, "cover_url", "cover"),
                        toInt(task, "width"),
                        toInt(task, "height"),
                        toDouble(task, "duration")
                ));
            }
        }
        if (videos.isEmpty()) {
            throw new RuntimeException("Vidu 任务成功但未解析到视频结果: " + task);
        }
        return new VideoGenerationResult(videos, prompt);
    }

    /**
     * 获取Str
     * @param map 映射，不允许为 null
     * @param keys 方法入参 keys
     * @return 结果字符串
     */
    private static String getStr(Map<?, ?> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 获取映射。
     *
     * @param map 映射，不允许为 null
     * @param key 键，不允许为 null
     * @return 结果映射，无数据时为 {@code null}
     */
    private static Map<?, ?> getMap(Map<?, ?> map, String key) {
        Object val = map != null ? map.get(key) : null;
        return val instanceof Map<?, ?> m ? m : null;
    }

    /**
     * 获取列表。
     *
     * @param map   映射，不允许为 null
     * @param keys  候选键，不允许为 null
     * @return 结果列表，无数据时为 {@code null}
     */
    private static List<?> getList(Map<?, ?> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof List<?> list) {
                return list;
            }
        }
        return null;
    }

    /**
     * ToInt
     * @param map 映射，不允许为 null
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    private static int toInt(Map<?, ?> map, String key) {
        Object val = map != null ? map.get(key) : null;
        if (val instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    /**
     * ToDouble
     * @param map 映射，不允许为 null
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    private static double toDouble(Map<?, ?> map, String key) {
        Object val = map != null ? map.get(key) : null;
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        if (val instanceof String s && !s.isEmpty()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                // 非数值字符串按缺失处理
            }
        }
        return 0.0;
    }
}
