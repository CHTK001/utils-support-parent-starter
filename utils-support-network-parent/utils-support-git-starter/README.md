# utils-support-git-starter

> 基于 **Eclipse JGit 7.x** 的链式 Git 客户端 SDK：克隆、拉取、推送、分支、标签、日志、状态、提交、HEAD 概况、定时文件监听、CI 流水线 REST 调用与「拉取 → 编译 → 部署」流水线编排，一个入口全部覆盖。
>
> 版本：`4.0.0.51`｜JDK：`25`（仓库 `java.version=25`）｜包名根：`com.chua.git.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 常用能力速查](#13-常用能力速查)
- [2. 包结构](#2-包结构)
- [3. 核心 API](#3-核心-api)
  - [3.1 GitClient：唯一对外入口](#31-gitclient唯一对外入口)
  - [3.2 clone：克隆远程仓库](#32-clone克隆远程仓库)
  - [3.3 pull：拉取与文件变更监听](#33-pull拉取与文件变更监听)
  - [3.4 remote：只检查远端更新，不动工作区](#34-remote只检查远端更新不动工作区)
  - [3.5 branch：分支查询与切换](#35-branch分支查询与切换)
  - [3.6 log / status / commit / tag](#36-log--status--commit--tag)
  - [3.7 push：推送](#37-push推送)
  - [3.8 deploy：拉取 → 编译 → 部署流水线](#38-deploy拉取--编译--部署流水线)
  - [3.9 actions：CI 流水线（GitHub Actions / Gitee Go）](#39-actions-ci-流水线github-actions--gitee-go)
  - [3.10 结果模型](#310-结果模型)
  - [3.11 监听器与异常](#311-监听器与异常)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 扩展点](#6-spi-扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-git-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 纯 Java 库：把 JGit `Git` / `Repository` 封装成「`GitClient` + 各 Operation 链式构建器」 | 不是 Spring Boot starter（pom 无任何 Spring 依赖，无自动配置） |
| 能力 | clone / pull / fetch 比对 / push / branch / tag / log / status / add+commit / HEAD 概况 / 定时文件监听 / CI REST 调用 / 部署流水线编排 | 不是 Git 服务端（无 http.server、无 daemon），不含 Maven 构建的真实执行实现 |
| 异步 | 同步与异步（`CompletableFuture`）同一套链式 API | 异步返回的不是 Reactor `Mono` |
| 依赖 | 只依赖 `utils-support-common-starter`、`org.eclipse.jgit`（7.8.0.202609011348-r）、`org.eclipse.jgit.ssh.jsch`、slf4j、lombok | 不依赖 Spring、不依赖数据库驱动 |
| SPI | 对外暴露 `Deployer`（部署器）扩展点 | 本模块自身未注册任何 SPI 实现（resources 下仅有 `GitClient` 的 native-image `reflect-config.json`） |

**设计原则**（来自 `GitClient` 类注释）：

1. **懒加载**：`open()` 首次调用才打开仓库；
2. **线程安全**：`open()` 使用双检锁（DCL）保证只打开一次；
3. **操作子模式**：`pull()` / `push()` / `branch()` 等返回独立 Operation 对象，链式配置后一次执行；
4. **AutoCloseable**：支持 try-with-resources 自动关闭。

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 克隆远程仓库 | `GitClient.builder().remoteUrl(..).localPath(..).build().cloneOp().execute()` | 返回指向新仓库的新 `GitClient` |
| 拉取并合并 | `client.pull().execute()` | 返回 `PullResult`；`.async()` 异步 |
| 定时监听文件变更 | `client.pull().interval(30, TimeUnit.SECONDS).listener(fn).start()` | 后台守护线程定时 pull + diff 回调，`stop()` 停止 |
| 只查远端有没有新提交 | `client.remote().fetch("main")` | 仅 fetch + 比对，不合并、不改工作区 |
| 分支查询 / 切换 | `client.branch().listAll()` / `.checkout("dev")` | 本地、远程、创建、删除、远端 URL |
| 工作区状态 | `client.status().execute()` | `StatusResult.isClean()`、未跟踪文件等 |
| 提交日志 | `client.log().list(10)` | `LogEntry` 列表，从新到旧 |
| 暂存并提交 | `client.commit().addAll().commit("feat: x")` | 返回提交 SHA；支持 `author` / `amend` / `async` |
| 推送 | `client.push().branch("main").execute()` | 支持 `.force()`、`.tags()`、`.async()` |
| HEAD 概况 | `client.head()` | 当前分支、本地/远端提交、是否落后 |
| 标签 | `client.tag().list()` / `.create("v1.0", "msg")` / `.delete("v0.9")` | 轻量与附注标签 |
| CI 流水线 | `new ActionsOperation(client).listWorkflows()` / `.trigger(..)` | GitHub Actions 与 Gitee Go（能力有差异，见 3.9） |
| 部署流水线 | `client.deploy().goals("clean","package").execute()` | pull → `Deployer` SPI 编排，见 3.8 |

---

## 2. 包结构

共 30 个 Java 源文件。

| 包 | 数量 | 职责 | 代表类型 |
| --- | --- | --- | --- |
| `com.chua.git.support` | 1 | 唯一对外入口 `GitClient`（内含 `Builder`） | `GitClient` |
| `com.chua.git.support.operation` | 13 | 各具体操作的链式构建器、`Deployer` SPI、进度适配器 | `FetchOperation`、`PushOperation`、`Deployer` |
| `com.chua.git.support.model` | 12 | 不可变结果 record | `BranchInfo`、`LogEntry`、`StatusResult` |
| `com.chua.git.support.listener` | 3 | 文件变更 / 进度回调 | `GitFileListener`、`GitProgressListener` |
| `com.chua.git.support.exception` | 1 | 统一运行时异常 | `GitClientException` |

---

## 3. 核心 API

> 所有操作入口都返回「链式构建器」；`execute()` / `commit(...)` 等终结方法在同步模式返回结果对象、在 `async()` 模式返回 `CompletableFuture<结果>`，**签名统一为 `Object`，调用方需按模式强转**（源码即如此设计）。
> 所有 Git 操作失败统一抛 `GitClientException`（RuntimeException）。

### 3.1 GitClient：唯一对外入口

`com.chua.git.support.GitClient`，`implements AutoCloseable`。

| 方法 | 说明 |
| --- | --- |
| `static GitClient.builder()` | 链式构建器入口 |
| `static GitClient.ofLocal(Path localPath)` | 快速构造：仅指定本地路径，不关联远程 |
| `Builder.remoteUrl(String)` | 远程 HTTP(S) 地址（clone / push / CI 平台推断用） |
| `Builder.localPath(Path)` | 本地仓库目录；`build()` 时必填，缺失抛 `IllegalArgumentException` |
| `Builder.credentials(String username, String password)` | HTTPS 凭据（密码或 Personal Access Token） |
| `Builder.sshKey(String privateKeyPath, String passphrase)` | SSH 凭据；**当前源码仅保存字段，未接入传输层**（见第 8 节） |
| `Builder.build()` | 校验并实例化 |
| `GitClient open()` | 打开本地仓库（DCL 懒加载）；目录下无 `.git` 时抛 `GitClientException` |
| `void close()` | 关闭 JGit 资源，可再次 `open()` |
| `Git getGit()` / `Repository getRepository()` | 取底层 JGit 对象（未打开会先 `open()`） |
| `HeadInfo head()` | HEAD 概况：分支名、本地提交、远端同名分支提交（`refs/remotes/origin/<branch>`）、是否落后 |
| `CredentialsProvider getCredentialsProvider()` | 未配置凭据时返回 `null` |
| `String getRemoteUrl()` / `String getAccessToken()` / `Path getLocalPath()` | 访问器；`getAccessToken()` 即构建时的 `password` 字段 |

操作入口：`pull()`、`remote()`、`branch()`、`cloneOp()`、`push()`、`deploy()`、`log()`、`status()`、`commit()`、`tag()`，以及 `pull(GitFileListener)`、`pull(GitFileListener, GitProgressListener)` 两个便捷重载。

> **注意**：`GitClient` 没有名为 `actions()` 的方法。CI 操作类 `ActionsOperation` 需要自行 `new ActionsOperation(client)` 构造（其构造器是 public 的），见 3.9。

### 3.2 clone：克隆远程仓库

`CloneOperation`（`client.cloneOp()`）：

| 方法 | 说明 |
| --- | --- |
| `.async()` | `execute()` 改返回 `CompletableFuture<GitClient>` |
| `.progressListener(GitProgressListener)` | 克隆进度回调 |
| `.execute()` | 同步返回新 `GitClient`（指向克隆出的本地仓库）；异步返回 `CompletableFuture<GitClient>` |

- 克隆前必须已设置 `remoteUrl` 与 `localPath`，否则抛 `GitClientException("remoteUrl 不能为空")`。
- 已配置 HTTPS 凭据时自动附加 `CredentialsProvider`。

### 3.3 pull：拉取与文件变更监听

`FetchOperation`（`client.pull()`），对应 `git pull`（fetch + merge，会改工作区）：

| 方法 | 说明 |
| --- | --- |
| `.async()` | `execute()` 改返回 `CompletableFuture<PullResult>` |
| `.progressListener(GitProgressListener)` | 进度回调，透传给 JGit `ProgressMonitor` |
| `.interval(long, TimeUnit)` | `start()` 的轮询间隔，默认 `30 SECONDS` |
| `.listener(GitFileListener)` | 文件变更回调；设置后 pull 会对比前后 HEAD 树并逐文件通知 |
| `.execute()` | 同步返回 `PullResult`；异步返回 `CompletableFuture<PullResult>` |
| `.start()` | 启动定时拉取监听（单线程守护线程 `git-watch-<仓库目录>`，固定延迟调度）；未设置 listener 抛 `GitClientException` |
| `.stop()` | 停止定时拉取 |

`PullResult`：`updated`（HEAD 树是否变化）、`fetchedRefs`（当前版本始终为 0）、`messages`（如 `"Updated."` / `"Already up to date."`）。

文件变更事件 `GitFileEvent(changeType, filePath)`，`ChangeType` 取值 `ADD` / `MODIFY`（含 RENAME、COPY）/ `DELETE`。

另有 `client.pull(GitFileListener)` 与 `client.pull(GitFileListener, GitProgressListener)` 两个直接执行的重载。

### 3.4 remote：只检查远端更新，不动工作区

`RemoteOperation`（`client.remote()`），用于「有没有新提交」的轮询检查：

| 方法 | 说明 |
| --- | --- |
| `.progressListener(GitProgressListener)` | fetch 下载对象时的进度回调 |
| `.fetch(String branch)` | fetch `origin` 的指定分支（传空则用当前分支），比对本地 HEAD 与 `refs/remotes/origin/<branch>`；返回 `FetchResult`。refspec 固定为 `+refs/heads/<b>:refs/remotes/origin/<b>`，且 `TagOpt.NO_TAGS`（不拉标签） |
| `.currentBranch()` | 当前分支名（detached 时返回提交短码） |

`FetchResult(branch, localCommit, remoteCommit, updated, message)`；远端无该分支时返回 `FetchResult.missing(branch)`（`updated=false`）。

> 与 `pull()` 的区别：pull 会合并工作区（可能冲突），轮询检查用 fetch 只读比对。

### 3.5 branch：分支查询与切换

`BranchOperation`（`client.branch()`）。查询方法返回数据；操作方法执行后返回 `this` 可继续链式：

| 方法 | 说明 |
| --- | --- |
| `listAll()` | 所有分支（本地 + 远程），`List<BranchInfo>` |
| `listLocal()` / `listRemote()` | 仅本地 / 仅远程 |
| `current()` | 当前 HEAD 分支；detached HEAD 抛 `GitClientException` |
| `checkout(String branch)` | 切换分支（`git checkout`） |
| `checkoutCreate(String branch)` | 创建并切换（`git checkout -b`） |
| `create(String branch, String startPoint)` | 基于起点创建分支，不切换 |
| `delete(String branch)` | 删除本地分支（`-d`） |
| `deleteForce(String branch)` | 强制删除（`-D`，允许删未合并分支） |
| `remoteUrls()` | `.git/config` 中所有 `[remote]` 的 url |

`BranchInfo(name, shortName, objectId, isRemote)`：`name` 为完整引用名（如 `refs/remotes/origin/main`），`shortName` 为短名（如 `main`），`objectId` 为指向提交的完整哈希。

### 3.6 log / status / commit / tag

**LogOperation**（`client.log()`）：

| 方法 | 说明 |
| --- | --- |
| `list()` | 全部提交（从新到旧） |
| `list(int count)` | 最近 N 条 |
| `listBetween(String fromRef, String toRef)` | 两个引用之间的提交（不含 `toRef`） |
| `limit(int count)` | 链式设置条数上限 |

`LogEntry(sha, shortSha, message, author, authorEmail, commitTime)`：`shortSha` 为 7 位前缀，`commitTime` 为 Unix 毫秒。

**StatusOperation**（`client.status()`）：`execute()` 返回 `StatusResult(indexToWorkTree, workTreeToIndex, untracked, ignored)`。两个映射的键为 `added` / `removed` / `modified` / `missing`（值为文件路径列表，仅保留非空分类）；`isClean()` 表示无任何 staged / unstaged / untracked 变更。

**CommitOperation**（`client.commit()`）：

| 方法 | 说明 |
| --- | --- |
| `.add(String... paths)` | 暂存指定路径（相对仓库根目录） |
| `.addAll()` | 暂存所有变更（`git add -A`） |
| `.remove(String... paths)` | 从暂存区移除（`git rm --cached`） |
| `.message(String)` / `.author(String name, String email)` / `.amend()` | 提交消息 / 自定义作者 / 修改上一次提交 |
| `.async()` | 终结方法改返回 `CompletableFuture<String>` |
| `commit(String msg)` | 提交，返回提交 SHA（异步返回 `CompletableFuture<String>`） |
| `full(String msg)` | 组合操作：`addAll()` + 提交 |

> 未暂存且非 amend 时按不允许空提交处理（`setAllowEmpty(false)`）。

**TagOperation**（`client.tag()`）：`list()` 返回 `List<TagInfo>`；`create(String name)` 创建轻量标签；`create(String name, String message)` 创建附注标签；`delete(String name)` 删除本地标签。`TagInfo(name, sha, message, annotated)`（`list()` 结果中 `annotated=false`、`message=""`）。

### 3.7 push：推送

`PushOperation`（`client.push()`）：

| 方法 | 说明 |
| --- | --- |
| `.remote(String)` | 远端名，默认 `origin` |
| `.branch(String)` | 指定推送分支（构造 refspec `refs/heads/<b>:refs/heads/<b>`） |
| `.force()` | 强制推送（refspec 前缀 `+`；会覆盖远程历史，慎用） |
| `.tags()` | 推送标签（`git push --tags`） |
| `.prompt(String)` | 仅记录到日志，便于追踪（JGit push 本身不传输提交消息） |
| `.progressListener(GitProgressListener)` | 进度回调 |
| `.async()` / `.execute()` | 同步返回 `PushResult`；异步返回 `CompletableFuture<PushResult>` |

`PushResult(success, refUpdates, messages)`：`refUpdates=0` 表示本地与远端已同步（`"Everything up-to-date."`）。

### 3.8 deploy：拉取 → 编译 → 部署流水线

`DeployOperation`（`client.deploy()`），执行顺序（来自源码）：`open()` → 查找 `Deployer`（优先链式指定的 `deployer(...)`，否则经 `ServiceProvider.of(Deployer.class).list()` 找第一个 `supports(config)` 命中的实现）→ 未找到则返回 `DeployResult.failure(...)` → `git pull` → `deployer.deploy(client, config)`。

| 方法 | 默认 | 说明 |
| --- | --- | --- |
| `.projectPath(String)` | `pom.xml` | 相对仓库根目录的 pom 路径 |
| `.goals(String...)` | `clean, compile, package` | Maven 目标 |
| `.profiles(String...)` | 空 | Maven profile 列表 |
| `.skipTests(boolean)` | `true` | 是否跳过测试 |
| `.jdkVersion(String)` | 空 | JDK 版本（如 `"25"`） |
| `.deployTarget(String)` | 空 | 部署目标路径（本地或远程目录） |
| `.fileListener(GitFileListener)` | 空 | pull 阶段文件变更回调 |
| `.gitProgressListener(GitProgressListener)` | 空 | pull 阶段进度回调 |
| `.deployer(Deployer)` | 空 | 显式指定部署器，覆盖 SPI 自动发现 |
| `.async()` / `.execute()` | — | 同步返回 `DeployResult`；异步返回 `CompletableFuture<DeployResult>` |

`DeployResult(success, message, artifacts, durationMs)`：`artifacts` 为编译产物路径列表（可为空），`durationMs` 为整条流水线耗时。

> 本模块**不提供任何 `Deployer` 实现**；没有可用实现时 `execute()` 不会抛异常，而是返回 `DeployResult.failure("未找到能处理此部署配置的 Deployer 实现。请确保 maven-starter 在 classpath 中，或手动调用 deployer()。")`（该提示文案来自源码；当前工作区内未检索到 `Deployer` 实现类）。

### 3.9 actions：CI 流水线（GitHub Actions / Gitee Go）

`ActionsOperation`（`com.chua.git.support.operation`）通过平台 REST API 操作 CI，**不依赖本地仓库**。构造时会从 `GitClient` 的远程地址自动推断 `owner` / `repo` / 平台（URL 含 `gitee.com` 判为 GITEE，否则 GITHUB），并继承 `getAccessToken()` 作为令牌；可用链式方法覆盖：`.owner(String)`、`.repo(String)`、`.token(String)`、`.platform(CiPlatform)`（枚举 `GITHUB` / `GITEE`）。

| 方法 | GitHub Actions | Gitee Go |
| --- | --- | --- |
| `listWorkflows()` | 支持 | 不支持（抛 `GitClientException`） |
| `listRuns()` | 支持 | 不支持 |
| `getRun(long runId)` | 支持 | 不支持 |
| `trigger(String workflowId, String ref)` | 支持；`workflowId` 为工作流文件路径（如 `.github/workflows/ci.yml`）或数字 ID，向 `ref` 发 dispatch | 仅支持触发；`workflowId` 被忽略，按仓库维度触发（需在 Gitee Go 开启「API 触发」） |
| `cancel(long runId)` | 支持 | 不支持 |
| `logs(long runId)` | 支持；返回日志原始字节（GitHub 为 zip 包） | 不支持 |

结果模型：`WorkflowInfo(id, name, path, state, htmlUrl)`、`WorkflowRun(id, name, status, conclusion, branch, headSha, runNumber, createdAt, updatedAt, htmlUrl)`。

鉴权方式：GitHub 走 `Authorization: Bearer <token>`；Gitee 走 `access_token` 查询参数。API 基址：GitHub `https://api.github.com`，Gitee `https://gitee.com/api/v5`。

