# utils-support-desktop-starter

桌面通知原生实现：Windows/macOS/Linux 系统通知调用

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-desktop-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `DesktopPush` | 桌面通知推送实现 通过 调用操作系统原生桌面通知能力。 (SPI: `desktop`) |
| `LinuxDesktopNotifier` | LinuxDesktopNotifier |
| `MacOsDesktopNotifier` | MacOsDesktopNotifier |
| `NativeDesktopNotifier` | 桌面通知 SPI 接口。 |
| `WindowsDesktopNotifier` | WindowsDesktopNotifier |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-desktop-starter
├── utils-support-common-starter
```