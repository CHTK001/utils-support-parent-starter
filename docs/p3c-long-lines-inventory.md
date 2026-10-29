# 1.3.7 剩余超长行清单（139 处）

> 口径：代码行长度 > 120 字符，排除注释行、文本块（`"""`）内部行。数据由全仓扫描生成，可直接用于逐行精修。

## 一、按类型汇总

| 类型 | 数量 | 典型成因 | 建议处理方式 |
| --- | --- | --- | --- |
| literal | 2 | 单条字符串超过 120 字符（HTML/CSS 片段、提示词、错误消息） | 长字符串字面量：可提取为 `private static final String` 常量，或按 `+` 拼接拆分（值不变，但可读性下降） |
| other | 2 | 深层调用/长表达式（builder 链、三元、try-with-resources） | 深层表达式：需按语义重排（提取局部变量或拆分调用链） |
| signature | 0 | 方法签名参数多或含全限定类型名 | 超长签名：多为泛型/长类型名，建议把全限定名改 import 简写，或按参数逐个换行 |
| regex | 13 | 正则表达式字面量 | 正则字面量：建议保留原样，或提取为 `static final Pattern` 常量以便复用与预编译 |
| **合计** | **17** | — | — |

## 二、完整清单

### literal（2 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |
| 1 | `com.chua.common.support.utils.StringUtils.java:4212` | 148 | `Pattern.compile("(insert\|delete\|update\|select\|create\|drop\|truncate\|grant\|alter\|deny\|revoke\|call\|execute\|exe…` |
| 2 | `com.chua.common.support.datasearch.music.spi.impl.TencentMusicSourceProvider.java:334` | 121 | `"Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")), "req", "data", "track_info");` |

### other（2 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |
| 1 | `com.chua.alibaba.support.sms.AlibabaSmsMessagePush.java:16` | 123 | `import com.aliyun.dysmsapi20170525.models.QuerySmsTemplateListResponseBody.QuerySmsTemplateListResponseBodySmsTemplateLi…` |
| 2 | `com.chua.runtime.core.service.SystemdServiceManager.java:259` | 123 | `service.getEnv().forEach((k, v) -> sb.append("Environment=\"").append(k).append("=").append(v).append("\"\n"));` |

### signature（0 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |

### regex（13 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |
| 1 | `com.chua.common.support.constant.RegexConstant.java:34` | 483 | `"(?:[a-z0-9!#$%&'*+/=?^_`{\|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{\|}~-]+)*\|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23…` |
| 2 | `com.chua.common.support.constant.RegexConstant.java:105` | 158 | `"^(25[0-5]\|2[0-4]\\d\|[0-1]?\\d?\\d)\\.(25[0-5]\|2[0-4]\\d\|[0-1]?\\d?\\d)\\.(25[0-5]\|2[0-4]\\d\|[0-1]?\\d?\\d)\\.(25[…` |
| 3 | `com.chua.common.support.constant.RegexConstant.java:118` | 201 | `"4})?:)?((25[0-5]\|(2[0-4]\|1?[0-9])?[0-9])\\.){3}(25[0-5]\|(2[0-4]\|1?[0-9])?[0-9])\|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]…` |
| 4 | `com.chua.common.support.constant.RegexConstant.java:124` | 122 | `"([1-9]\|[1-9]\\\\d\|1\\\\d{2}\|2[0-4]\\\\d\|25[0-5])(\\\\.(\\\\d\|[1-9]\\\\d\|1\\\\d{2}\|2[0-4]\\\\d\|25[0-5])){3}");` |
| 5 | `com.chua.common.support.lang.compile.Compiler.java:61` | 182 | `Pattern METHOD_PATTERN = Pattern.compile("(private\|public\|protect)\\s+(([a-zA-z][$_a-zA-z0-9.]*)(<(.*?)>)*)\\s+([a-zA-…` |
| 6 | `com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter.java:279` | 122 | `"(?i)([`\"]?(?!NULL\\b\|NOT\\b\|NULL_\|PRIMARY\\b\|UNIQUE\\b\|KEY\\b\|DEFAULT\\b\|CONSTRAINT\\b)[A-Za-z_]\\w*[`\"]?)"` |
| 7 | `com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter.java:499` | 127 | `"(?is)^ALTER\\s+TABLE\\s+[`\"]?([A-Za-z_]\\w*)[`\"]?\\s+ADD\\s+COLUMN\\s+[`\"]?([A-Za-z_]\\w*)[`\"]?\\s+(.+)$")` |
| 8 | `com.chua.common.support.utils.StringUtils.java:4213` | 177 | `"\\s+.*(into\|from\|set\|where\|table\|database\|view\|index\|on\|cursor\|procedure\|trigger\|for\|password\|union\|and\…` |
| 9 | `com.chua.common.support.utils.Validator.java:42` | 155 | `"^(25[0-5]\|2[0-4]\\d\|[01]?\\d\\d?)\\.(25[0-5]\|2[0-4]\\d\|[01]?\\d\\d?)\\.(25[0-5]\|2[0-4]\\d\|[01]?\\d\\d?)\\.(25[0-5…` |
| 10 | `com.chua.calcite.support.datasource.EngineUpdateSqlExecutor.java:35` | 157 | `"(?is)^\\s*UPDATE\\s+(?:(?:`([^`]+)`\|([A-Za-z_][\\w$]*))\\s*\\.\\s*)?(?:`([^`]+)`\|([A-Za-z_][\\w$]*))\\s+SET\\s+(.+?)(…` |
| 11 | `com.chua.sqlserver.support.flyway.SqlServerScriptConverter.java:130` | 123 | `+ "([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)\\s+ON\\s+([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)\\s*(\\(.*\\))\\s*;?\\s*$");` |
| 12 | `com.chua.kimi.support.KimiChatClient.java:801` | 126 | `"!\\[.*?\\]\\((https?://[^)]+)\\)\|https?://[^\\s)\"'<>]+(?:\\.(?:png\|jpg\|jpeg\|webp\|gif))(?:\\?[^\\s)\"'<>]*)?"` |
| 13 | `com.chua.playwright.support.qwen.QwenProxyChatClient.java:601` | 126 | `"!\\[.*?\\]\\((https?://[^)]+)\\)\|https?://[^\\s)\"'<>]+(?:\\.(?:png\|jpg\|jpeg\|webp\|gif))(?:\\?[^\\s)\"'<>]*)?"` |

## 三、处理优先级建议

1. **signature（24 处）**：最容易改且收益直接——把全限定名改 import 后多数可落到 120 以内。
2. **other（51 处）**：需要按语义拆分，建议随功能迭代顺手处理。
3. **literal（51 处）**：多为 HTML/CSS/提示词模板，拆分会降低可读性，建议仅在必须时提取常量。
4. **regex（13 处）**：拆分会破坏正则可读性，建议保留；如需复用可提取为 `static final Pattern`。
