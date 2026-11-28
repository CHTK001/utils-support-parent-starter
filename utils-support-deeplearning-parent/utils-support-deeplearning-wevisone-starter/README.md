# utils-support-deeplearning-wevisone-starter

WeVisOne-VL 的远程视觉推理客户端。Java 侧实现 `VlmClient`（SPI `wevisone`），图像和任务通过 HTTP 发给独立 Python 服务。Java jar 不含权重，也不执行本地推理。

## 安装

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-wevisone-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

## 快速开始

先由运维准备 [python](python) 中的推理服务及模型，客户端连接已经就绪的地址：

```java
import com.chua.deeplearning.support.wevisone.WevisoneVlClient;
import com.chua.deeplearning.support.image.VlmRequest;
import com.chua.deeplearning.support.image.VlmTask;
import java.nio.file.Files;
import java.nio.file.Path;

WevisoneVlClient client = new WevisoneVlClient(System.getenv("WEVISONE_URL"))
        .timeoutSeconds(180);
if (!client.available()) {
    throw new IllegalStateException("视觉推理服务尚未就绪");
}
byte[] image = Files.readAllBytes(Path.of("photo.png"));
var result = client.infer(VlmRequest.of(image, VlmTask.DETECTION, "cat\nperson"));
System.out.println(result.text());
System.out.println(result.objects());
```

也可用 `VlmClient.create("wevisone")` 获取 SPI 实例；需要指定服务地址时使用具体类型的 `baseUrl(...)`。

## 能力和结果

`infer(VlmRequest)` 支持 [VlmTask](../../utils-support-core-parent/utils-support-deeplearning-starter/src/main/java/com/chua/deeplearning/support/image/VlmTask.java) 定义的指令任务，包括检测、指代、点、计数、分割、抠图、OCR 和自由提示。任务字段、参考框及抠图解码选项由请求传递，提示词协议由服务端实现。

`VlmResult` 返回任务、文本、对象、掩码及耗时。框和点采用归一化坐标，绘制时乘以原图尺寸；`targetMissing()` 可判断模型未找到目标。不要用空字符串推断所有任务的成功状态。

## 协议与配置

- `WevisoneHttpClient`：`POST /infer` 发送 Base64 图像、task、query、prompt、reference_boxes、decode_matting；`GET /status` 判断模型是否就绪。
- `baseUrl(...)` 修改服务地址，`timeoutSeconds(...)` 设置正数秒超时，两者都会使内部 HTTP 客户端在下次使用时重建。
- `model(...)` 设置标识；实际加载哪些权重由 Python 服务决定。
- 旧 `understand(image, UnderstandTask)` 仅映射 OCR、OCR_WITH_REGION 和三种 caption；其它 Florence-2 专属任务抛 `UnsupportedOperationException`，请使用指令式入口。
- 服务不可达或返回失败时抛异常；安装客户端不等于模型已部署。

[实现源码](src/main/java/com/chua/deeplearning/support/wevisone/WevisoneVlClient.java) · [返回推理目录](../README.md)
