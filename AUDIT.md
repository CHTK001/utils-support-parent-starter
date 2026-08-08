# utils-support-parent-starter 修复跟踪表

> 本表跟踪整个 `utils-support-parent-starter` 内 Java 文件的 P3C / ch-java-coding-style 违规及修复进度。
>
> 规范要求:
> - ch-java-coding-style(本地 skill,16 条强制规则)
> - P3C 阿里巴巴 Java 开发手册(黄山版)
>
> 修复状态:
> - ⬜ 未检查
> - � 检查中
> - ✅ 已修复
> - ⚠️ 已记录但暂不修复(给出原因)
> - ❌ 修复失败

## 子模块概况

| 子模块 | Java 文件数 | 状态 |
|---|---:|---|
| utils-support-cloud-parent | 60 | ⬜ |
| utils-support-core-parent | 1535 | ⬜ |
| utils-support-datalake-parent | 27 | ⬜ |
| utils-support-datasource-parent | 77 | ⬜ |
| utils-support-datatask-parent | 233 | ⬜ |
| utils-support-deeplearning-parent | 259 | ⬜ |
| utils-support-extra-parent | 177 | ⬜ |
| utils-support-filesystem-parent | 266 | � |
| utils-support-gateway-parent | 30 | ⬜ |
| utils-support-middleware-parent | 60 | ⬜ |
| utils-support-models-parent | 0 | ⬜ |
| utils-support-network-parent | 174 | ⬜ |
| utils-support-runtime-parent | 80 | ⬜ |

## 修复进度汇总

| 模块 | 总文件数 | 已检查 | 已修复 | 待修复 | 失败 |
|---|---:|---:|---:|---:|---:|
| 总计 | 2978 | 0 | 0 | 0 | 0 |

## 各模块明细

- [utils-support-cloud-parent](./utils-support-cloud-parent.md)
- [utils-support-core-parent](./utils-support-core-parent.md)
- [utils-support-datalake-parent](./utils-support-datalake-parent.md)
- [utils-support-datasource-parent](./utils-support-datasource-parent.md)
- [utils-support-datatask-parent](./utils-support-datatask-parent.md)
- [utils-support-deeplearning-parent](./utils-support-deeplearning-parent.md)
- [utils-support-extra-parent](./utils-support-extra-parent.md)
- [utils-support-filesystem-parent](./utils-support-filesystem-parent.md)
- [utils-support-gateway-parent](./utils-support-gateway-parent.md)
- [utils-support-middleware-parent](./utils-support-middleware-parent.md)
- [utils-support-models-parent](./utils-support-models-parent.md)
- [utils-support-network-parent](./utils-support-network-parent.md)
- [utils-support-runtime-parent](./utils-support-runtime-parent.md)

## 违规分类汇总(P3C + ch-style 编号)

| 编号 | 规则 | 等级 |
|:---:|---|---|
| 1 | 中文详细注释 | 强制 |
| 2 | 属性必须多行注释 | 强制 |
| 3 | 单行注释必须在代码上方 | 强制 |
| 4 | 类注释添加 @author CH | 强制 |
| 5 | 控制语句必须大括号 | 强制 |
| 6 | 代码不允许压缩成一行 | 强制 |
| 7 | 使用 Lombok 和 Record 简化 | 强制 |
| 8 | 删除 package 上方注释 | 强制 |
| 11 | 修复代码上的问题(魔法值/NPE/资源泄漏) | 强制 |
| 13 | 必须遵守 P3C | 强制 |
| 14 | 日志必须 SLF4J + @Slf4j | 强制 |
| 15 | 日志消息必须中文 + `[业务模块]` 前缀 | 强制 |
| 16 | POJO 必须 @Data / record | 强制 |
| P-1 | 不使用 final 修饰方法参数 | 强制 |
| P-2 | 不使用 final 修饰局部变量 | 强制 |
| P-3 | 字符串比较使用 "常量".equals(变量) | 强制 |
| P-4 | 不捕获 RuntimeException | 强制 |
| P-5 | finally 不使用 return | 强制 |
| P-6 | 集合初始化指定初始容量 | 推荐 |

## 日志规范细则(规则 15)

- **必须中文**:日志消息主体使用中文,英文仅限专有名词/类名/字段名
- **必须 `[业务模块]` 前缀**:`[模块名]` 是类所属业务模块名(例如 `[aliyun-oss]`、`[runtime-shell]`、`[network-quarkus]`、`[file-storage]`、`[database-jdbc]`、`[kafka-producer]`),**不是类名**
- **业务模块判断**:从类的包路径取最后 1-3 段,如 `com.chua.aliyun.support.oss.OssClient` → `[aliyun-support-oss]`;或从父目录/类注释/@since 中提炼
- **示例**:
  - ✅ `log.info("[aliyun-oss] 上传文件成功: bucket={}, key={}", bucket, key)`
  - ✅ `log.warn("[database-jdbc] 连接超时,重试第{}次", retry)`
  - ❌ `log.info("Upload success: bucket={}", bucket)`(非中文)
  - ❌ `log.info("[OssClient] 上传成功")`(`[OssClient]` 是类名,不是业务模块)

## 修复日志

- 2026-08-08 初始化表格,生成总览
- 2026-08-08 新增规则 14/15/16:SLF4J/中文日志/Lombok 简化
