# 1.3.7 剩余超长行清单（最终 2 处，均为语法不可拆项）

> 口径：代码行长度 > 120 字符，排除注释行、文本块（`"""`）内部行。数据由全仓扫描生成，可直接用于逐行精修。

## 一、按类型汇总

| 类型 | 数量 | 典型成因 | 建议处理方式 |
| --- | --- | --- | --- |
| literal | 0 | 单条字符串超过 120 字符（HTML/CSS 片段、提示词、错误消息） | 长字符串字面量：可提取为 `private static final String` 常量，或按 `+` 拼接拆分（值不变，但可读性下降） |
| other | 2 | 深层调用/长表达式（builder 链、三元、try-with-resources） | 深层表达式：需按语义重排（提取局部变量或拆分调用链） |
| signature | 0 | 方法签名参数多或含全限定类型名 | 超长签名：多为泛型/长类型名，建议把全限定名改 import 简写，或按参数逐个换行 |
| regex | 0 | 正则表达式字面量 | 正则字面量：建议保留原样，或提取为 `static final Pattern` 常量以便复用与预编译 |
| **合计** | **2** | — | — |

## 二、完整清单

### literal（0 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |

### other（2 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |
| 1 | `com.chua.alibaba.support.sms.AlibabaSmsMessagePush.java:16` | 123 | `import com.aliyun.dysmsapi20170525.models.QuerySmsTemplateListResponseBody.QuerySmsTemplateListResponseBodySmsTemplateLi…` |
| 2 | `com.chua.runtime.core.service.SystemdServiceManager.java:259` | 123 | `service.getEnv().forEach((k, v) -> sb.append("Environment=\"").append(k).append("=").append(v).append("\"\n"));` |

### signature（0 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |

### regex（0 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |

## 三、处理优先级建议

1. **signature（24 处）**：最容易改且收益直接——把全限定名改 import 后多数可落到 120 以内。
2. **other（51 处）**：需要按语义拆分，建议随功能迭代顺手处理。
3. **literal（51 处）**：多为 HTML/CSS/提示词模板，拆分会降低可读性，建议仅在必须时提取常量。
4. **regex（13 处）**：拆分会破坏正则可读性，建议保留；如需复用可提取为 `static final Pattern`。