```java
// GitClient 未提供 actions() 便捷入口，需自行构造：
ActionsOperation actions = new ActionsOperation(client);
List<WorkflowInfo> workflows = actions.listWorkflows();          // 仅 GitHub
actions.trigger(".github/workflows/ci.yml", "main");
byte[] logs = actions.logs(12345678L);                           // zip 字节
```

### 3.10 结果模型

全部为 `java.lang.record`，位于 `com.chua.git.support.model`，字段不可变（集合组件深拷贝）：

| record | 字段 |
| --- | --- |
| `HeadInfo` | `branch`、`commit`、`shortCommit`（8 位）、`remoteCommit`、`behind` |
| `PullResult` | `updated`、`fetchedRefs`（当前版本恒 0）、`messages`；`toString()` 输出 `Updated.` / `No update.` |
| `FetchResult` | `branch`、`localCommit`、`remoteCommit`、`updated`、`message` |
| `PushResult` | `success`、`refUpdates`、`messages` |
| `BranchInfo` | `name`、`shortName`、`objectId`、`isRemote` |
| `LogEntry` | `sha`、`shortSha`（7 位）、`message`、`author`、`authorEmail`、`commitTime`（Unix 毫秒） |
| `StatusResult` | `indexToWorkTree`、`workTreeToIndex`、`untracked`、`ignored` + `isClean()` |
| `TagInfo` | `name`、`sha`、`message`、`annotated` |
| `DeployConfig` | `projectPath`、`goals`、`profiles`、`skipTests`、`jdkVersion`、`deployTargetPath`；静态 `ofDefault(projectPath)` |
| `DeployResult` | `success`、`message`、`artifacts`、`durationMs`；静态 `success(...)` / `failure(...)` |
| `WorkflowInfo` | `id`、`name`、`path`、`state`、`htmlUrl` |
| `WorkflowRun` | `id`、`name`、`status`、`conclusion`、`branch`、`headSha`、`runNumber`、`createdAt`、`updatedAt`、`htmlUrl` |

