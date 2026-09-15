package com.chua.git.support;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.git.support.model.WorkflowInfo;
import com.chua.git.support.model.WorkflowRun;
import com.chua.git.support.operation.ActionsOperation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
* CI 流水线操作（GitHub Actions）测试。
*
* <p>运行方式：直接执行 {@code main}，分三部分验证：</p>
* <ol>
*   <li>owner/repo 与平台自动解析（本地，无网络）</li>
*   <li>工作流/运行 JSON 响应解析（本地构造样本，无网络）</li>
*   <li>真实 GitHub 公开 API 冒烟测试（仓库 nodejs/node，无需 token）</li>
* </ol>
*
* @author CH
* @since 4.0.0.42
 */
public class ActionsOperationTest {

    static int passed = 0; // 通过
    static int failed = 0; // 失败

    /**
    * main。
    * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        // 1. owner/repo 与平台解析
        testResolve();

        // 2. JSON 响应解析
        testParse();

        // 3. 真实 GitHub 公开 API
        testPublicApi();

        System.out.println("\n========== ActionsOperation 测试结果 ==========");
        System.out.println("通过: " + passed + ", 失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
    * 测试远程地址解析与平台识别。
    *
    * <p>通过反射调用私有方法 {@code resolveOwnerAndRepo} 与 {@code resolvePlatform}，
    * 并校验构造器对 owner/repo/platform 字段的自动推断。</p>
     */
    static void testResolve() throws Exception {
        Path localPath = Path.of("target", "actions-test-resolve");
        GitClient client = GitClient.builder()
                .localPath(localPath)
                .remoteUrl("https://github.com/octocat/Hello-World.git")
                .build();
        ActionsOperation op = new ActionsOperation(client);

        // 构造器自动推断
        Field ownerField = ActionsOperation.class.getDeclaredField("owner");
        ownerField.setAccessible(true);
        Field repoField = ActionsOperation.class.getDeclaredField("repo");
        repoField.setAccessible(true);
        Field platformField = ActionsOperation.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        assertOk("构造自动推断 owner", "octocat".equals(ownerField.get(op)));
        assertOk("构造自动推断 repo", "Hello-World".equals(repoField.get(op)));
        assertOk("构造自动推断平台", ActionsOperation.CiPlatform.GITHUB == platformField.get(op));

        // resolveOwnerAndRepo
        Method resolveOwnerAndRepo = ActionsOperation.class.getDeclaredMethod("resolveOwnerAndRepo", String.class);
        resolveOwnerAndRepo.setAccessible(true);
        String[] gitHub = (String[]) resolveOwnerAndRepo.invoke(op, "https://github.com/octocat/Hello-World.git");
        assertOk("解析 https + .git", gitHub != null && "octocat".equals(gitHub[0]) && "Hello-World".equals(gitHub[1]));

        String[] gitee = (String[]) resolveOwnerAndRepo.invoke(op, "https://gitee.com/user/repo.git");
        assertOk("解析 gitee 地址", gitee != null && "user".equals(gitee[0]) && "repo".equals(gitee[1]));

        String[] noSuffix = (String[]) resolveOwnerAndRepo.invoke(op, "https://github.com/org/project");
        assertOk("解析无 .git 后缀", noSuffix != null && "org".equals(noSuffix[0]) && "project".equals(noSuffix[1]));

        String[] empty = (String[]) resolveOwnerAndRepo.invoke(op, (Object) null);
        assertOk("解析空地址", empty == null);

        String[] noOwner = (String[]) resolveOwnerAndRepo.invoke(op, "https://github.com/repo");
        assertOk("解析缺所属者", noOwner == null);

        // resolvePlatform
        Method resolvePlatform = ActionsOperation.class.getDeclaredMethod("resolvePlatform", String.class);
        resolvePlatform.setAccessible(true);
        assertOk("识别 gitee 平台", ActionsOperation.CiPlatform.GITEE == resolvePlatform.invoke(op, "https://gitee.com/user/repo.git"));
        assertOk("识别 github 平台", ActionsOperation.CiPlatform.GITHUB == resolvePlatform.invoke(op, "https://github.com/user/repo.git"));
        assertOk("默认 github 平台", ActionsOperation.CiPlatform.GITHUB == resolvePlatform.invoke(op, (Object) null));
    }

