# utils-support-maven-starter

> utils 体系的 **Maven 编译与部署集成模块**：链式 Builder API 创建 MavenClient，基于 Maven Invoker 以独立进程完成 pom.xml 的同步/异步编译与构建，并可按部署目标（本地目录 / SSH+SFTP / 自定义实现）分发构建产物。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`，源码 UTF-8）｜包名：`com.chua.maven.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 常用能力速查](#21-常用能力速查)
  - [2.2 构建器方法速查](#22-构建器方法速查)
- [3. 快速开始](#3-快速开始)
  - [3.1 同步编译](#31-同步编译)
  - [3.2 预设任务模式](#32-预设任务模式)
  - [3.3 异步编译与取消](#33-异步编译与取消)
  - [3.4 编译结果与产物扫描](#34-编译结果与产物扫描)
  - [3.5 部署到本地目录](#35-部署到本地目录)
  - [3.6 部署到 SSH/SFTP 远程服务器](#36-部署到-sshsftp-远程服务器)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-maven-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 编译 | Maven 链式客户端：同步/异步编译、自定义目标、多 Profile、跳过测试、quiet/debug/offline | 不封装 Maven 仓库管理（依赖上传由 `deploy` 目标本身完成） |
| 进程 | 基于 Apache Maven Invoker（`maven-invoker` 3.3.0）的独立进程编译，可指定 JAVA_HOME / Maven 安装路径 | 不内嵌 Maven 发行版，必须在本机可解析到 Maven |
| 部署 | 编译产物的部署：本地文件系统复制、SSH/SFTP 上传、自定义 `MavenDeployTarget` | 不处理 Nexus 等仓库的制品发布（`deploy` 目标直接透传） |
| 回调 | 编译进度回调、编译生命周期回调、部署回调 | 不提供 GUI、不集成 IDE |
| 依赖 | 依赖 `utils-support-common-starter`、`utils-support-git-starter`、`maven-invoker`；`utils-support-ssh-starter` 为 `provided`（按需引入） | 不依赖 Spring |

**判断口径**：凡是「把 pom.xml 交给 Maven 编译、再把产物搬到某处」的事情归本模块；需要 Git 仓库操作的是 `utils-support-git-starter`，需要 SSH 底层能力的是 `utils-support-ssh-starter`。

### 1.3 规模

- Java 源文件 **11** 个，全部位于包 `com.chua.maven.support`。
- 部署目标契约 `MavenDeployTarget` 一份接口，内置两个实现（`LocalDeployTarget`、`SshDeployTarget`）。
- 内置 GraalVM `native-image` 的 `reflect-config.json`（声明 `com.chua.ssh.support.client.SftpClient` 及其嵌套 Builder/UploadOperation/MkdirOperation/StatOperation/RmOperation）。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 编译 pom.xml | `MavenClient.create()` → `MavenClientBuilder` | 链式配置，`execute()` 同步编译 |
| 指定目标 | `.goal("clean", "compile", "package")` | 追加目标；`.goals(List)` 整体替换 |
| 预设任务 | `.compile()` / `.test()` / `.install()` / `.packageTask()` / `.deploy()` | 常用目标组合一键设定 |
| 跳过测试 | `.skipTests(true)` | 注入 `skipTests` 与 `maven.test.skip` 属性 |
| 多 Profile | `.profile("dev")` / `.profiles(List)` | 激活 Maven Profile |
| 指定 JDK 编译 | `.jdkVersion("21")` + `.javaHome("C:/jdk-17")` | fork 进程的 `java.version`、`maven.compiler.source/target` 与 JAVA_HOME |
| 进度反馈 | `.onProgress((msg, pct) -> ...)` | `MavenCompilerProgress`：`onProgress(String message, int percent)` |
| 生命周期回调 | `.onCallback(new MavenCompilerCallback() {...})` | `onStart` / `onComplete` / `onSuccess` / `onFailure` / `onCancel` |
| 异步编译 | `client.executeAsync()` / `builder.executeAsync()` | 返回 `Future<MavenCompileResult>`，可 `cancel()` |
| 编译后部署 | `.compileAndDeploy()` | 构建 + 返回 `MavenDeployClient` |
| 部署到本地 | `deploy().deployTo("/opt/app/")` / `deployTo(File)` | `LocalDeployTarget`，文件复制 |
| 部署到远程 | `deploy().deployToSsh(host, 22, user, pass, "/opt/app/")` | `SshDeployTarget`，SFTP 上传（需 `utils-support-ssh-starter`） |
| 部署到文件 | `deploy().deployToFile("/opt/app.jar")` | 复制主产物到单个文件 |
| 重命名产物 | `deploy().rename("app-new.jar")` | 移动主产物并返回新路径 |
| 编译结果 | `MavenCompileResult` | `isSuccess` / `getExitCode` / `getErrors` / `getDurationMillis` / `getArtifacts` / `getMainArtifact` |
| 自定义部署目标 | 实现 `MavenDeployTarget` 接口 | `name` / `connect` / `upload` / `createDirectory` / `disconnect` 等 |

### 2.2 构建器方法速查

以下方法名均与源码一致，全部返回 `this` 支持链式：

| 方法 | 说明 |
| --- | --- |
| `projectPath(String path)` | 设置 pom.xml 路径；传入目录自动拼接 `pom.xml`，传入 null 解析到当前目录的 pom.xml |
| `goal(String... goals)` | 追加目标 |
| `goals(List<String> goals)` | 替换目标列表 |
| `profile(String... profiles)` / `profiles(List<String>)` | 追加 / 替换激活 Profile |
| `compile()` | 只编译：清空目标后加 `compile` |
| `test()` | 仅测试：清空目标后加 `test` |
| `install()` | 清空目标后加 `clean`、`install` |
| `packageTask()` | 清空目标后加 `clean`、`compile`、`package` |
| `deploy()` | 清空目标后加 `clean`、`compile`、`package`、`deploy` |
| `skipTests(boolean)` | 跳过测试 |
| `quiet(boolean)` / `debug(boolean)` / `offline(boolean)` | 静默 / 调试 / 离线模式 |
| `jdkVersion(String)` | JDK 版本号，如 `"17"`、`"21"` |
| `javaHome(String)` | JDK 安装路径，如 `C:/jdk-17`；留空用当前进程的 java |
| `mavenHome(String)` | Maven 安装路径，如 `C:/apache-maven-3.9.9`；留空自动解析 |
| `onProgress(MavenCompilerProgress)` | 编译进度回调 |
| `onCallback(MavenCompilerCallback)` | 编译生命周期回调 |
| `property(String, String)` / `properties(Map)` | 追加系统属性 |
| `executor(ExecutorService)` | 自定义异步线程池 |
| `execute()` | 同步编译，返回 `MavenCompileResult` |
| `executeAsync()` | 异步编译，返回 `Future<MavenCompileResult>` |
| `build()` | 构建 `MavenClient` 实例 |
| `compileAndDeploy()` | 编译并返回 `MavenDeployClient` |
| `compileAndDeployAsync()` | 异步编译并返回 `Future<MavenDeployClient>` |
| `getGoalString()` | 目标列表转空格分隔字符串；未配置目标时默认 `"compile"` |

> 未显式配置目标时默认目标为 `compile`（`MavenClientBuilder.DEFAULT_GOALS`）。
> 注意源码中未开启的开关：`quiet` / `debug` / `offline` 三个布尔值在 `MavenClient` 的 `doExecute` 中**未参与任何分支**（`quiet` 与 `offline` 仅存储；`debug` 仅注入 `debug=true` 属性），效果以实际构建为准。

---

## 3. 快速开始

以下示例均可在不依赖 Spring 的纯 Java 环境运行。

### 3.1 同步编译

```java
import com.chua.maven.support.MavenClient;
import com.chua.maven.support.MavenCompileResult;
import com.chua.maven.support.MavenCompilerCallback;

MavenCompileResult result = MavenClient.create()
        .projectPath("G:/project/pom.xml")
        .goal("clean", "compile")
        .profile("dev")
        .skipTests(true)
        .onProgress((msg, pct) -> System.out.printf("[%d%%] %s%n", pct, msg))
        .onCallback(new MavenCompilerCallback() {
            @Override
            public void onSuccess(MavenCompileResult r) {
                System.out.println("编译成功, 耗时: " + r.getDurationMillis() + "ms");
            }
        })
        .execute();

if (result.isSuccess()) {
    System.out.println("构建成功: " + result.getMainArtifact());
} else {
    System.out.println("构建失败, exitCode=" + result.getExitCode() + ", errors=" + result.getErrors());
}
```

链式创建后也可以 `build()` 保留客户端复用：

```java
MavenClient client = MavenClient.create().projectPath("pom.xml").goal("compile").build();
MavenCompileResult r1 = client.execute();
MavenCompileResult r2 = client.execute("package");   // 覆盖目标执行
client.close();
```

### 3.2 预设任务模式

```java
import com.chua.maven.support.MavenClient;

// 只编译
MavenClient.create().projectPath("pom.xml").compile().execute();

// 只测试
MavenClient.create().projectPath("pom.xml").test().execute();

// 本地安装（clean install）
MavenClient.create().projectPath("pom.xml").install().execute();

// 完整打包（clean compile package）
MavenClient.create().projectPath("pom.xml").packageTask().execute();

// 部署（clean compile package deploy）
MavenClient.create().projectPath("pom.xml").deploy().execute();
```

### 3.3 异步编译与取消

```java
import com.chua.maven.support.MavenClient;
import com.chua.maven.support.MavenCompileResult;
import java.util.concurrent.Future;

MavenClient client = MavenClient.create().projectPath("pom.xml").goal("compile").build();
Future<MavenCompileResult> future = client.executeAsync();

// 需要取消时
future.cancel(true);

// 等待结果
MavenCompileResult result = future.get();
```

也可以直接用构建器异步执行：

```java
Future<MavenCompileResult> future = MavenClient.create()
        .projectPath("pom.xml")
        .goal("package")
        .executeAsync();
```

> `MavenClientBuilder.executeAsync()` 使用配置的线程池或默认线程池；`MavenClient.executeAsync()` 自建单线程虚拟线程池（`Thread.ofVirtual().factory()`）。通过返回的 `Future` 可取消编译。

### 3.4 编译结果与产物扫描

```java
import com.chua.maven.support.MavenCompileResult;

MavenCompileResult result = /* execute() 的返回值 */;

result.isSuccess();             // 是否成功
result.getExitCode();           // 进程退出码
result.getOutput();             // 标准输出
result.getErrors();              // 错误信息列表
result.getDurationMillis();     // 编译耗时（毫秒）
result.getProjectPath();        // 项目 pom.xml 路径
result.getProjectDir();         // 项目根目录
result.getGoals();              // 执行的 Maven 目标
result.getProfiles();           // 激活的 Profile
result.getArtifacts();          // 构建产物路径列表（jar/war/ear）
result.getMainArtifact();       // 第一个产物路径（无则 null）
result.getMainArtifactName();   // 第一个产物文件名（无则 null）
result.hasArtifacts();          // 是否有至少一个产物
```

产物扫描规则（`MavenCompileResult.success(...)` 触发）：执行了包含 `package` / `install` / `deploy` / `jar` 的目标才扫描；先扫项目根目录 `target/` 第一层 jar / war / ear 文件，根目录无产物时再递归扫描各子模块的 `target/` 目录。

### 3.5 部署到本地目录

```java
import com.chua.maven.support.MavenClient;
import com.chua.maven.support.MavenDeployClient;
import com.chua.maven.support.LocalDeployTarget;
import com.chua.maven.support.MavenDeployCallback;
import java.util.List;

// 方式一：本地部署（快捷方式）
MavenClient.create()
        .projectPath("pom.xml")
        .goal("clean", "package")
        .compileAndDeploy()
        .deployTo("/opt/app/");

// 方式二：指定部署目标（含部署回调）
LocalDeployTarget target = new LocalDeployTarget("/opt/app");
MavenClient.create()
        .projectPath("pom.xml")
        .goal("clean", "package")
        .compileAndDeploy()
        .onDeploy(new MavenDeployCallback() {
            @Override
            public void onDeployProgress(String message, int percent) {
                System.out.printf("[部署 %d%%] %s%n", percent, message);
            }

            @Override
            public void onDeploySuccess(List<String> deployedPaths) {
                System.out.println("部署成功: " + deployedPaths);
            }
        })
        .deployTo(target);

// 方式三：已有编译结果
MavenCompileResult result = MavenClient.create()
        .projectPath("pom.xml")
        .goal("package")
        .execute();

MavenDeployClient deploy = MavenDeployClient.from(result);
deploy.deployTo(new LocalDeployTarget("/opt/app"));

// 部署到单个文件
String file = deploy.deployToFile("/opt/app.jar");

// 重命名产物
String renamed = deploy.rename("app-new.jar");
```

`MavenDeployClient` 常用方法：

| 方法 | 说明 |
| --- | --- |
| `from(MavenCompileResult)` | 由编译结果创建部署客户端 |
| `onDeploy(MavenDeployCallback)` | 部署回调（`onDeployStart` / `onDeployProgress` / `onDeploySuccess` / `onDeployFailure`） |
| `getResult()` | 获取编译结果 |
| `canDeploy()` | 编译成功且有产物才能部署 |
| `deployTo(MavenDeployTarget)` | 部署到任意目标，返回部署后的文件名列表 |
| `deployTo(String targetDir)` | 快捷部署到本地目录 |
| `deployToFile(String)` | 复制主产物到指定文件 |
| `rename(String)` | 重命名主产物并返回新路径 |

> 编译失败或无产物时调用部署 / 文件复制 / 重命名会抛 `MavenDeployException`（如 `"无法部署: 编译失败或无产物"`、`"无产物可部署"`）。

### 3.6 部署到 SSH/SFTP 远程服务器

```java
import com.chua.maven.support.MavenClient;
import com.chua.maven.support.SshDeployTarget;

// 方式一：传入连接参数
SshDeployTarget sshTarget = new SshDeployTarget("192.168.1.100", 22, "root", "password", "/opt/app");
MavenClient.create()
        .projectPath("pom.xml")
        .goal("clean", "package")
        .compileAndDeploy()
        .deployTo(sshTarget);

// 方式二：默认 22 端口
SshDeployTarget target = new SshDeployTarget("192.168.1.100", "root", "password", "/opt/app");

// 直接使用目标
target.connect();
target.upload("target/myapp.jar", "myapp.jar");
target.uploadBatch(List.of("a.jar", "b.jar"), "/libs");
target.disconnect();
```

> `SshDeployTarget` 在 `connect()` 时通过反射（`ReflectUtils`）动态加载 `com.chua.ssh.support.client.SftpClient`，不编译期强依赖。使用前需要在 classpath 引入 `utils-support-ssh-starter`（本模块 pom 中为 `provided` scope，不会传递进来）。`createDirectory(".")` 会被忽略（远程根目录已隐含）。

---

## 4. 配置项

**无**。本模块没有读取任何配置文件或系统属性作为功能开关。

说明与依据：

- 不依赖 Spring，也没有 `@ConfigurationProperties` 装配。
- 唯一涉及外部环境的是 `MavenClient` 对 Maven 安装路径的解析顺序（写死在代码里，不是可配置项）：

1. `MavenClientBuilder.mavenHome(...)` 显式指定（项目级，优先且不写全局属性）；
2. 系统属性 `maven.home`；
3. 环境变量 `MAVEN_HOME`；
4. 环境变量 `M2_HOME`；
5. 在 `PATH` 中查找 `mvn.cmd` / `mvn`，并校验同级 `lib` 目录存在。

> 都找不到时返回 null，编译结果直接判定失败，错误信息为 `"未找到 Maven 安装路径，请设置 MAVEN_HOME 环境变量或 maven.home 系统属性"`。

---

## 5. SPI 扩展点

**无 `META-INF/extensions/` 下的 SPI 注册**。本模块不通过 `ServiceProvider` 装配实现。

说明与依据：

- `MavenDeployTarget` 接口注释提到「通过 SPI 或直接实例化可扩展为其他部署方式（SSH、最小io、Nexus 等）」，但模块内**没有** `META-INF/extensions/` 目录、没有 `@Extension` 注解标注的实现类。
- 实际扩展方式是**实现 `MavenDeployTarget` 接口并直接实例化**，传给 `MavenDeployClient.deployTo(MavenDeployTarget)`：

```java
public class MyDeployTarget implements MavenDeployTarget {
    @Override public String name() { return "my:target"; }
    @Override public boolean isReady() { return true; }
    @Override public void connect() { /* 建立连接 */ }
    @Override public void upload(String localPath, String targetPath) { /* 传输 */ }
    @Override public void createDirectory(String path) { /* 建目录 */ }
    @Override public boolean exists(String path) { return false; }
    @Override public void delete(String path) { /* 删除 */ }
    @Override public void disconnect() { /* 断开 */ }
}

MavenClient.create()...compileAndDeploy().deployTo(new MyDeployTarget());
```

- 接口契约：`name()`、`isReady()`、`connect()`、`upload(localPath, targetPath)`、`createDirectory(path)`、`exists(path)`、`delete(path)`、`disconnect()`；默认方法 `uploadBatch(files, targetDir)`（按 `targetDir + "/" + 文件名` 逐条上传并返回上传成功数）、`close()`（等价 `disconnect()`）、`setCallback(MavenDeployCallback)`（可选实现）。
- 回调扩展点：编译侧 `MavenCompilerProgress`（函数式接口，`onProgress(String message, int percent)`）与 `MavenCompilerCallback`（默认方法 `onStart` / `onComplete` / `onSuccess` / `onFailure` / `onCancel`）；部署侧 `MavenDeployCallback`（默认方法 `onDeployStart` / `onDeployProgress` / `onDeploySuccess` / `onDeployFailure`）。全部回调抛异常都会被捕获并仅记 warn 日志，不影响主流程。

---

## 6. 构建与依赖

### 6.1 编译要求

- JDK 25（`maven.compiler.source/target=25`、`java.version=25`）。
- 源码 UTF-8、无 BOM；`maven.compiler.parameters=true`。
- slf4j 版本统一由根 pom 的 `${slf4j.version}` 管理，本模块不得覆盖（`2.1.0-alpha2` 在所有仓库均不存在）。

```bash
mvn -pl utils-support-core-parent/utils-support-maven-starter -am clean package
```

### 6.2 依赖

| 依赖 | 用途 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 公共支持（`Wraps` 集合、`ReflectUtils` 反射） | 同版本 `${project.version}` |
| `com.chua:utils-support-git-starter` | Git 集成 | 同版本 `${project.version}` |
| `org.apache.maven.shared:maven-invoker` | 独立进程编译 | 版本 `${maven.invoker.version}`（`3.3.0`） |
| `com.chua:utils-support-ssh-starter` | SSH/SFTP 部署能力 | **`provided` scope，需使用方自行引入** |

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| Maven 本地仓库 / 依赖解析 | 由 Maven 本身与 `settings.xml` 决定，本模块不接管 |
| Nexus / Artifactory 制品发布 | 不封装；走 Maven `deploy` 目标直接透传 |
| Git 版本管理 / 标签 / 提交 | 在 `utils-support-git-starter` |
| 应用进程远程启停 / 服务管理 | 在 `utils-support-common-starter` 的 `service` 包（`ServiceBuilder`） |
| 通用 SSH 客户端能力 | 在 `utils-support-ssh-starter`（`com.chua.ssh.support.client.SftpClient` 等） |
| 嵌入式构建（不装 Maven 直接编译） | 不支持；必须能在本机解析到 Maven 安装路径 |
| 产物直接推送到云存储 | 不在本模块；部署目标可自行扩展 `MavenDeployTarget` 实现 |
| GUEST / 可视化界面 | 无 GUI；仅提供 API 与回调 |
| 部署目标的 SPI 自动发现 | 不提供；需显式 `new` 具体目标或实现 `MavenDeployTarget` 并直接实例化 |

---

Apache License 2.0