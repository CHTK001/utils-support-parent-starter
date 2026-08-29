# utils-support-huggingface-starter

[Hugging Face Hub](https://huggingface.co) 仓库管理集成模块（模型/数据集仓库的增删查改、文件上传下载，含 LFS 大文件）。

> Hugging Face 官方无 Java SDK，本模块通过 Hub REST API 与 Git 直接对接。

## 前置

- Hugging Face 账号 + 访问令牌（`https://huggingface.co` → Settings → Access Tokens）；只读公开仓库可免令牌
- Maven 依赖（version 由根 `utils.version` 统一管理）：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-huggingface-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
```

## 快速开始

```java
// 国内网络建议切换镜像（避免 huggingface.co PKIX 证书失败/直连慢）
HuggingfaceHubClient hub = new HuggingfaceHubClient().mirror();

// 仓库元信息
Map<String, Object> info = hub.getRepoInfo("segmind/tiny-sd");

// 文件清单（siblings.rfilename）
List<String> files = hub.listFiles("segmind/tiny-sd");

// 单文件下载（免鉴权 resolve URL，自动跟随 302 跳转至 LFS 存储）
hub.downloadFile("segmind/tiny-sd", "model_index.json", Path.of("./model_index.json"));

// 完整克隆（含 LFS 权重），需本机 git + git-lfs
Path repo = hub.downloadSnapshot("segmind/tiny-sd", Path.of("/data/cache"));
```

## 带令牌操作（增删改）

```java
HuggingfaceHubClient hub = new HuggingfaceHubClient("hf_xxx").mirror();

// 创建仓库（type: model / dataset）
hub.createRepo("my-org/my-model", "model", false);

// REST API 单文件上传（默认 main 分支）
hub.uploadFile("my-org/my-model", "config.json", Path.of("./config.json"));

// 删除仓库文件
hub.deleteFile("my-org/my-model", "config.json");

// git push 上传本地仓库（GB 级 LFS 权重首选；本地必须是 git 仓库并配置 user.name/email）
hub.uploadSnapshot("my-org/my-model", Path.of("./local-repo"));

// 删除仓库
hub.deleteRepo("my-org/my-model");
```

## 自定义 baseUrl

```java
// 默认官方地址
new HuggingfaceHubClient("hf_xxx").getBaseUrl();   // https://huggingface.co

// 国内镜像（API 兼容）
new HuggingfaceHubClient("hf_xxx").mirror();        // https://hf-mirror.com

// 自建代理 / 企业镜像
new HuggingfaceHubClient("hf_xxx").baseUrl("https://your-proxy.example.com");
```

## 端点

- Hub：`https://huggingface.co`（镜像 `https://hf-mirror.com`，**仅读路径**可用，写接口被 308 跳回官方站）
  - `GET    /api/models/{repoId}`（仓库元信息）
  - `GET    /api/whoami-v2`（账户信息）
  - `GET    /{repoId}/resolve/{revision}/{path}`（文件下载，免鉴权，LFS 302 跳转）
  - `POST   /api/models/{repoId}/preupload/{revision}`（判定 LFS/regular）
  - `POST   /{repoId}.git/info/lfs/objects/batch`（LFS 取上传 URL）
  - `PUT    <S3 预签名 URL>`（LFS 对象上传，不带 Authorization 头）
  - `POST   <LFS verify URL>`（LFS 对象注册）
  - `POST   /api/models/{repoId}/commit/{revision}`（NDJSON commit：上传/删除文件）
  - `POST   /api/repos/create`（创建仓库）
  - `DELETE /api/repos/delete`（删除仓库，body `{name, organization, type}`）

> 旧版 `POST /api/models/{repoId}/upload/...` 与 `DELETE /api/models/{repoId}/delete/...`
> 端点已被 HF 下线（410），本客户端已改用 commit 端点。

## 鉴权

- HTTP API：`Authorization: Bearer <token>`；公开仓库只读免令牌
- Git 推送/克隆：`oauth2:<token>@` 内嵌到仓库 URL
- LFS 上传：batch 返回的 S3 预签名 URL 自带鉴权，**不得**附加 Authorization 头

## 网络与重试

- 客户端对幂等读操作（`getRepoInfo`/`listFiles`/`downloadFile`）遇瞬时 5xx 自动重试
  （3 次、指数退避）；写操作不盲目重试以免重复提交。
- 国内/代理环境若直连 huggingface.co 出现 PKIX 证书失败（MITM 代理签发），
  可把代理 CA 导入 JVM truststore：`-Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=changeit`。

## License

Java 客户端遵循仓库根 LICENSE。Hugging Face 平台与模型权重许可参见 https://huggingface.co。
