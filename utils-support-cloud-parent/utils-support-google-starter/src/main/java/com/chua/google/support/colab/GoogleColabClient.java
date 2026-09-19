package com.chua.google.support.colab;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.aiplatform.v1.CreateNotebookExecutionJobRequest;
import com.google.cloud.aiplatform.v1.JobState;
import com.google.cloud.aiplatform.v1.NotebookExecutionJob;
import com.google.cloud.aiplatform.v1.NotebookRuntime;
import com.google.cloud.aiplatform.v1.NotebookRuntimeTemplate;
import com.google.cloud.aiplatform.v1.NotebookServiceClient;
import com.google.cloud.aiplatform.v1.NotebookServiceSettings;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Colab Enterprise 客户端
 *
 * <p>基于 Vertex AI NotebookService API（aiplatform.googleapis.com）封装，
 * 提供 Colab Enterprise 运行时管理与笔记本执行能力：
 * <ul>
 *   <li>运行时管理：查询、启动、停止、删除 Notebook 运行时</li>
 *   <li>运行时模板：查询可用的运行时模板</li>
 *   <li>执行作业：提交 GCS 上的 .ipynb 笔记本执行任务，查询执行状态与结果</li>
 * </ul>
 *
 * <p>注意：普通版 Colab（colab.research.google.com）不提供公开 API，
 * 本客户端仅支持 Colab Enterprise，需要 GCP 项目与服务账号凭据。
 *
 * @author CH
 * @see <a href="https://cloud.google.com/colab/docs/reference/rest">Colab Enterprise REST API</a>
 * @since 4.0.0.42
 */
@Slf4j
public class GoogleColabClient implements AutoCloseable {

    /**
     * 默认区域
     */
    public static final String DEFAULT_LOCATION = "us-central1";

    /**
     * 执行状态轮询间隔（毫秒）
     */
    private static final long POLL_INTERVAL_MILLIS = 5_000L;

    /**
     * Vertex AI notebook服务 客户端
     */
    private final NotebookServiceClient client;

    /**
     * GCP 项目 标识
     */
    private final String projectId;

    /**
     * 区域
     */
    private final String location;

    /**
     * 资源父路径：projects/{project}/位置/{位置}
     */
    private final String parent;

    /**
     * 创建 Google Colab Enterprise 客户端
     *
     * @param projectId          GCP 项目 标识
     * @param location           区域，如 us-中央1、asia-east1
     * @param serviceAccountJson 服务账号 JSON 密钥内容，为空时使用 ADC 凭据链
     */
    public GoogleColabClient(String projectId, String location, String serviceAccountJson) {
        this.projectId = projectId;
        this.location = location != null && !location.isBlank() ? location : DEFAULT_LOCATION;
        this.parent = "projects/" + projectId + "/locations/" + this.location;
        try {
            Credentials credentials = resolveCredentials(serviceAccountJson);
            NotebookServiceSettings settings = NotebookServiceSettings.newBuilder()
                    .setEndpoint(this.location + "-aiplatform.googleapis.com:443")
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
            this.client = NotebookServiceClient.create(settings);
        } catch (Exception e) {
            throw new IllegalStateException("创建 Colab Enterprise 客户端失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 Google Colab Enterprise 客户端（默认区域）
     *
     * @param projectId          GCP 项目 标识
     * @param serviceAccountJson 服务账号 JSON 密钥内容，为空时使用 ADC 凭据链
     */
    public GoogleColabClient(String projectId, String serviceAccountJson) {
        this(projectId, DEFAULT_LOCATION, serviceAccountJson);
    }

    /**
     * 解析 GCP 凭据。
     *
     * <p>解析优先级：
     * <ol>
     *   <li>将 {@code secret} 作为服务账号 JSON 密钥内容解析</li>
     *   <li>若解析失败，降级为 {@link GoogleCredentials#getApplicationDefault()}（ADC 凭据链）</li>
     * </ol>
     *
     * @param secret 密钥内容（JSON 字符串或任意字符串）
     * @return 解析后的凭据，不会为 空
     */
    private static Credentials resolveCredentials(String secret) {
        if (secret != null && !secret.isBlank()) {
            String trimmed = secret.trim();
            if (trimmed.startsWith("{")) {
                try {
                    return ServiceAccountCredentials.fromStream(
                            new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8)));
                } catch (Exception ignored) {
                }
            }
        }
        try {
            return GoogleCredentials.getApplicationDefault();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法创建 Colab Enterprise 凭据：服务账号 JSON 无效，且 ADC 也未配置。"
                            + "请设置 GOOGLE_APPLICATION_CREDENTIALS 环境变量或提供有效的服务账号 JSON。", e);
        }
    }