### 3.11 监听器与异常

| 类型 | 说明 |
| --- | --- |
| `GitFileListener` | `void onChanged(GitFileEvent event)`；注意重复 pull 可能对同一变更再次回调，去重由监听方自理 |
| `GitFileEvent` | `changeType()`（`ADD`/`MODIFY`/`DELETE`）+ `filePath()`（相对仓库根目录） |
| `GitProgressListener` | 对齐 JGit `ProgressMonitor` 生命周期：`onBegin(totalTasks)` → `onTaskBegin(taskName, totalWork)` → `onUpdate(work)` → `onTaskEnd()` → … → `onEnd()`；`default boolean isCancelled()` 返回 `true` 可中止操作 |
| `GitClientException` | 继承 `RuntimeException`；`(String message)` 与 `(String message, Throwable cause)` 两个构造器，业务层统一捕获即可 |

内部适配器 `ProgressMonitorAdapter`（`operation` 包）负责把 JGit `ProgressMonitor` 回调一一映射到 `GitProgressListener`（jgit 7.x 新增的 `showDuration(boolean)` 被忽略）。

---

## 4. 快速开始

最短可运行示例（覆盖「克隆 → 打开 → 拉取 → 查状态/日志 → 提交 → 推送 → 关闭」）：

```java
import com.chua.git.support.GitClient;
import com.chua.git.support.model.HeadInfo;
import com.chua.git.support.model.LogEntry;
import com.chua.git.support.model.PullResult;
import com.chua.git.support.model.PushResult;
import com.chua.git.support.model.StatusResult;

import java.nio.file.Paths;
import java.util.List;

// 1. 构建客户端（localPath 必填）
GitClient client = GitClient.builder()
        .remoteUrl("https://gitee.com/user/repo.git")
        .localPath(Paths.get("/tmp/repo"))
        .credentials("username", "password")   // HTTPS 凭据，匿名仓库可省略
        .build();

// 2. 首次：克隆到 localPath（返回指向新仓库的新 GitClient）
GitClient repo = (GitClient) client.cloneOp().execute();

// 3. 打开本地仓库（目录下必须已有 .git；AutoCloseable，可用 try-with-resources）
repo.open();

// 4. 拉取（fetch + merge）
PullResult pulled = (PullResult) repo.pull().execute();
System.out.println(pulled.messages());

// 5. 状态 / 日志 / HEAD 概况
StatusResult status = repo.status().execute();
System.out.println("工作区干净: " + status.isClean());
List<LogEntry> logs = repo.log().list(10);
HeadInfo head = repo.head();
System.out.println(head.branch() + " 落后远端: " + head.behind());

// 6. 暂存并提交，再推送
repo.commit().addAll().commit("feat: 新功能");
PushResult pushed = (PushResult) repo.push().branch("main").execute();
System.out.println(pushed.messages());

// 7. 关闭
repo.close();
```

