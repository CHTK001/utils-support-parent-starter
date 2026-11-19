# utils-support-huggingface-starter

> [Hugging Face Hub](https://huggingface.co) 仓库管理集成模块：模型/数据集仓库的增删查改、文件上传下载（含 LFS 大文件），通过 Hub REST API 与 Git 直接对接。
>
> 版本：`4.0.0.51`（类 API 自 `4.0.0.42` 起）｜JDK：`25`（仓库统一 `maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.huggingface.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
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
    <artifactId>utils-support-huggingface-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Hugging Face Hub 的 Java 客户端：仓库元信息/文件清单查询、单文件与整仓上传下载、仓库创建删除 | 不是 Hugging Face 官方 SDK（官方无 Java SDK） |
| 能力 | Hub REST API 增删查改 + Git clone/push；LFS 大文件走 preupload/batch/multipart/verify/commit 流程 | 不做模型推理、不做数据集处理、不管理本地模型缓存格式 |
| 形态 | 纯工具客户端（2 个类，无 Spring 依赖），归属 utils 最底层工具库 | 不是 Spring Boot starter：无自动装配、无 `application.yml` 配置绑定 |

### 1.3 规模

- Java 源文件 **2** 个：`HuggingfaceHubClient`（Hub 客户端）、`HuggingfaceConstants`（端点与超时常量）。
- 无 `src/main/resources` 目录。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 查仓库元信息 | `HuggingfaceHubClient#getRepoInfo(repoId)` | `GET /api/models/{repoId}`，公开仓库免鉴权 |
| 列仓库文件 | `HuggingfaceHubClient#listFiles(repoId)` | 取元信息响应中的 `siblings.rfilename` |
| 下载单文件 | `HuggingfaceHubClient#downloadFile(repoId, pathInRepo, target)` | resolve URL 免鉴权，自动跟随 302 跳转至 LFS 存储 |
| 克隆整仓 | `HuggingfaceHubClient#downloadSnapshot(repoId, localDir)` | `git clone`（含 LFS 大权重），克隆到 `localDir/<repoName>` |
| 上传单文件 | `HuggingfaceHubClient#uploadFile(repoId, pathInRepo, localPath)` | preupload 判定 → LFS batch+PUT 或 base64 内联 → NDJSON commit |
| 推送本地仓库 | `HuggingfaceHubClient#uploadSnapshot(repoId, localRepoPath)` | `git push origin HEAD:main`，GB 级 LFS 权重首选 |
| 创建/删除仓库 | `HuggingfaceHubClient#createRepo(...)` / `#deleteRepo(repoId)` | `POST /api/repos/create`、`DELETE /api/repos/delete` |
| 切换国内镜像 | `HuggingfaceHubClient#mirror()` | 基地址切到 `https://hf-mirror.com`（仅读路径可用） |

---

## 2. 核心 API 与速查表

`HuggingfaceHubClient` 的全部公共成员（与源码一致）：

| 成员 | 签名 | 说明 |
| --- | --- | --- |
| `DEFAULT_REVISION` | `public static final String` | 默认分支，值为 `"main"` |
| 构造 | `new HuggingfaceHubClient()` | 无鉴权客户端，仅可访问公开仓库（只读） |
| 构造 | `new HuggingfaceHubClient(String token)` | 传入访问令牌（`hf_` 开头），允许为 `null` |
| `mirror()` | `HuggingfaceHubClient mirror()` | 基地址切到 `MIRROR_HUB_BASE_URL`（hf-mirror.com），返回 `this` |
| `baseUrl(String)` | `HuggingfaceHubClient baseUrl(String baseUrl)` | 自定义基地址（自建代理/企业镜像），返回 `this` |
| `getBaseUrl()` | `String getBaseUrl()` | 当前生效基地址 |
| `whoami()` | `Map<String, Object> whoami()` | `GET /api/whoami-v2`；匿名调用返回 401，必须携带令牌 |
| `getRepoInfo(String)` | `Map<String, Object> getRepoInfo(String repoId)` | 仓库元信息：id、private、downloads、likes、siblings 等 |
| `listFiles(String)` | `List<String> listFiles(String repoId)` | 文件路径列表（来自 `siblings.rfilename`） |
| `downloadFile(String, String, Path)` | `void` | 下载单文件到 `target`，默认 `main` 分支 |
| `downloadFile(String, String, String, Path)` | `void` | 指定 revision（分支名或 commit id）下载 |
| `downloadSnapshot(String, Path)` | `Path` | `git clone` 整仓到 `localDir/<repoName>`，返回仓库根目录 |
| `uploadFile(String, String, Path)` | `void` | 上传单文件，默认 `main` 分支 |
| `uploadFile(String, String, String, Path)` | `void` | 指定分支上传（新版 commit 端点） |
| `deleteFile(String, String)` | `void` | 删除文件，默认 `main` 分支 |
| `deleteFile(String, String, String)` | `void` | 指定分支删除（commit 端点 `deletedFile` 操作） |
| `createRepo(String, String, boolean)` | `void` | 创建仓库；`repoType` 取 `model` 或 `dataset`；HTTP 409 视为已存在、不报错 |
| `deleteRepo(String)` | `void` | 删除仓库；HTTP 404 视为不存在、不报错；实现中 `type` 固定为 `model` |
| `uploadSnapshot(String, Path)` | `void` | `git remote set-url origin <鉴权URL>` + `git push origin HEAD:main` |

### 2.1 鉴权

| 场景 | 方式 |
| --- | --- |
| HTTP API | 请求头 `Authorization: Bearer <token>`；无令牌时源码仍会附加**空值** `Authorization` 头（`buildAuthHeader()` 返回空串，并非省略该头），公开仓库按匿名只读访问 |
| Git clone / push | 令牌内嵌仓库 URL：`{baseUrl}/oauth2:<token>@{repoId}.git`；无令牌用免鉴权 URL |
| LFS 上传 | batch 返回的 S3 预签名 URL 自带鉴权，**不得**附加 `Authorization` 头（否则 S3 报错） |

### 2.2 端点（常量定义于 `HuggingfaceConstants`）

| 端点 | 用途 |
| --- | --- |
| `GET /api/whoami-v2` | 令牌账户信息 |
| `GET /api/models/{repoId}` | 仓库元信息 / 文件清单 |
| `GET /{repoId}/resolve/{revision}/{path}` | 单文件下载（免鉴权，LFS 302 跳转） |
| `POST /api/repos/create` | 创建仓库 |
| `DELETE /api/repos/delete` | 删除仓库（body `{name, organization, type}`，name 不含 owner 前缀） |
| `POST /api/{repoType}s/{repoId}/preupload/{revision}` | 上传前判定 LFS / regular |
| `POST /{repoId}.git/info/lfs/objects/batch` | LFS 取上传 URL（basic / multipart） |
| `POST /api/{repoType}s/{repoId}/commit/{revision}` | NDJSON commit：上传/删除文件 |

### 2.3 上传、重试与镜像行为

- **上传流程**（`uploadFile`）：先 preupload 判定模式 → LFS 文件经 batch API 取上传 URL 后 `PUT`（basic 直传，或按 `chunk_size` 分片 multipart 上传并收集各分片 ETag），再 POST verify 注册对象 → 最后以 NDJSON commit 提交（regular 文件内联 base64，LFS 引用 oid/size，oid 为 SHA-256 摘要）。
- **重试**：幂等 GET（`whoami` / `getRepoInfo` / `listFiles` / `downloadFile`）遇瞬时 5xx 自动重试，最多 3 次、指数退避（500ms 起）；POST/DELETE 非幂等，不重试。
- **镜像**：`mirror()` 后仅读路径可用，写接口会被 308 跳回官方站；batch 响应中属于本 Hub API 的不可达 host 会对齐到当前 `baseUrl`（S3 预签名 URL 除外）。
- **超时**：连接 60 秒，下载/上传 600 秒。

---

## 3. 快速开始

前置条件：

- 只读公开仓库可免令牌；增删改需 Hugging Face 访问令牌（官网 Settings → Access Tokens）。
- `downloadSnapshot` / `uploadSnapshot` 需本机安装 `git` 与 `git-lfs`；`uploadSnapshot` 还要求本地目录已是 git 仓库（含 `.git`）且配置了 `user.name` / `user.email`。

最短示例（读 + 写各一条链路）：

```java
import com.chua.huggingface.support.HuggingfaceHubClient;

import java.nio.file.Path;
import java.util.List;

// 国内网络建议切镜像，避免直连 huggingface.co 的 PKIX 证书失败/直连慢
HuggingfaceHubClient hub = new HuggingfaceHubClient("hf_xxx-token").mirror();

// 读：文件清单、单文件下载、整仓克隆
List<String> files = hub.listFiles("segmind/tiny-sd");
hub.downloadFile("segmind/tiny-sd", "config.json", Path.of("/data/cache/config.json"));
Path repo = hub.downloadSnapshot("segmind/tiny-sd", Path.of("/data/cache"));

// 写：建仓、传文件、推整仓
hub.createRepo("my-model", "model", false);
hub.uploadFile("my-org/my-model", "config.json", Path.of("./local/config.json"));
hub.uploadSnapshot("my-org/my-model", Path.of("./local-repo"));
```

---

## 4. 配置项

本模块**无 Spring Boot 配置项**：没有 `@ConfigurationProperties`、没有自动装配、没有 `src/main/resources`，因此不存在 `application.yml` 键。全部配置以常量与链式方法提供。

常量（`HuggingfaceConstants`）：

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `DEFAULT_HUB_BASE_URL` | `https://huggingface.co` | 官方 Hub 地址（默认基地址） |
| `MIRROR_HUB_BASE_URL` | `https://hf-mirror.com` | 国内镜像地址（`mirror()` 切换） |
| `PATH_RESOLVE` | `/%s/resolve/%s/%s` | 文件下载路径模板（免鉴权） |
| `PATH_API_MODEL` | `/api/models/%s` | 仓库元信息 API |
| `PATH_API_REPOS_CREATE` | `/api/repos/create` | 创建仓库 |
| `PATH_API_REPOS_DELETE` | `/api/repos/delete` | 删除仓库 |
| `PATH_API_COMMIT` | `/api/%ss/%s/commit/%s` | NDJSON commit 端点 |
| `PATH_API_PREUPLOAD` | `/api/%ss/%s/preupload/%s` | 上传前判定端点 |
| `PATH_API_LFS_BATCH` | `/%s.git/info/lfs/objects/batch` | LFS batch 端点 |
| `UPLOAD_TIMEOUT_MILLIS` | `600_000L` | 上传超时（LFS 大文件可能很慢） |
| `DOWNLOAD_TIMEOUT_MILLIS` | `600_000L` | 下载超时 |
| `CONNECT_TIMEOUT_MILLIS` | `60_000L` | 连接超时（镜像/跨国网络留足余量） |

运行期覆盖手段：令牌经构造器传入；基地址经 `mirror()` 或 `baseUrl(String)` 覆盖。其余端点与超时为常量，源码未提供可配置入口。

---

## 5. SPI 扩展点

- 本模块源码内**未提供** SPI 接口定义，也没有 `META-INF/extensions` 注册文件（模块无 `src/main/resources`）。
- POM 描述与类注释标注「SPI provider="huggingface"」：本类定位为上层 SPI 体系中 provider 名为 `huggingface` 的 Hub 仓库客户端实现；对应的 SPI 契约接口不在本模块源码内（源码未提供）。
- 本模块内没有可扩展点；更换端点只能通过 `mirror()` / `baseUrl(String)` 链式方法。

---

## 6. 构建与依赖

当前 POM 直接依赖（版本 `4.0.0.51`）：

```text
com.chua:utils-support-huggingface-starter
├─ com.chua:utils-support-common-starter   （HttpClientFactory / Json / Wraps / StringUtils 等）
├─ org.slf4j:slf4j-api
└─ org.projectlombok:lombok               （provided）
```

根 POM 另为全体子模块统一注入 `org.projectlombok:lombok`、`org.jetbrains:annotations`、`com.google.code.findbugs:jsr305`（均 `provided`；本模块 POM 对 lombok 作了显式重复声明）。

> 模块目录下的 `dtrees.txt` 为 `4.0.0.42` 时期快照，其中还列有 `org.slf4j:slf4j-simple`（test）等条目；现行 POM 已不再声明 slf4j-simple，依赖以本节为准。

- 聚合于 `utils-support-cloud-parent`（父 POM 链：`utils-support-parent-starter` → `utils-support-cloud-parent` → 本模块），版本随父 POM 统一为 `4.0.0.51`。
- 编译目标 JDK 25，开启 `--enable-preview`（仓库根 POM 统一配置）。
- 无 Spring、无数据库驱动、无 Hugging Face 第三方 SDK 依赖；Git 能力直接调用本机 `git` / `git-lfs` 命令行。
- 编译命令：在本模块目录执行 `mvn -o -q compile -DskipTests`，`EXIT=0` 即通过。

---

## 7. 边界：这些能力不在本模块

| 不在本模块 | 说明 |
| --- | --- |
| 模型推理 / Transformer 执行 | 本模块只管理 Hub 仓库与文件传输，不加载或运行模型 |
| 数据集处理 | 不提供数据集清洗、标注、切分等能力 |
| Spring Boot 自动装配 | 无自动配置类、无配置属性绑定；按需自行 `new HuggingfaceHubClient(...)` |
| 本地模型缓存管理 | 不定义缓存目录结构/版本快照策略；落盘位置由调用方以 `Path` 指定 |
| 镜像站写操作 | hf-mirror.com 仅读路径可用，写接口被 308 跳回官方站；写操作请用官方地址 |
| OAuth 网页登录、Space 管理、评论/点赞等平台功能 | 源码未提供 |
