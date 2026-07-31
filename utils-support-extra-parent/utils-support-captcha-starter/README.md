# utils-support-captcha-starter

验证码模块

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-captcha-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `CaptchaParser` | 验证码解析器接口 定义验证码解析服务的核心行为：提交验证码图片进行解析、查询解析结果。 |
| `CaptchaRequest` | 验证码解析请求参数 封装提交验证码解析请求所需的全部参数，包括验证码类型、 目标页面 URL、siteKey、代理设置、超时时间等。 |
| `CaptchaResponse` | 验证码解析响应结果 封装验证码解析服务返回的处理结果，包含解析是否成功、 解析得到的 token、任务 ID、错误码及执行耗时等信息。 |
| `CaptchaRunClient` | CaptchaRun 验证码解析服务客户端 |
| `CaptchaSetting` | 验证码解析服务配置 用于配置验证码解析服务（如 CaptchaRun）的连接参数， 包括 API 令牌、服务地址、超时时间等。 |
| `CaptchaType` | 验证码类型枚举 支持市面上主流的验证码服务类型，包括 Google reCAPTCHA、HCaptcha、FunCaptcha、 Cloudflare Turn |
| `FileTaskPersistence` | 基于文件存储的验证码任务持久化实现 使用纯文本文件存储任务结果，每行一条记录。 |
| `TaskPersistence` | 验证码任务持久化存储接口 提供验证码解析任务的缓存能力，避免重复请求。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-captcha-starter
├── utils-support-common-starter
```