定时监听仓库文件变更（守护线程，不阻塞主流程）：

```java
FetchOperation watch = repo.pull()
        .interval(30, java.util.concurrent.TimeUnit.SECONDS)   // 默认即 30 秒
        .listener(event -> System.out.println(event.changeType() + ": " + event.filePath()));
watch.start();     // 后台定时 pull + diff 回调
// …
watch.stop();      // 停止
```

只检查远端是否有新提交（不动工作区）：

```java
FetchResult r = repo.remote().fetch("main");
if (r.updated()) {
    // 远端有新提交，触发后续动作
}
```

---

## 5. 配置项

**本模块没有 `application.*` / 系统属性 / 环境变量类配置项**——pom 未依赖 Spring，不是 Spring Boot starter；所有配置通过 `GitClient.Builder` 与各 Operation 的链式方法在代码里完成。

| 配置点 | 方法 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 远程地址 | `Builder.remoteUrl(String)` | 未设置 | clone / push / CI 平台与 owner/repo 推断都依赖它 |
| 本地路径 | `Builder.localPath(Path)` | 无（必填） | `build()` 时缺失抛 `IllegalArgumentException` |
| HTTPS 凭据 | `Builder.credentials(String, String)` | 未设置 | 用户名 + 密码/访问令牌 |
| SSH 凭据 | `Builder.sshKey(String, String)` | 未设置 | **当前源码仅保存字段，未接入 JGit 传输层**（全模块无 `SshSessionFactory` / `TransportConfigCallback` 使用） |
| 轮询间隔 | `FetchOperation.interval(long, TimeUnit)` | `30 SECONDS` | `start()` 的固定延迟 |
| 推送远端 | `PushOperation.remote(String)` | `origin` | |
| 部署 pom 路径 | `DeployOperation.projectPath(String)` | `pom.xml` | |
| 部署目标 | `DeployOperation.goals(String...)` | `clean, compile, package` | |
| 部署 profile | `DeployOperation.profiles(String...)` | 空列表 | |
| 跳过测试 | `DeployOperation.skipTests(boolean)` | `true` | |
| CI 平台/账号 | `ActionsOperation.platform/owner/repo/token` | 由 remoteUrl 与 `getAccessToken()` 推断 | |

