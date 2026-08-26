# utils-support-google-starter

谷歌云集成模块：Cloud Storage、Cloud Vision、Gemini 大模型、Colab Enterprise

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-google-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `GoogleChatClient` | Google Gemini 大模型对话客户端 基于 Google Gemini API 的 实现，通过 HTTP 协议 调用 Gemini 系列模型的对话接口， |
| `GoogleImageClient` | Google Imagen 图片生成客户端 基于 Google Vertex AI Imagen 或 Gemini API 的 实现， 通过 HTTP 协议调用 |
| `GoogleCloudFileStorage` | Google Cloud Storage 文件存储实现。 基于 Google Cloud Storage SDK 实现 SPI 接口。 |
| `GoogleColabClient` | Google Colab Enterprise 客户端。基于 Vertex AI NotebookService API 实现运行时管理与笔记本执行作业提交、状态跟踪。 |

---

## Colab Enterprise 使用说明

> 注意：普通版 Colab（colab.research.google.com）不提供公开 API，本客户端仅支持 **Colab Enterprise**。

```java
// 创建客户端：projectId + 区域 + 服务账号 JSON（为空时走 ADC 凭据链）
try (GoogleColabClient client = new GoogleColabClient("my-project", "us-central1", serviceAccountJson)) {
    // 查询运行时
    List<GoogleColabClient.RuntimeInfo> runtimes = client.listRuntimes();

    // 提交笔记本执行作业（GCS 上的 .ipynb），立即返回
    GoogleColabClient.ExecutionInfo exec = client.executeNotebook(
            "gs://bucket/notebook.ipynb",   // 笔记本地址
            "gs://bucket/output/",          // 输出目录
            "my-runtime-template",          // 运行时模板 ID
            "demo-execution");

    // 等待执行完成（轮询，超时毫秒）
    exec = client.waitExecution(exec.getId(), 30 * 60 * 1000);
}
```

前置条件：
1. 启用 Vertex AI API（`aiplatform.googleapis.com`）
2. 服务账号需具备 `aiplatform.admin` 或相应 NotebookService 权限

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-google-starter
├── utils-support-common-starter
```