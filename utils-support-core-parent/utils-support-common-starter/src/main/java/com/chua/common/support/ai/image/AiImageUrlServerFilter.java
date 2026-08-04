package com.chua.common.support.ai.image;

import com.chua.common.support.objects.DefaultObjectContext;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author CH
 */
@Slf4j
@SuppressWarnings("NullAway")
@NullUnmarked
public class AiImageUrlServerFilter extends UrlMappingServerFilter {

    private final Supplier<ImageClient> clientSupplier;
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public AiImageUrlServerFilter(Supplier<ImageClient> clientSupplier) {
        this(clientSupplier, new DefaultObjectContext());
    }

    public AiImageUrlServerFilter(Supplier<ImageClient> clientSupplier, ObjectContext objectContext) {
        super(objectContext);
        this.clientSupplier = clientSupplier;
        registerRoutes();
        scheduler.scheduleAtFixedRate(this::pollTasks, 2, 2, TimeUnit.SECONDS);
    }

    private void registerRoutes() {
        route("/v2/ai/image/generations/task", this::handleImageTask);
        route("/v2/ai/video/generations/task", this::handleVideoTask);
        route("/v2/ai/image/generations/history", this::handleHistory);
    }

    private void handleImageTask(ServerRequest req, ServerResponse res) {
        if (HttpMethod.GET == req.getMethod()) {
            handleQueryTask(req, res, "IMAGE");
        } else {
            handleCreateTask(req, res, "IMAGE");
        }
    }

    private void handleVideoTask(ServerRequest req, ServerResponse res) {
        if (HttpMethod.GET == req.getMethod()) {
            handleQueryTask(req, res, "VIDEO");
        } else {
            handleCreateTask(req, res, "VIDEO");
        }
    }

    private void handleCreateTask(ServerRequest req, ServerResponse res, String type) {
        try {
            Map<String, Object> body = Json.fromJson(req.getBodyString(), Map.class);
            Object promptObj = body != null ? body.get("prompt") : req.getParam("prompt");
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
            result.put("taskId", taskId);
            result.put("status", "PENDING");
            writeJson(res, result);
        } catch (Exception e) {
            log.error("create {} task error", type, e);
            writeFail(res, e.getMessage());
        }
    }

    private void handleQueryTask(ServerRequest req, ServerResponse res, String expectedType) {
        try {
            String taskId = req.getParam("taskId");
            if (taskId == null || taskId.isBlank()) {
                taskId = req.getParam("id");
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
            result.put("taskId", taskId);
            result.put("status", ir.getStatus() != null ? ir.getStatus().name() : "PENDING");
            result.put("progress", ir.getProgress() != null ? ir.getProgress() : 0);
            result.put("imageUrl", ir.getImageUrl());
            result.put("errorMessage", ir.getErrorMessage());
            writeJson(res, result);
        } catch (Exception e) {
            log.error("query {} task error", expectedType, e);
            writeFail(res, e.getMessage());
        }
    }

    private void handleHistory(ServerRequest req, ServerResponse res) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (TaskEntry entry : tasks.values()) {
                if (now - entry.createTime > 86400000) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taskId", entry.taskId);
                item.put("type", entry.type);
                item.put("createTime", entry.createTime);
                list.add(item);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", list);
            writeJson(res, result);
        } catch (Exception e) {
            log.error("history error", e);
            writeFail(res, e.getMessage());
        }
    }

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

    private static void writeJson(ServerResponse res, Object data) {
        try {
            res.setContentType("application/json; charset=utf-8");
            res.end(Json.toJson(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeFail(ServerResponse res, String msg) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("error", msg);
        writeJson(res, err);
    }

    private static class TaskEntry {
        final String taskId;
        final String type;
        final long createTime;
        final ImageClient client;

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