package com.chua.common.support.ai.image;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.DefaultObjectContext;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * AI 图片与视频任务 URL 映射过滤器。
 *
 * <p>基于 {@link UrlMappingServerFilter} 提供异步图片/视频生成任务的创建、查询与历史查询 REST 接口。
 * 后台通过 {@link ScheduledExecutorService} 定时轮询任务状态。
 * 业务客户端由 {@link Supplier} 注入，便于在运行时按 provider 切换实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AiImageUrlServerFilter extends UrlMappingServerFilter {

    /**
     * 路径：图片任务创建与查询
     */
    private static final String PATH_IMAGE_TASK = "/v2/ai/image/generations/task";

    /**
     * 路径：视频任务创建与查询
     */
    private static final String PATH_VIDEO_TASK = "/v2/ai/video/generations/task";

    /**
     * 路径：图片历史记录查询
     */
    private static final String PATH_IMAGE_HISTORY = "/v2/ai/image/generations/history";

    /**
     * 任务类型：图片
     */
    private static final String TYPE_IMAGE = "IMAGE";

    /**
     * 任务类型：视频
     */
    private static final String TYPE_VIDEO = "VIDEO";

    /**
     * 任务状态：等待中
     */
    private static final String STATUS_PENDING = "PENDING";

    /**
     * 请求参数：提示词
     */
    private static final String PARAM_PROMPT = "prompt";

    /**
     * 请求参数：任务 ID
     */
    private static final String PARAM_TASK_ID = "taskId";

    /**
     * 请求参数：任务 ID（备选名称）
     */
    private static final String PARAM_ID = "id";

    /**
     * 响应字段：任务 ID
     */
    private static final String FIELD_TASK_ID = "taskId";

    /**
     * 响应字段：任务状态
     */
    private static final String FIELD_STATUS = "status";

    /**
     * 响应字段：进度
     */
    private static final String FIELD_PROGRESS = "progress";

    /**
     * 响应字段：图片地址
     */
    private static final String FIELD_IMAGE_URL = "imageUrl";

    /**
     * 响应字段：错误信息
     */
    private static final String FIELD_ERROR_MESSAGE = "errorMessage";

    /**
     * 响应字段：成功标识
     */
    private static final String FIELD_SUCCESS = "success";

    /**
     * 响应字段：错误内容
     */
    private static final String FIELD_ERROR = "error";

    /**
     * 响应字段：列表
     */
    private static final String FIELD_LIST = "list";

    /**
     * 响应字段：任务类型
     */
    private static final String FIELD_TYPE = "type";

    /**
     * 响应字段：创建时间
     */
    private static final String FIELD_CREATE_TIME = "createTime";

    /**
     * JSON 响应内容类型
     */
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

    /**
     * 轮询线程名称前缀
     */
    private static final String POLL_THREAD_NAME = "com-ch-ai-image-poll";

    /**
     * 轮询线程核心数
     */
    private static final int POLL_CORE_POOL_SIZE = 1;

    /**
     * 轮询首次延迟（秒）
     */
    private static final long POLL_INITIAL_DELAY_SECONDS = 2L;

    /**
     * 轮询周期（秒）
     */
    private static final long POLL_PERIOD_SECONDS = 2L;

    /**
     * 历史记录保留时长（毫秒），超过该时长的任务不再展示在历史中
     */
    private static final long HISTORY_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    /**
     * 进度字段为空时使用的默认值
     */
    private static final int PROGRESS_DEFAULT = 0;

    /**
     * 业务客户端提供者，用于按需创建 {@link ImageClient}
     */
    private final Supplier<ImageClient> clientSupplier;

    /**
     * 任务表：任务 ID → 任务条目
     */
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();

    /**
     * 任务轮询调度器
     */
    private final ScheduledExecutorService scheduler =
            ThreadUtils.newScheduledThreadPool(POLL_CORE_POOL_SIZE, POLL_THREAD_NAME);

    /**
     * 构造过滤器，使用默认 {@link ObjectContext}。
     *
     * @param clientSupplier 业务客户端提供者
     */
    public AiImageUrlServerFilter(Supplier<ImageClient> clientSupplier) {
        this(clientSupplier, new DefaultObjectContext());
    }

    /**
     * 构造过滤器。
     *
     * @param clientSupplier 业务客户端提供者
     * @param objectContext  对象上下文
     */
    public AiImageUrlServerFilter(Supplier<ImageClient> clientSupplier, ObjectContext objectContext) {
        super(objectContext);
        this.clientSupplier = clientSupplier;
        registerRoutes();
        scheduler.scheduleAtFixedRate(
                this::pollTasks,
                POLL_INITIAL_DELAY_SECONDS,
                POLL_PERIOD_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * 注册 URL 路由。
     */
    private void registerRoutes() {
        route(PATH_IMAGE_TASK, this::handleImageTask);
        route(PATH_VIDEO_TASK, this::handleVideoTask);
        route(PATH_IMAGE_HISTORY, this::handleHistory);
    }

    /**
     * 处理图片任务请求，GET 查询、其它方法创建。
     *
     * @param req 请求对象
     * @param res 响应对象
     */
    private void handleImageTask(ServerRequest req, ServerResponse res) {
        if (HttpMethod.GET == req.getMethod()) {
            handleQueryTask(req, res, TYPE_IMAGE);
        } else {
            handleCreateTask(req, res, TYPE_IMAGE);
        }
    }

    /**
     * 处理视频任务请求，GET 查询、其它方法创建。
     *
     * @param req 请求对象
     * @param res 响应对象
     */
    private void handleVideoTask(ServerRequest req, ServerResponse res) {
        if (HttpMethod.GET == req.getMethod()) {
            handleQueryTask(req, res, TYPE_VIDEO);
        } else {
            handleCreateTask(req, res, TYPE_VIDEO);
        }
    }

    /**
     * 处理创建任务请求。
     *
     * @param req  请求对象
     * @param res  响应对象
     * @param type 任务类型（图片/视频）
     */
    private void handleCreateTask(ServerRequest req, ServerResponse res, String type) {
        try {
            Map<String, Object> body = Json.fromJson(req.getBodyString(), Map.class);
            Object promptObj = body != null ? body.get(PARAM_PROMPT) : req.getParam(PARAM_PROMPT);
            String prompt = promptObj instanceof String ? (String) promptObj : "";
            if (prompt.isBlank()) {
                writeFail(res, "prompt is required");
                return;
            }
            ImageClient client = clientSupplier.get();
            if (client == null) {
                writeFail(res, "ImageClient not configured for " + type);
                return;
            }
            String taskId = client.createTask(prompt);
            tasks.put(taskId, new TaskEntry(taskId, type, System.currentTimeMillis(), client));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(FIELD_TASK_ID, taskId);
            result.put(FIELD_STATUS, STATUS_PENDING);
            writeJson(res, result);
        } catch (Exception e) {
            log.error("create {} task error", type, e);
            writeFail(res, e.getMessage());
        }
    }

    /**
     * 处理任务查询请求。
     *
     * @param req          请求对象
     * @param res          响应对象
     * @param expectedType 期望的任务类型
     */
    private void handleQueryTask(ServerRequest req, ServerResponse res, String expectedType) {
        try {
            String taskId = req.getParam(PARAM_TASK_ID);
            if (taskId == null || taskId.isBlank()) {
                taskId = req.getParam(PARAM_ID);
            }
            if (taskId == null || taskId.isBlank()) {
                writeFail(res, "taskId is required");
                return;
            }
            TaskEntry entry = tasks.get(taskId);
            if (entry == null) {
                writeFail(res, "task not found: " + taskId);
                return;
            }
            ImageClient client = entry.client;
            ImageResponse ir = client.queryTask(taskId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(FIELD_TASK_ID, taskId);
            result.put(FIELD_STATUS, ir.getStatus() != null ? ir.getStatus().name() : STATUS_PENDING);
            result.put(FIELD_PROGRESS, ir.getProgress() != null ? ir.getProgress() : PROGRESS_DEFAULT);
            result.put(FIELD_IMAGE_URL, ir.getImageUrl());
            result.put(FIELD_ERROR_MESSAGE, ir.getErrorMessage());
            writeJson(res, result);
        } catch (Exception e) {
            log.error("query {} task error", expectedType, e);
            writeFail(res, e.getMessage());
        }
    }

    /**
     * 处理历史记录查询请求。
     *
     * @param req 请求对象
     * @param res 响应对象
     */
    private void handleHistory(ServerRequest req, ServerResponse res) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (TaskEntry entry : tasks.values()) {
                if (now - entry.createTime > HISTORY_TTL_MILLIS) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put(FIELD_TASK_ID, entry.taskId);
                item.put(FIELD_TYPE, entry.type);
                item.put(FIELD_CREATE_TIME, entry.createTime);
                list.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(FIELD_LIST, list);
            writeJson(res, result);
        } catch (Exception e) {
            log.error("history error", e);
            writeFail(res, e.getMessage());
        }
    }

    /**
     * 轮询所有任务，更新本地状态。终态任务仅记录日志。
     */
    private void pollTasks() {
        for (TaskEntry entry : tasks.values()) {
            try {
                ImageResponse ir = entry.client.queryTask(entry.taskId);
                if (ir.getStatus() == ImageResponse.Status.SUCCESS || ir.getStatus() == ImageResponse.Status.FAILED) {
                    log.debug("task {} completed with status {}", entry.taskId, ir.getStatus());
                }
            } catch (Exception e) {
                log.debug("poll task {} error: {}", entry.taskId, e.getMessage());
            }
        }
    }

    /**
     * 将对象以 JSON 形式写入响应。
     *
     * @param res  响应对象
     * @param data 待序列化数据
     */
    private static void writeJson(ServerResponse res, Object data) {
        try {
            res.setContentType(CONTENT_TYPE_JSON);
            res.end(Json.toJson(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 向响应写入失败信息。
     *
     * @param res 响应对象
     * @param msg 错误描述
     */
    private static void writeFail(ServerResponse res, String msg) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put(FIELD_SUCCESS, false);
        err.put(FIELD_ERROR, msg);
        writeJson(res, err);
    }

    /**
     * 任务条目，记录任务的客户端与创建时间，便于轮询时反查。
     */
    private static class TaskEntry {
        /**
         * 任务 ID
         */
        final String taskId;
        /**
         * 任务类型
         */
        final String type;
        /**
         * 创建时间戳（毫秒）
         */
        final long createTime;
        /**
         * 任务所属的客户端实例
         */
        final ImageClient client;

        /**
         * 构造任务条目。
         *
         * @param taskId     任务 ID
         * @param type       任务类型
         * @param createTime 创建时间戳
         * @param client     任务所属的客户端实例
         */
        TaskEntry(String taskId, String type, long createTime, ImageClient client) {
            this.taskId = taskId;
            this.type = type;
            this.createTime = createTime;
            this.client = client;
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
