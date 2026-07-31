# utils-support-appimage-starter

AppImage                             -     Docker                       Spring Boot                                   .AppImage

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>utils-support-appimage-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AppImageHealthChecker` | AppImage 健康检查器 通过 HTTP 请求或进程状态检查 AppImage 是否正常运行 |
| `AppImageInstaller` | AppImage 安装管理器 支持从多种格式安装软件到本地系统： - 直接安装 .AppImage 文件 - 解压安装 .tar.gz、.zip、.tar 压缩 |
| `AppImageInstance` | AppImage 实例 POJO，记录单个 AppImage 的运行时信息 |
| `AppImageLifecycleManager` | AppImage 生命周期管理器 负责 AppImage 进程的启动、停止、重启以及健康检查等操作 |
| `AppImageManagementResponse` | AppImage 管理操作响应结果 |
| `AppImageManager` | AppImage 管理器 - 统一入口 集成 appimagetool 下载、JRE 管理、打包等功能。 |
| `AppImagePackager` | AppImage 打包器 将 Spring Boot fat jar 与 JRE 打包为可执行的 AppImage 文件， 参考 SmolvmPackager  |
| `AppImageProcessManager` | AppImage 进程管理器 提供 AppImage 进程的启动、停止、状态查询等操作， 支持 Windows 和 Linux 平台 |
| `AppImageProperties` | AppImage 打包配置属性 提供打包 AppImage 所需的各项配置，包括 JRE 路径、 appimagetool 工具路径、JVM 参数等 |
| `AppImageRuntimeConfig` | AppImage 运行时配置 |
| ... | 共 13 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-appimage-starter
└── (无内部依赖)
```