并发说明：仅 `open()` 有双检锁保护；同一 `GitClient` 上的其它操作未做额外串行化，多线程共享同一实例时由调用方自行协调。

---

## 6. SPI 扩展点

本模块只暴露**一个** SPI 扩展点：`com.chua.git.support.operation.Deployer`（「拉取仓库 → 编译 → 部署」的统一契约）：

```java
public interface Deployer {
    String name();                                    // 显示名，如 "Maven"、"Docker"
    boolean supports(DeployConfig config);            // 能否处理该配置
    DeployResult deploy(GitClient gitClient, DeployConfig config);
}
```

- **发现机制**：`DeployOperation` 通过 `ServiceProvider.of(Deployer.class).list()` 加载，取第一个 `supports(config)` 为 `true` 的实现；链式 `.deployer(...)` 显式指定时跳过 SPI。
- **注册方式**（与 common-starter 的 SPI 约定一致）：在实现方 `src/main/resources/META-INF/extensions/` 下建名为接口全限定名的文件：

  ```text
  # src/main/resources/META-INF/extensions/com.chua.git.support.operation.Deployer
  maven=com.example.MyMavenDeployer
  ```

- **现状**：本模块自身未注册任何 `Deployer` 实现；在整个工作区源码中也未检索到实现类。也就是说，直接调用 `deploy()` 且不传 `.deployer(...)` 时，会得到 `DeployResult.failure(...)`（见 3.8）。
- `GitProgressListener` / `GitFileListener` 是**回调接口**（直接以参数传入，不注册）；`GitClient` 已登记 GraalVM native-image 的 `reflect-config.json`。

