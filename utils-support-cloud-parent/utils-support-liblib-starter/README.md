# utils-support-liblib-starter

LiblibAI 星流 ultra 文生图的 `ImageClient` 实现，SPI 名称为 `liblib`。实际实现见 [LiblibImageClient](src/main/java/com/chua/liblib/support/image/LiblibImageClient.java)。

## 安装

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-liblib-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

## 快速开始

配置对象中的 `appKey` 对应 AccessKey，`secretKey` 对应 SecretKey，两者缺一不可。凭据由调用方提供：

```java
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import javax.imageio.ImageIO;
import java.nio.file.Path;

ImageClientSetting setting = ImageClientSetting.builder()
        .provider("liblib")
        .appKey(System.getenv("LIBLIB_ACCESS_KEY"))
        .secretKey(System.getenv("LIBLIB_SECRET_KEY"))
        .build();
try (ImageClient client = ImageClient.create(setting)) {
    client.size(1024, 1024).steps(30);
    var image = client.generate("A red fox in a quiet forest, natural light");
    ImageIO.write(image, "png", Path.of("fox.png").toFile());
}
```

## 任务与结果

- `generate(prompt)` 提交后每 3 秒查询一次，最多等待 900 秒，成功后下载首张图片并解码。
- `createTask(prompt)` 只提交并返回任务号；调用方持久化任务号，再用 `queryTask(taskId)` 查询，不需要把 Web 请求阻塞到出图。
- 提交使用 `/api/generate/webui/text2img/ultra`，查询使用 `/api/generate/webui/status`。请求 query 自动带 AccessKey、时间戳、随机串与 HMAC-SHA1 签名。
- 宽高钳制到每边 512–2048；提示词不能为空且不超过 2000 字符，按上游要求使用英文。
- `model(...)` 仅用于标识，实际提交使用实现内置的 ultra 模板；不会据模型名自动切换到其它 API。

## 使用边界

`negativePrompt`、`seed`、`quality`、`style` 不会转换成该接口不存在的参数。参考图字节上传未实现，`supportsReferenceImage()` 为 false；不能把平台拥有图生图 API 理解为本客户端已实现图生图。上游进度暂不可用时返回 null，不伪造百分比。账户未开通接口权限时会收到上游错误，引入 jar 不代表账户具备生成权限。

[返回云服务目录](../README.md) · [返回总目录](../../README.md)