    /**
    * 测试工作流与运行记录的 JSON 响应解析。
    *
    * <p>通过反射调用私有方法 {@code toWorkflowInfo} 与 {@code toWorkflowRun}，
    * 使用本地构造的样本 JSON 验证字段映射。</p>
     */
    static void testParse() throws Exception {
        Path localPath = Path.of("target", "actions-test-parse");
        GitClient client = GitClient.builder()
                .localPath(localPath)
                .remoteUrl("https://github.com/octocat/Hello-World.git")
                .build();
        ActionsOperation op = new ActionsOperation(client);

        // 工作流解析
        Method toWorkflowInfo = ActionsOperation.class.getDeclaredMethod("toWorkflowInfo", JsonNode.class);
        toWorkflowInfo.setAccessible(true);
        JsonNode wfNode = Json.parse("{\"id\":1,\"name\":\"CI\",\"path\":\".github/workflows/ci.yml\","
                + "\"state\":\"active\",\"html_url\":\"https://github.com/octocat/Hello-World/actions/workflows/1\"}");
        WorkflowInfo wf = (WorkflowInfo) toWorkflowInfo.invoke(op, wfNode);
        assertOk("解析工作流 id", wf.id() == 1L);
        assertOk("解析工作流 name", "CI".equals(wf.name()));
        assertOk("解析工作流 path", ".github/workflows/ci.yml".equals(wf.path()));
        assertOk("解析工作流 state", "active".equals(wf.state()));
        assertOk("解析工作流 htmlUrl", wf.htmlUrl().contains("/actions/workflows/1"));

        // 运行记录解析
        Method toWorkflowRun = ActionsOperation.class.getDeclaredMethod("toWorkflowRun", JsonNode.class);
        toWorkflowRun.setAccessible(true);
        JsonNode runNode = Json.parse("{\"id\":123,\"name\":\"CI\",\"status\":\"completed\","
                + "\"conclusion\":\"success\",\"head_branch\":\"main\",\"head_sha\":\"abc123\","
                + "\"run_number\":5,\"created_at\":\"2024-01-01T00:00:00Z\","
                + "\"updated_at\":\"2024-01-01T01:00:00Z\","
                + "\"html_url\":\"https://github.com/octocat/Hello-World/actions/runs/123\"}");
        WorkflowRun run = (WorkflowRun) toWorkflowRun.invoke(op, runNode);
        assertOk("解析运行 id", run.id() == 123L);
        assertOk("解析运行 name", "CI".equals(run.name()));
        assertOk("解析运行 status", "completed".equals(run.status()));
        assertOk("解析运行 conclusion", "success".equals(run.conclusion()));
        assertOk("解析运行 branch", "main".equals(run.branch()));
        assertOk("解析运行 sha", "abc123".equals(run.headSha()));
        assertOk("解析运行序号", run.runNumber() == 5L);
        assertOk("解析运行 htmlUrl", run.htmlUrl().contains("/actions/runs/123"));
    }

    /**
    * 测试真实 GitHub 公开 API（无需 token）。
    *
    * <p>对 nodejs/node 仓库查询运行列表、运行详情与工作流列表，验证 HTTP 链路与响应解析。
    * 公开仓库未鉴权访问受 GitHub 限流（每小时 60 次）约束。</p>
    * <p>若本机 JVM 因 TLS 证书链校验失败（如中间人代理剥落证书链）无法访问 GitHub，
    * 该部分标记为跳过（SKIP），不计入失败，避免环境问题误报代码缺陷。</p>
     */
    static void testPublicApi() throws Exception {
        Path tempDir = Files.createTempDirectory("actions-api-test");
        try {
            GitClient client = GitClient.builder()
                    .localPath(tempDir)
                    .remoteUrl("https://github.com/nodejs/node.git")
                    .build();
            ActionsOperation op = new ActionsOperation(client);

            List<WorkflowRun> runs = op.listRuns();
            assertOk("公开仓库 listRuns", runs != null && !runs.isEmpty());
            if (runs != null && !runs.isEmpty()) {
                WorkflowRun first = runs.get(0);
                assertOk("公开仓库运行字段", first.id() > 0 && first.status() != null);
                WorkflowRun detail = op.getRun(first.id());
                assertOk("公开仓库 getRun", detail != null && detail.id() == first.id());
            }

            List<WorkflowInfo> workflows = op.listWorkflows();
            assertOk("公开仓库 listWorkflows", workflows != null);
        } catch (Exception e) {
            if (isTlsEnvIssue(e)) {
                System.out.println("  [SKIP] 公开仓库 API 调用（本机 TLS 证书校验失败，跳过）");
                System.out.println("  原因: " + e.getMessage());
                return;
            }
            assertOk("公开仓库 API 调用", false);
            System.out.println("  API 调用异常: " + e);
            e.printStackTrace();
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
    * 判断异常是否为 TLS 证书环境问题。
    *
    * <p>遍历异常原因链，若存在 {@code SSLHandshakeException} 或消息包含
    * {@code PKIX} / {@code certificate_unknown}，则视为本机 TLS 证书校验失败，
    * 属于环境问题而非代码缺陷。</p>
    *
    * @param t 待判断异常
    * @return 是否 TLS 证书环境问题
     */
    static boolean isTlsEnvIssue(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof javax.net.ssl.SSLHandshakeException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("PKIX") || msg.contains("certificate_unknown"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
    * 断言ok。
    * @param name 名称
    * @param condition 条件
     */
    static void assertOk(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    /**
    * 删除recursively。
    * @param dir dir
     */
    static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}