    /**
     * 查询运行时列表
     *
     * @return 当前项目与区域下的全部 Notebook 运行时
     */
    public List<RuntimeInfo> listRuntimes() {
        List<RuntimeInfo> result = new ArrayList<>();
        for (NotebookRuntime runtime : client.listNotebookRuntimes(parent).iterateAll()) {
            result.add(RuntimeInfo.builder()
                    .name(runtime.getName())
                    .id(extractId(runtime.getName()))
                    .displayName(runtime.getDisplayName())
                    .state(runtime.getRuntimeState().name())
                    .proxyUri(runtime.getProxyUri())
                    .build());
        }
        return result;
    }

    /**
     * 查询单个运行时
     *
     * @param runtimeId 运行时 标识
     * @return 运行时信息
     */
    public RuntimeInfo getRuntime(String runtimeId) {
        NotebookRuntime runtime = client.getNotebookRuntime(runtimeName(runtimeId));
        return RuntimeInfo.builder()
                .name(runtime.getName())
                .id(runtimeId)
                .displayName(runtime.getDisplayName())
                .state(runtime.getRuntimeState().name())
                .proxyUri(runtime.getProxyUri())
                .build();
    }

    /**
     * 启动运行时（阻塞直至启动完成）
     *
     * @param runtimeId 运行时 标识
     */
    public void startRuntime(String runtimeId) {
        awaitQuietly(client.startNotebookRuntimeAsync(runtimeName(runtimeId)));
    }

    /**
     * 停止运行时（阻塞直至停止完成）
     *
     * @param runtimeId 运行时 标识
     */
    public void stopRuntime(String runtimeId) {
        awaitQuietly(client.stopNotebookRuntimeAsync(runtimeName(runtimeId)));
    }

    /**
     * 删除运行时（阻塞直至删除完成）
     *
     * @param runtimeId 运行时 标识
     */
    public void deleteRuntime(String runtimeId) {
        awaitQuietly(client.deleteNotebookRuntimeAsync(runtimeName(runtimeId)));
    }

    /**
     * 查询运行时模板列表
     *
     * @return 当前项目与区域下的全部运行时模板
     */
    public List<TemplateInfo> listRuntimeTemplates() {
        List<TemplateInfo> result = new ArrayList<>();
        for (NotebookRuntimeTemplate template : client.listNotebookRuntimeTemplates(parent).iterateAll()) {
            result.add(TemplateInfo.builder()
                    .name(template.getName())
                    .id(extractId(template.getName()))
                    .displayName(template.getDisplayName())
                    .build());
        }
        return result;
    }

    /**
     * 提交笔记本执行作业。
     *
     * <p>提交后立即返回，通过 {@link #getExecution(String)} 或
     * {@link #waitExecution(String, long)} 跟踪执行进度；
     * 执行完成后结果写入 {@code outputUri} 对应的 GCS 目录。
     *
     * @param notebookUri      待执行笔记本的 GCS 地址，如 gs://bucket/路径/notebook.ipynb
     * @param outputUri        执行输出目录，如 gs://bucket/输出/
     * @param templateId       运行时模板 标识 或完整资源名
     * @param displayName      作业显示名称
     * @return 执行作业信息
     */
    public ExecutionInfo executeNotebook(String notebookUri, String outputUri,
                                         String templateId, String displayName) {
        String jobId = "exec-" + java.util.UUID.randomUUID();
        NotebookExecutionJob job = NotebookExecutionJob.newBuilder()
                .setDisplayName(displayName == null || displayName.isBlank() ? jobId : displayName)
                .setGcsNotebookSource(NotebookExecutionJob.GcsNotebookSource.newBuilder()
                        .setUri(notebookUri))
                .setGcsOutputUri(outputUri)
                .setNotebookRuntimeTemplateResourceName(templateResource(templateId))
                .build();
        CreateNotebookExecutionJobRequest request = CreateNotebookExecutionJobRequest.newBuilder()
                .setParent(parent)
                .setNotebookExecutionJob(job)
                .setNotebookExecutionJobId(jobId)
                .build();

        client.createNotebookExecutionJobCallable().call(request);
        log.info("Colab Enterprise 执行作业已提交: {}", jobId);
        return getExecution(jobId);
    }

    /**
     * 查询执行作业状态
     *
     * @param executionId 执行作业 标识
     * @return 执行作业信息
     */
    public ExecutionInfo getExecution(String executionId) {
        NotebookExecutionJob job = client.getNotebookExecutionJob(executionName(executionId));
        return toExecutionInfo(job);
    }

    /**
     * 查询执行作业列表
     *
     * @return 全部执行作业
     */
    public List<ExecutionInfo> listExecutions() {
        List<ExecutionInfo> result = new ArrayList<>();
        for (NotebookExecutionJob job : client.listNotebookExecutionJobs(parent).iterateAll()) {
            result.add(toExecutionInfo(job));
        }
        return result;
    }

