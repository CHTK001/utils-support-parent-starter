# utils-support-tui-starter

终端 UI 仪表盘：Mordant 渲染 + @IpcMethod 数据绑定 + 系统监控组件

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tui-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `SystemMonitorService` | 系统监控数据提供者。 通过 注解暴露实时系统数据， 供 中的组件消费。 使用 Java 内置的 获取系统信息， 无需额外依赖。 |
| `MordantHelper` | Mordant 终端渲染辅助工具。 |
| `TuiDashboard` | 终端仪表盘引擎。 管理网格布局中的多个 组件，通过内部处理器注册表获取实时数据， 使用 Mordant 渲染到终端，支持定时刷新和键盘交互。 使用方式： |
| `TuiDashboardBuilder` | 终端仪表盘链式构建器。 类似 的链式风格， 通过一系列方法调用完成配置后构建 实例。 使用方式： |
| `TuiLayout` | 终端仪表盘布局枚举。 定义固定的网格布局规格，每种规格对应终端上的一种排列方式。 例如 GRID_2x2 表示 2 行 2 列共 4 个组件。 |
| `TuiWidget` | 终端仪表盘组件基类。 每个组件绑定一个唯一的 ，该 id 对应 注解中的路径。 组件的数据由 通过内部处理器注册表获取， 再调用 将数据渲染为终端文本。 子类只 |
| `CpuWidget` | CPU 监控组件。 绑定 id 为 ，对应 。 期望返回格式：（百分比数值字符串）。 使用 Mordant 渲染边框面板， 渲染颜色进度条。 |
| `DiskWidget` | 磁盘监控组件。 绑定 id 为 ，对应 。 期望返回格式：多行，每行 。 使用 Mordant 渲染边框面板， 渲染每个分区的使用率条。 |
| `HtopWidget` | Htop 风格综合监控组件。 |
| `MemoryWidget` | 内存监控组件。 绑定 id 为 ，对应 。 期望返回格式：（使用率|已用GB|总量GB）。 使用 Mordant 渲染边框面板， 渲染颜色进度条。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-tui-starter
├── utils-support-common-starter
```