---

## 7. 构建与依赖

### 7.1 编译要求

- JDK 25（仓库 `java.version=25`，`maven.compiler.release=25`）。
- 源码 UTF-8、无 BOM；Lombok 作为注解处理器参与编译（`provided`）。

```bash
mvn -pl utils-support-network-parent/utils-support-git-starter -am clean package
```

### 7.2 依赖清单（来自 pom.xml）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | SPI（`ServiceProvider`）、集合 `Wraps`、JSON、HTTP 客户端、`ThreadUtils`、`StringUtils` 等底层能力 |
| `org.eclipse.jgit:org.eclipse.jgit` | `7.8.0.202609011348-r` | Git 核心实现 |
| `org.eclipse.jgit:org.eclipse.jgit.ssh.jsch` | `7.8.0.202609011348-r` | SSH 传输支持（JSch） |
| `org.projectlombok:lombok` | `${lombok.version}`，`provided` | 编译期注解处理（`@Slf4j`） |
| `org.slf4j:slf4j-api` | `${slf4j.version}` | 日志门面 |

### 7.3 相关文档

| 文档 | 路径 |
| --- | --- |
| 父模块 POM（utils-support-network-parent） | [../pom.xml](../pom.xml) |
| 仓库根 POM（utils-support-parent-starter） | [../../pom.xml](../../pom.xml) |
| 底层契约与工具库说明 | [../../utils-support-core-parent/utils-support-common-starter/README.md](../../utils-support-core-parent/utils-support-common-starter/README.md) |

