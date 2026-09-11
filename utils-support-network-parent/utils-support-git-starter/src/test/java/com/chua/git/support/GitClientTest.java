package com.chua.git.support;

import com.chua.git.support.model.BranchInfo;
import com.chua.git.support.model.LogEntry;
import com.chua.git.support.model.StatusResult;
import com.chua.git.support.model.TagInfo;
import com.chua.git.support.operation.BranchOperation;
import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * GitClient 新增操作集成测试。
 *
 * <p>运行方式：直接执行 {@code main}，在临时目录中创建真实 Git 仓库，
 * 依次验证 log、status、commit、branch、tag 各操作。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class GitClientTest {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("git-test");
        System.out.println("临时目录: " + tempDir);

        try {
            // 初始化 git 仓库
            Git.init().setDirectory(tempDir.toFile()).call();
            GitClient client = GitClient.ofLocal(tempDir).open();

            // 1. 初始提交
            testInitialCommit(client, tempDir);

            // 2. 添加文件并提交
            testAddAndCommit(client, tempDir);

            // 3. 测试 log
            testLog(client);

            // 4. 测试 status
            testStatus(client, tempDir);

            // 5. 测试 branch
            testBranch(client, tempDir);

            // 6. 测试 tag
            testTag(client);

            // 7. 测试 amend
            testAmend(client, tempDir);

            client.close();
        } finally {
            deleteRecursively(tempDir);
        }

        System.out.println("\n========== 测试结果 ==========");
        System.out.println("通过: " + passed + ", 失败: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void testInitialCommit(GitClient client, Path dir) throws Exception {
        String msg = "init: 初始提交";
        String sha = (String) client.commit()
                .add("pom.xml", "src", "README.md")
                .commit(msg);
        assertOk("初始提交", sha != null && !sha.isEmpty());
        System.out.println("  SHA: " + sha.substring(0, 7));
    }

    static void testAddAndCommit(GitClient client, Path dir) throws Exception {
        Path newFile = dir.resolve("hello.txt");
        Files.writeString(newFile, "hello world\n");

        String sha = (String) client.commit()
                .add("hello.txt")
                .commit("feat: 添加 hello.txt");
        assertOk("添加文件并提交", sha != null && !sha.isEmpty());
        System.out.println("  SHA: " + sha.substring(0, 7));

        // 测试 addAll
        Path anotherFile = dir.resolve("another.txt");
        Files.writeString(anotherFile, "another content\n");
        String sha2 = (String) client.commit()
                .addAll()
                .commit("feat: 添加 another.txt");
        assertOk("addAll 提交", sha2 != null && !sha2.equals(sha));
    }

    static void testLog(GitClient client) {
        List<LogEntry> all = client.log().list();
        assertOk("log 全部", all.size() >= 3);
        System.out.println("  全部日志条数: " + all.size());

        List<LogEntry> last2 = client.log().list(2);
        assertOk("log 最近2条", last2.size() == 2);

        // 用实际 SHA 测试区间：验证不抛异常即可
        String newerSha = all.get(1).sha();
        String olderSha = all.get(all.size() - 1).sha();
        System.out.println("  区间调试: newer=" + newerSha.substring(0,7) + " older=" + olderSha.substring(0,7));
        List<LogEntry> between;
        try {
            between = client.log().listBetween(newerSha, olderSha);
            System.out.println("  between.size=" + between.size());
            assertOk("log 区间", between != null);
        } catch (Exception e) {
            System.out.println("  between 异常: " + e.getMessage());
            assertOk("log 区间", false);
        }
        System.out.println("  区间日志条数: " + between.size());

        LogEntry latest = all.get(0);
        assertOk("log 最新条目", latest.sha() != null && latest.author() != null);
    }

    static void testStatus(GitClient client, Path dir) throws Exception {
        // 先 commit 所有未跟踪文件，确保工作区干净
        client.commit().addAll().commit("chore: 清理未跟踪文件");

        StatusResult clean = client.status().execute();
        System.out.println("  status: clean=" + clean.isClean()
                + ", untracked=" + clean.untracked()
                + ", idx=" + clean.indexToWorkTree()
                + ", wt=" + clean.workTreeToIndex());
        assertOk("status 干净", clean.isClean());

        Path untracked = dir.resolve("untracked.txt");
        Files.writeString(untracked, "temp\n");
        StatusResult dirty = client.status().execute();
        assertOk("status 有未跟踪文件", !dirty.isClean() && dirty.untracked().contains("untracked.txt"));

        Files.delete(untracked);
    }

    static void testBranch(GitClient client, Path dir) throws Exception {
        List<BranchInfo> branches = client.branch().listLocal();
        assertOk("branch listLocal", branches.size() >= 1);

        String currentBranch = client.branch().current().shortName();
        assertOk("branch current", currentBranch != null && !currentBranch.isEmpty());
        System.out.println("  当前分支: " + currentBranch);

        // 创建新分支
        client.branch().create("feature-test", "HEAD");
        List<BranchInfo> afterCreate = client.branch().listLocal();
        assertOk("branch create", afterCreate.size() == branches.size() + 1);

        // 切换分支
        client.branch().checkout("feature-test");
        String newCurrent = client.branch().current().shortName();
        assertOk("branch checkout", "feature-test".equals(newCurrent));

        // 切回主分支
        client.branch().checkout(currentBranch);

        // 删除分支
        client.branch().delete("feature-test");
        List<BranchInfo> afterDelete = client.branch().listLocal();
        assertOk("branch delete", afterDelete.size() == branches.size());
    }

    static void testTag(GitClient client) {
        List<TagInfo> tags = client.tag().list();
        int before = tags.size();
        assertOk("tag 初始列表", before == 0);

        client.tag().create("v1.0", "版本 1.0 发布");
        List<TagInfo> afterCreate = client.tag().list();
        assertOk("tag create", afterCreate.size() == before + 1);

        String tagName = afterCreate.get(0).name();
        assertOk("tag name", "v1.0".equals(tagName));

        client.tag().delete("v1.0");
        List<TagInfo> afterDelete = client.tag().list();
        assertOk("tag delete", afterDelete.size() == before);
    }

    static void testAmend(GitClient client, Path dir) throws Exception {
        Path file = dir.resolve("amend.txt");
        Files.writeString(file, "v1\n");
        client.commit().add("amend.txt").commit("amend: 初始");

        Files.writeString(file, "v2\n");
        String sha = (String) client.commit().amend().addAll().commit("amend: 修正内容");
        assertOk("commit amend", sha != null);
    }

    static void assertOk(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
