# P3C 质量门禁

Alibaba Java Coding Guidelines（黄山版）中**可机械校验**部分的自动化检查，用于防止整改成果回退。

两种实现，检查项与判定口径完全一致：

| 实现 | 依赖 | 适用场景 |
| --- | --- | --- |
| `P3cCheck.java` | 仅 JDK 11+（单文件直接运行） | 跨平台 / CI（Linux、macOS、Windows 均可） |
| `Check-P3c.ps1` | PowerShell 7+ | Windows 本地开发 |

## 用法

```powershell
# 方式一：Java 版（推荐，无需额外运行时）
java tools/p3c-check/P3cCheck.java            # 全仓检查，违规时退出码 1
java tools/p3c-check/P3cCheck.java --quiet    # 只看汇总

# 方式二：PowerShell 版
pwsh -NoProfile -File tools/p3c-check/Check-P3c.ps1
pwsh -NoProfile -File tools/p3c-check/Check-P3c.ps1 -Quiet
```

## 检查项

| 检查 | 条款 | 判定口径 |
| --- | --- | --- |
| 超长行 | 1.3.7 | 代码行 > 120 字符；排除注释、文本块（`"""`）内容 |
| 行尾空白 | 1.3.1 | 行尾存在空格或制表符（文本块外） |
| 制表符缩进 | 1.3.1 | 行首使用 `\t` |
| 空 Javadoc | 1.8 | `/** */` 内没有任何描述 |
| 注解位于 Javadoc 之前 | 1.8 | 注解独立成行且下一行以 `/**` 开始 |
| void 方法带 `@return` | 1.8 | `@return` 后紧跟的方法返回 `void` |
| 空 catch | 2.1 | `catch (...) {}` 且体内无注释说明（排除字符串/文本块内的 JS） |
| 日志占位符不匹配 | 2.4 | 单行 `log.xxx(...)` 的 `{}` 数量与实参数量不一致（末位 Throwable 不计） |
| BOM | 编码 | 文件以 UTF-8 BOM 开头 |
| U+FFFD | 编码 | 含替换字符，说明编码已损坏 |
| 模块循环依赖 | 6.x | 解析所有 `pom.xml` 的 `com.chua` 依赖图，检测环 |

## 豁免机制

脚本内置 `$longLineAllow` 列表，登记「语法上不可拆」的历史遗留：

| 文件 | 原因 |
| --- | --- |
| `AlibabaSmsMessagePush.java` | 123 字符的 `import`，Java 语法不允许折行 |
| `SystemdServiceManager.java` | lambda 内单条 `sb.append(...)` 链，含多处 `\"` 转义，拆分后更难读 |

新增豁免必须写明原因，且只用于「物理不可拆」的情形。

## 自检

脚本在提交前做过注入验证：故意写入 4 类违规（超长行、注解顺序、void `@return`、空 catch）后门禁准确报出 4 处 FAIL；移除样例后恢复全 PASS。

## 与完整整改记录的关系

本脚本只覆盖**可机械判定**的条款。完整的整改过程、人工判断项与豁免理由见 [docs/p3c-remediation.md](../../docs/p3c-remediation.md)。