    /**
     * 等待执行作业到达终态（成功/失败/取消等）。
     *
     * @param executionId 执行作业 标识
     * @param timeoutMillis 最长等待时间（毫秒），超时后返回当前状态
     * @return 最新执行作业信息
     */
    public ExecutionInfo waitExecution(String executionId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        ExecutionInfo info = getExecution(executionId);
        while (!isTerminal(info.getState()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            info = getExecution(executionId);
        }
        return info;
    }

    /**
     * 删除执行作业
     *
     * @param executionId 执行作业 标识
     */
    public void deleteExecution(String executionId) {
        awaitQuietly(client.deleteNotebookExecutionJobAsync(executionName(executionId)));
    }

    /**
     * 判断执行状态是否为终态（成功/失败/取消/过期/部分成功）。
     *
     * @param state 状态名
     * @return 是否终态
     */
    private boolean isTerminal(String state) {
        return JobState.JOB_STATE_SUCCEEDED.name().equals(state)
                || JobState.JOB_STATE_FAILED.name().equals(state)
                || JobState.JOB_STATE_CANCELLED.name().equals(state)
                || JobState.JOB_STATE_EXPIRED.name().equals(state)
                || JobState.JOB_STATE_PARTIALLY_SUCCEEDED.name().equals(state);
    }

    /**
     * 转为执行信息。
     *
     * @param job 作业
     * @return 转为执行信息的结果
     */
    private ExecutionInfo toExecutionInfo(NotebookExecutionJob job) {
        return ExecutionInfo.builder()
                .name(job.getName())
                .id(extractId(job.getName()))
                .displayName(job.getDisplayName())
                .state(job.getJobState().name())
                .message(job.hasStatus() ? job.getStatus().getMessage() : null)
                .gcsOutputUri(job.getGcsOutputUri())
                .createTime(job.hasCreateTime() ? String.valueOf(job.getCreateTime()) : null)
                .build();
    }

    /**
     * runtime名称。
     *
     * @param runtimeId runtimeID，不允许为 null
     * @return 结果字符串
     */
    private String runtimeName(String runtimeId) {
        return runtimeId.contains("/") ? runtimeId : parent + "/notebookRuntimes/" + runtimeId;
    /**
     * templateresource。
     * @param templateId templateid
     * @return templateResource的结果
     */
    }

    /**
     * 模板Resource。
     *
     * @param templateId 模板ID，不允许为 null
     * @return 结果字符串
     */
    private String templateResource(String templateId) {
        return templateId.contains("/") ? templateId : parent + "/notebookRuntimeTemplates/" + templateId;
    /**
     * 执行名称。
     * @param executionId 执行标识
     * @return 执行名称的结果
     * @param future 期货
     * @param resourceName resource名称
     */
    }

    /**
     * execution名称。
     *
     * @param executionId executionID，不允许为 null
     * @return 结果字符串
     */
    private String executionName(String executionId) {
        return executionId.contains("/") ? executionId : parent + "/notebookExecutionJobs/" + executionId;
    }

    /**
     * extractID。
     *
     * @param resourceName resource名称，不允许为 null
     * @return 结果字符串
     */
    private static String extractId(String resourceName) {
        if (resourceName == null || !resourceName.contains("/")) {
            return resourceName;
        }
        return resourceName.substring(resourceName.lastIndexOf('/') + 1);
    }

    /**
     * awaitQuietly。
     *
     * @param future 方法入参 future
     */
    private static void awaitQuietly(com.google.api.gax.longrunning.OperationFuture<?, ?> future) {
        try {
            future.get();
        } catch (Exception e) {
            throw new RuntimeException("操作失败: " + e.getMessage(), e);
        }
    }

    @Override
    /** 关闭客户端并释放底层资源 */
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    /**
    * 运行时信息
    * @author CH
    * @since 4.0.0
    */
    @Data
    @Builder
    public static class RuntimeInfo {

        /** 完整资源名 */
        private String name;

        /** 运行时 标识 */
        private String id;

        /** 显示名称 */
        private String displayName;

        /** 状态：RUNNING、STOPPED、存在_启动 等 */
        private String state;

        /** Jupyter 代理地址，运行中可用该地址访问内核 */
        private String proxyUri;
    }

    /**
        * 运行时模板信息
        * @author CH
        * @since 4.0.0
        */
    @Data
    @Builder
    public static class TemplateInfo {

        /** 完整资源名 */
        private String name;

        /** 模板 标识 */
        private String id;

        /** 显示名称 */
        private String displayName;
    }

    /**
        * 执行作业信息
        * @author CH
        * @since 4.0.0
        */
    @Data
    @Builder
    public static class ExecutionInfo {

        /** 完整资源名 */
        private String name;

        /** 执行作业 标识 */
        private String id;

        /** 显示名称 */
        private String displayName;

        /** 状态：队列、PENDING、RUNNING、SUCCEEDED、失败 等 */
        private String state;

        /** 错误或状态描述 */
        private String message;

        /** 输出目录 */
        private String gcsOutputUri;

        /** 创建时间 */
        private String createTime;
    }
}
