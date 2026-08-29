# ObjectContextExample 单元测试覆盖矩阵

## 版本信息

| 项目 | 内容 |
|:----|:----|
| 类名 | ObjectContextExample |
| 模块 | utils-support-example-starter |
| 包名 | com.chua.example.objects |
| 作者 | CH |
| 创建日期 | 2026-07-30 |
| 更新日期 | 2026-07-30 |
| 对应技能 | ch-java-coding-style |

## SPI 实现覆盖矩阵

| 脚本语言 | ScriptMarker | Listener | createInstance | getBean | getType | 热重载 | 自检通过 |
|:--------|:------------|:--------|:--------------|:--------|:--------|:------|:--------|
| Java | ScriptMarker | FileScriptListener | ✅ | ✅ | ✅ | N/A | 待测 |
| Groovy | GroovyScriptMarker | FileScriptListener | ✅ | ✅ | ✅ | ✅ | 待测 |

## 测试场景覆盖矩阵

| 场景ID | 测试方法 | 入参 | 断言 | 通过条件 |
|:------|:--------|:-----|:-----|:--------|
| TC-01 | testJavaScript | Java 脚本文件 | sayHello 返回 "Hello, Java from Java!" | result.equals("Hello, Java from Java!") |
| TC-02 | testGroovyScript | Groovy 脚本文件 | sayHello 返回 "Hello, Groovy from Groovy!" | result.equals("Hello, Groovy from Groovy!") |
| TC-03 | testHotReload | Groovy 脚本文件 | 首次与重载结果不同，Class 不同 | class1 != class2 && result2 包含 "reloaded" |

## 命令行参数覆盖矩阵

| 参数 | 缩写 | 必填 | 类型 | 默认值 | 说明 |
|:----|:----|:----|:----|:------|:-----|
| 无 | - | - | - | all | 执行全部能力点 |
| --type java | - | 否 | String | all | 仅测试 Java 脚本 |
| --type groovy | - | 否 | String | all | 仅测试 Groovy 脚本 |
| --type reload | - | 否 | String | all | 仅测试热重载 |
| --help | -h | 否 | - | - | 显示帮助 |

## P3C 规约遵守检查清单

| 维度 | 规则项 | 遵守 | 说明 |
|:-----|:------|:----|:-----|
| 规则 1 | 中文详细注释 | ✅ | 类 Javadoc、方法 Javadoc |
| 规则 2 | 属性必须多行注释 | ✅ | 所有字段均有 Javadoc |
| 规则 3 | 单行注释在代码上方 | ✅ | 各测试段上方注释 |
| 规则 4 | 类注释添加 @author CH | ✅ | 类 Javadoc |
| 规则 5 | 控制语句大括号 | ✅ | if/for/while 均有 `{}` |
| 规则 6 | 代码不允许压缩 | ✅ | 全部展开为多行 |
| 规则 7 | Lombok/Record 简化 | ✅ | 无冗余 POJO |
| 规则 8 | 删除 package 上方注释 | ✅ | package 上方无注释 |
| 规则 10 | Record 注释在类上 | ✅ | 无 record 定义 |
| 规则 11 | 修复代码问题 | ✅ | 判空处理、异常捕获 |
| 规则 12 | 示例工程规范 | ✅ | 位于 utils-support-example-starter |
| 规则 13 | 遵守 P3C | ✅ | 完整遵守 |

## 执行记录

| 日期 | 实现类型 | 结果 | 备注 |
|:-----|:--------|:----|:-----|
| 2026-07-30 | ScriptBeanDefinition + FileScriptListener | ⏳ 待执行 | 首次创建 |

## 规则违反记录

本示例创建时已完整遵守全部 13 条规则，无违反记录。