---

## 8. 边界：这些能力不在本模块

使用前请先确认以下事项，避免在错误的地方找能力：

| 能力 | 现状 |
| --- | --- |
| `client.actions()` 便捷入口 | **不存在**。`GitClient` 未引用 `ActionsOperation`；请 `new ActionsOperation(client)` |
| SSH 私钥登录 | `Builder.sshKey(...)` 仅保存字段，源码未接入传输层；当前 clone/pull/push 实际只使用 HTTPS 用户名密码凭据 |
| Maven/构建的真实执行 | `Deployer` 只是 SPI 契约，本模块与当前工作区均无实现；`deploy()` 需外部提供实现或显式 `.deployer(...)` |
| Git 服务端 | 无。pom 未依赖 `org.eclipse.jgit.http.server` / daemon，不能对外提供 Git 服务 |
| merge / rebase / cherry-pick / stash / blame / 子模块 / worktree / LFS | 未封装。内部 diff 仅用于 pull 后的文件变更通知，未对外提供 diff 查询 API |
| 任意 refspec 的 fetch | `RemoteOperation.fetch(branch)` 仅按单分支 `+refs/heads/<b>:refs/remotes/origin/<b>` 拉取，且不拉标签 |
| Gitee Go 的查询 / 取消 / 日志 | 不支持（Gitee 开放接口限制），仅支持触发流水线；工作流查询、取消、日志下载仅 GitHub Actions 可用 |
| Reactor `Mono` 异步 | 不在本模块。异步统一返回 `CompletableFuture` |
| 凭据安全存储、交互式登录 | 无。凭据由调用方通过 Builder 明文传入 |
| Git 仓库的创建（`git init`） | 未提供。目录下没有 `.git` 时 `open()` 直接抛 `GitClientException`，请先 clone |

---

## 许可证

Apache License 2.0
