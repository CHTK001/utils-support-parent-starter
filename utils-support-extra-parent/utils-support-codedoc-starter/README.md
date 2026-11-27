# utils-support-codedoc-starter

从指定源码目录生成可离线浏览的工具文档站。模块扫描源码文件、调用现有 `ChatClient` 生成结构化说明，并输出功能概览、参数与返回值、调用示例、生成问题记录和源码定位。

## 引入

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-codedoc-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

该模块仅依赖同版本 `utils-support-common-starter`。请使用应用中已配置好的模型客户端，或按 `ChatClient` 的现有工厂配置创建客户端；生成器不会创建、关闭或修改调用方的客户端。

## 快速使用

```java
import com.chua.codedoc.support.CodeDocResult;
import com.chua.codedoc.support.CodeDocs;
import com.chua.common.support.ai.chat.ChatClient;

import java.io.IOException;
import java.nio.file.Path;

void generateDocs(ChatClient chatClient) throws IOException {
    CodeDocResult result = CodeDocs.builder(chatClient)
            .directory(Path.of("D:/workspace/my-tools"))
            .outputDirectory(Path.of("D:/workspace/my-tools-docs"))
            .includeTypes("java", "kt", "js", "ts", "vue", "py")
            .include("**/*Utils.java", "**/api/**")
            .exclude("**/generated/**", "**/target/**")
            .title("工具使用手册")
            .instructions("使用简体中文，面向 Java 开发者说明调用方式。")
            .onProgress(progress -> System.out.printf("%d/%d %s %s%n",
                    progress.completed(), progress.total(), progress.status(), progress.path()))
            .generate();

    System.out.println("站点入口：" + result.indexFile());
    System.out.println("完整生成：" + result.complete());
}
```

打开 `result.indexFile()` 指向的 `index.html` 即可浏览，无需启动 HTTP 服务、安装前端依赖或访问 CDN。生成目录还包含 `data.js`、`site.css`、`site.js` 和 `manifest.json`；请整体保留这些文件。

## 过滤规则

- `includeTypes(...)` 覆盖默认源码扩展名列表，大小写不敏感；传入空参数表示不按扩展名筛选。扩展名可写 `java`、`.java` 或 `*.java`。
- `excludeTypes(...)` 添加排除扩展名，优先于包含类型。
- `include(...)` 与 `exclude(...)` 接受相对于源码根目录的 Ant 风格路径模式。排除的目录在遍历前剪枝；不跟随符号链接。
- 默认排除常见构建/依赖目录以及 `.env`、`.env.*`、`*.pem`、`*.key` 和 `secrets.*`。生成输出目录也不会被扫描。
- `maxDepth(0)` 只处理根目录文件；`maxFiles(...)` 超限会中止扫描；`maxFileBytes(...)` 限制单文件读取大小。
- 默认按严格 UTF-8 解码、统一换行并拒绝含 NUL 的文件。可通过 `charset(...)` 显式更换字符集。

## 增量、失败和进度

- 默认按源码、提示版本、路径、模型客户端标识、补充说明和分块大小生成内容寻址缓存，缓存位于输出目录的 `.codedoc-cache/`。缓存命中时不调用模型。
- 如更改客户端隐藏的系统提示、路由或其他未暴露配置，请修改 `cacheNamespace(...)`；也可用 `incremental(false)` 禁用缓存。
- 每个文件按有重叠的源码块串行调用模型；JSON 结构和源码行号通过校验后才纳入站点。失败默认记录在站点的生成记录中并继续；`failFast(true)` 会在文件失败时中止。
- `retries(n)` 是每块失败后的额外尝试次数。客户端调用仍是同步 `chatSync`；超时由 `ChatClient` 自身配置控制，本模块不保证对现有客户端强制设置超时。
- `onProgress(...)` 接收文件级的 `GENERATED`、`CACHED` 或 `FAILED` 状态。进度事件仅含相对路径和计数，不含源码或凭据。
- `CodeDocResult.complete()` 仅在所有匹配文件均成功且没有扫描、缓存等问题时为 `true`，不代表文档结论或示例已经执行验证。

## 数据与安全说明

源码会作为提示内容发送到 `ChatClient` 所配置的模型服务。生成站点及 `.codedoc-cache/` 会保存被扫描的源码快照和模型生成内容；按组织的数据分级要求配置文件过滤、输出目录权限和缓存保留策略。默认排除规则不能替代对项目敏感文件的审查。

生成结果是 AI 辅助文档：模型只能依据提供的源码作答，但仍需人工检查。示例不会被编译或执行，站点会明确标注未验证。页面将生成文本作为纯文本渲染，不执行模型返回的 HTML 或脚本。

## 配置默认值

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| 输出目录 | 源码目录下 `codedoc-site/` | 与源码根相同或位于源码根上级时拒绝 |
| 文件扩展名 | Java、Kotlin、Scala、Groovy、JS/TS、Vue、Python、Go、Rust、C/C++、C#、PHP、Ruby、Swift、SQL | 可用 `includeTypes(...)` 覆盖 |
| 最大深度 | 不限 | 根目录深度为 0 |
| 最大文件数 | 10000 | 超限整体中止 |
| 单文件上限 | 1 MiB | 可设范围 1 byte 至 64 MiB |
| 每块字符上限 | 16000 | 可设范围 1024 至 262144；不是模型 token 上限 |
| 额外重试次数 | 1 | 可设范围 0 至 5 |
| 增量缓存 | 开启 | 由 `incremental(false)` 关闭 |

## 运行回归测试

在本模块目录执行（PowerShell）：

```powershell
mvn -o test "-Dmaven.test.skip=false" "-DskipTests=false"
```

父工程默认同时跳过测试编译和执行，因此两个开关都需要显式关闭。测试覆盖扫描过滤、读取边界、分块行号、JSON 结构与记录序列化、安全转义及离线资源写入，不调用模型服务。模型调用、增量命中及浏览器交互仍需使用真实客户端和生成站点单独验收。
