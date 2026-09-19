package com.chua.git.support.operation;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientBuilder;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.model.WorkflowInfo;
import com.chua.git.support.model.WorkflowRun;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * CI 流水线操作（GitHub Actions / Gitee Go）。
 *
 * <p>通过远程平台 REST API 对仓库的 CI 流水线进行操作，不依赖本地仓库即可运行。
 * 支持 GitHub Actions 与 Gitee Go 两类平台，平台、仓库与令牌可通过链式方法配置，
 * 未配置时自动从 GitClient 的远程地址与访问令牌中推断。</p>
 *
 * <p>支持的平台能力如下：</p>
 * <ul>
 *   <li><b>GitHub Actions</b>：查询工作流、查询运行列表、查询运行详情、触发工作流、取消运行、下载日志</li>
 *   <li><b>Gitee Go</b>：触发流水线（需在 Gitee Go 中开启 API 触发）；其余能力受限于 Gitee 开放接口暂不支持</li>
 * </ul>
 *
 * <pre>示例：
 * {@code
 * // 1. 查询仓库所有 CI 工作流
 * List<WorkflowInfo> workflows = client.actions().listWorkflows();
 *
 * // 2. 查询最近运行记录
 * List<WorkflowRun> runs = client.actions().listRuns();
 *
 * // 3. 触发指定工作流（GitHub 按工作流文件路径或 ID）
 * client.actions().trigger(".github/workflows/ci.yml", "main");
 *
 * // 4. 取消一次运行
 * client.actions().cancel(12345678L);
 *
 * // 5. 下载一次运行的日志（原始字节，GitHub 返回 zip 压缩包）
 * byte[] logs = client.actions().logs(12345678L);
 *
 * // 6. 覆盖默认平台与仓库
 * client.actions().platform(ActionsOperation.CiPlatform.GITEE)
 *         .owner("user").repo("repo").token("gitee-token")
 *         .trigger(null, "main");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ActionsOperation {

    /**
     * GitHub Actions REST API 基础地址。
     */
    private static final String GITHUB_API_BASE = "https://api.github.com";

    /**
     * Gitee API v5 基础地址。
     */
    private static final String GITEE_API_BASE = "https://gitee.com/api/v5";

    /**
     * CI 平台类型。
     */
    public enum CiPlatform {

        /** GitHub Actions 平台 */
        GITHUB,

        /** Gitee Go 流水线平台 */
        GITEE
    }

    /**
     * 所属 git客户端。
     */
    private final GitClient client;

    /**
     * 仓库所属者（用户或组织），未配置时从远程地址推断。
     */
    private String owner;

    /**
     * 仓库名称，未配置时从远程地址推断。
     */
    private String repo;

    /**
     * CI 平台访问令牌，未配置时取 git客户端 的访问令牌。
     */
    private String token;

    /**
     * CI 平台类型，未配置时根据远程地址自动识别。
     */
    private CiPlatform platform;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * <p>构造时尝试从 {@code GitClient} 的远程地址解析仓库所属者、仓库名与平台，
     * 并继承访问令牌，后续可通过链式方法覆盖。</p>
     *
     * @param client 所属 Git客户端
     */
    public ActionsOperation(GitClient client) {
        this.client = client;
        String remoteUrl = client.getRemoteUrl();
        String[] ownerAndRepo = resolveOwnerAndRepo(remoteUrl);
        if (ownerAndRepo != null) {
            this.owner = ownerAndRepo[0];
            this.repo = ownerAndRepo[1];
        }
        this.platform = resolvePlatform(remoteUrl);
        this.token = client.getAccessToken();
    }

    // ==================== 配置方法 ====================

    /**
     * 设置仓库所属者（用户或组织）。
     *
     * @param owner 所属者名称，如 "chua"
     * @return 当前操作实例
     */
    public ActionsOperation owner(String owner) {
        this.owner = owner;
        return this;
    }

    /**
     * 设置仓库名称。
     *
     * @param repo 仓库名称，如 "utils-support-parent-starter"
     * @return 当前操作实例
     */
    public ActionsOperation repo(String repo) {
        this.repo = repo;
        return this;
    }

    /**
     * 设置 CI 平台访问令牌。
     *
     * <p>GitHub 使用 Personal Access Token，Gitee 使用私人令牌。</p>
     *
     * @param token 平台访问令牌
     * @return 当前操作实例
     */
    public ActionsOperation token(String token) {
        this.token = token;
        return this;
    }

    /**
     * 设置 CI 平台类型，覆盖自动识别结果。
     *
     * @param platform 平台类型
     * @return 当前操作实例
     */
    public ActionsOperation platform(CiPlatform platform) {
        this.platform = platform;
        return this;
    }

    // ==================== 查询方法 ====================

    /**
     * 查询仓库全部 CI 工作流。
     *
     * <p>仅 GitHub Actions 支持；Gitee Go 因开放接口限制不支持该操作。</p>
     *
     * @return 工作流信息列表
     */
    public List<WorkflowInfo> listWorkflows() {
        requirePlatform(CiPlatform.GITHUB, "listWorkflows");
        ClientResponse response = newRequest()
                .path("/repos/" + owner + "/" + repo + "/actions/workflows")
                .get();
        checkResponse(response, "查询 CI 工作流列表");
        List<WorkflowInfo> result = new ArrayList<>();
        JsonNode root = Json.parse(response.getBodyString());
        JsonNode workflows = root.get("workflows");
        if (workflows != null && workflows.isArray()) {
            for (int i = 0; i < workflows.size(); i++) {
                result.add(toWorkflowInfo(workflows.get(i)));
            }
        }
        log.info("CI 工作流查询完成: {}/{}, 共 {} 个", owner, repo, result.size());
        return result;
    }

    /**
     * 查询仓库最近的工作流运行记录。
     *
     * <p>仅 GitHub Actions 支持；Gitee Go 因开放接口限制不支持该操作。</p>
     *
     * @return 工作流运行列表
     */
    public List<WorkflowRun> listRuns() {
        requirePlatform(CiPlatform.GITHUB, "listRuns");
        ClientResponse response = newRequest()
                .path("/repos/" + owner + "/" + repo + "/actions/runs")
                .get();
        checkResponse(response, "查询 CI 运行列表");
        List<WorkflowRun> result = new ArrayList<>();
        JsonNode root = Json.parse(response.getBodyString());
        JsonNode runs = root.get("workflow_runs");
        if (runs != null && runs.isArray()) {
            for (int i = 0; i < runs.size(); i++) {
                result.add(toWorkflowRun(runs.get(i)));
            }
        }
        log.info("CI 运行列表查询完成: {}/{}, 共 {} 条", owner, repo, result.size());
        return result;
    }

    /**
     * 查询一次工作流运行的详情。
     *
     * <p>仅 GitHub Actions 支持；Gitee Go 因开放接口限制不支持该操作。</p>
     *
     * @param runId 运行唯一标识
     * @return 运行详情，不存在时返回 null
     */
    public WorkflowRun getRun(long runId) {
        requirePlatform(CiPlatform.GITHUB, "getRun");
        ClientResponse response = newRequest()
                .path("/repos/" + owner + "/" + repo + "/actions/runs/" + runId)
                .get();
        checkResponse(response, "查询 CI 运行详情");
        return toWorkflowRun(Json.parse(response.getBodyString()));
    }

    // ==================== 触发与取消 ====================

    /**
     * 触发一次 CI 运行。
     *
     * <p><b>GitHub Actions</b>：{@code workflowId} 为工作流文件路径（如
     * {@code .github/workflows/ci.yml}）或工作流数字 ID，向指定分支发起 dispatch 事件。</p>
     *
     * <p><b>Gitee Go</b>：{@code workflowId} 被忽略，按仓库维度触发流水线
     * （需在 Gitee Go 中开启「API 触发」）。</p>
     *
     * @param workflowId 工作流文件路径或 ID，Gitee 平台可传 null
     * @param ref        目标分支或 tag，如 "main"
     * @return 触发成功返回 true
     */
    public boolean trigger(String workflowId, String ref) {
        String path;
        if (platform == CiPlatform.GITEE) {
            path = "/repos/" + owner + "/" + repo + "/pipelines";
        } else {
            requirePlatform(CiPlatform.GITHUB, "trigger");
            if (workflowId == null || workflowId.isBlank()) {
                throw new GitClientException("触发 GitHub Actions 必须指定 workflowId(工作流文件路径或 ID)");
            }
            path = "/repos/" + owner + "/" + repo + "/actions/workflows/" + workflowId + "/dispatches";
        }
        JsonObject body = Json.createJsonObject();
        body.put("ref", ref);
        if (platform == CiPlatform.GITEE) {
            body.put("variables", new ArrayList<>());
        }
        ClientResponse response = newRequest()
                .path(path)
                .json()
                .body(Json.toJson(body))
                .post();
        checkResponse(response, "触发 CI 运行");
        log.info("CI 运行触发成功: {}/{}, ref={}", owner, repo, ref);
        return true;
    }

    /**
     * 取消一次进行中的运行。
     *
     * <p>仅 GitHub Actions 支持；Gitee Go 因开放接口限制不支持该操作。</p>
     *
     * @param runId 运行唯一标识
     * @return 取消成功返回 true
     */
    public boolean cancel(long runId) {
        requirePlatform(CiPlatform.GITHUB, "cancel");
        ClientResponse response = newRequest()
                .path("/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/cancel")
                .post();
        checkResponse(response, "取消 CI 运行");
        log.info("CI 运行取消成功: {}/{}, runId={}", owner, repo, runId);
        return true;
    }

    // ==================== 日志 ====================

    /**
     * 下载一次运行的日志原始字节。
     *
     * <p>仅 GitHub Actions 支持；Gitee Go 因开放接口限制不支持该操作。
     * GitHub 返回的日志为 zip 压缩包字节，可由调用方按需解压或保存。</p>
     *
     * @param runId 运行唯一标识
     * @return 日志原始字节，无内容时返回空数组
     */
    public byte[] logs(long runId) {
        requirePlatform(CiPlatform.GITHUB, "logs");
        ClientResponse response = newRequest()
                .path("/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/logs")
                .get();
        checkResponse(response, "下载 CI 运行日志");
        byte[] body = response.getBody();
        return body != null ? body : new byte[0];
    }

    // ==================== 内部方法 ====================

    /**
     * 校验当前平台是否支持指定操作，不支持时抛出异常。
     *
     * @param expected 期望的平台
     * @param action   操作名称
     */
    private void requirePlatform(CiPlatform expected, String action) {
        if (platform != expected) {
            throw new GitClientException(action + " 操作暂不支持 " + platform + " 平台");
        }
    }

    /**
     * 校验响应状态码，非 2xx 时抛出异常。
     *
     * @param response 响应对象
     * @param action   操作描述
     */
    private void checkResponse(ClientResponse response, String action) {
        if (!response.isSuccess()) {
            throw new GitClientException(action + "失败: HTTP " + response.getStatusCode()
                    + " - " + response.getBodyString());
        }
    }

    /**
     * 构建带平台鉴权的基础请求构建器。
     *
     * <p>GitHub 使用 {@code Authorization: Bearer token} 头鉴权；
     * Gitee 使用 {@code access_token} 查询参数鉴权。</p>
     *
     * @return 请求构建器
     */
    private HttpClientBuilder newRequest() {
        String baseUrl = platform == CiPlatform.GITEE ? GITEE_API_BASE : GITHUB_API_BASE;
        HttpClientBuilder builder = HttpClientFactory.of(baseUrl);
        if (token != null && !token.isBlank()) {
            if (platform == CiPlatform.GITEE) {
                builder.query("access_token", token);
            } else {
                builder.auth(token);
            }
        }
        return builder;
    }

    /**
     * 将 JSON 节点转换为工作流信息记录。
     *
     * @param node 工作流 JSON 节点
     * @return 工作流信息记录
     */
    private WorkflowInfo toWorkflowInfo(JsonNode node) {
        return new WorkflowInfo(
                node.get("id").toLongValue(),
                node.get("name").toStringValue(""),
                node.get("path").toStringValue(""),
                node.get("state").toStringValue(""),
                node.get("html_url").toStringValue("")
        );
    }

    /**
     * 将 JSON 节点转换为运行记录。
     *
     * @param node 运行 JSON 节点
     * @return 运行记录
     */
    private WorkflowRun toWorkflowRun(JsonNode node) {
        return new WorkflowRun(
                node.get("id").toLongValue(),
                node.get("name").toStringValue(""),
                node.get("status").toStringValue(""),
                node.get("conclusion").toStringValue(""),
                node.get("head_branch").toStringValue(""),
                node.get("head_sha").toStringValue(""),
                node.get("run_number").toLongValue(),
                node.get("created_at").toStringValue(""),
                node.get("updated_at").toStringValue(""),
                node.get("html_url").toStringValue("")
        );
    }

    /**
     * 从远程地址解析所属者与仓库名。
     *
     * <p>支持形如 {@code https://gitee.com/user/repo.git} 的地址，返回
     * {@code ["user", "repo"]}；解析失败返回 null。</p>
     *
     * @param remoteUrl 远程仓库地址，可为 null
     * @return 长度为 2 的数组（所属者、仓库名），失败返回 null
     */
    private String[] resolveOwnerAndRepo(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String url = remoteUrl.trim();
        int schemeIndex = url.indexOf("://");
        if (schemeIndex >= 0) {
            url = url.substring(schemeIndex + 3);
        }
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        int slashIndex = url.indexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        String path = url.substring(slashIndex + 1);
        String[] parts = path.split("/");
        if (parts.length < 2) {
            return null;
        }
        return new String[] {parts[parts.length - 2], parts[parts.length - 1]};
    }

    /**
     * 根据远程地址自动识别 CI 平台。
     *
     * @param remoteUrl 远程仓库地址，可为 null
     * @return 平台类型，默认 GitHub
     */
    private CiPlatform resolvePlatform(String remoteUrl) {
        if (remoteUrl != null) {
            String url = remoteUrl.toLowerCase();
            if (url.contains("gitee.com")) {
                return CiPlatform.GITEE;
            }
        }
        return CiPlatform.GITHUB;
    }
}
