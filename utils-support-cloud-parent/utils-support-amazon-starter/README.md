# utils-support-amazon-starter

亚马逊云集成模块：S3 存储、Bedrock 大模型

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-amazon-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AmazonChatClient` | AWS Bedrock 大模型对话客户端 基于 Amazon Bedrock Runtime API 的 实现，通过 HTTP 协议 调用 AWS Bedroc |
| `AmazonImageClient` | Amazon Bedrock 图片生成客户端 基于 AWS Bedrock Runtime InvokeModel 的 实现， 通过 HTTP 协议调用 Bed |
| `AmazonS3FileStorage` | Amazon S3 文件存储实现（兼容所有 S3 协议存储：MinIO、Ceph、JuiceFS 等）。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-amazon-starter
├── utils-support-common-starter
```