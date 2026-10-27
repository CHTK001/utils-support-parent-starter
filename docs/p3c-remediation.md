# P3C 规约整改记录（utils-support-parent-starter）

> 依据《阿里巴巴 Java 开发手册（黄山版）》逐条排查与整改。本文件记录已完成的整改、复核后确认合规的项，以及仍待人工判断的遗留项。

## 一、整改总览

| 提交 | 规则 | 变更内容 | 规模 |
| --- | --- | --- | --- |
| `ba04c38cc` | 注释规约 / 异常日志 | 注解与 Javadoc 顺序修正、空 catch 补日志、冗余 Javadoc 清理 | 1112 文件 |
| `d20d145a0` | 异常日志 2.1.6 | 补齐 56 处空 catch 块日志，13 个类补全日志器 | 45 文件 |
| `ac1fffcb8` | 注释规约 | 方法体内误置的 17 处 Javadoc 归位/降级 | 18 文件 |
| `72addfcad` | 注释规约 | 删除 34 处连续重复 Javadoc，纠正文档归属 | 34 文件 |
| `39d11ec29` | 注释规约 | 移除 45 处 void 方法上错误的 `@return` | 24 文件 |
| `4f00c7943` | 注释规约 | 删除 136 处空 Javadoc、补 17 处摘要、修畸形标签、修 2 类缺失 `@Slf4j` | 49 文件 |
| `5220bd74c` | 异常日志 | 561 处 `log.error/warn` 只打印 `getMessage()` 的调用补传异常对象 | 308 文件 |
| `04c8753d3` | 异常日志 | 28 处跨行日志调用补传异常对象 | 23 文件 |
| `1bbc5e899` | OOP 规约 | 155 处 `xxx.equals("字面量")` 改为 `"字面量".equals(xxx)` | 36 文件 |
| `952c104c5` | 控制语句 | 11 处 `if/for/else` 单语句补大括号 | 3 文件 |
| `025f8b524` | 控制语句 | `PNGImageWriter` 位深分支补 `default`；`TsharkCliConfig` 告警改用日志 | 2 文件 |

## 二、整改细则

### 1. 注释与注解顺序（1.9 / 4.1）

- 统一为「Javadoc → 注解 → 声明」，全仓 `standalone 注解后紧邻 /**` 的数量为 **0**。
- 单行注解声明（如 `@JsonProperty("role") public String role;`）不计入违规。

### 2. 空 catch 块（异常日志 2.1.6）

- 判定标准：`catch` 块内既无语句也无注释。仅含说明性注释的 catch 视为已处理。
- 处理方式：`log.debug("[模块] 中文说明: {}", e.getMessage())`；异常变量统一为 `e`。
- 无日志器的类按模块依赖补 `@Slf4j`（有 lombok）或 `java.util.logging.Logger LOG`（paddle / tensorflow 模块无 lombok 依赖）。

### 3. 异常日志（堆栈完整性）

```
log.error("...{}", e.getMessage())      →  log.error("...{}", e.getMessage(), e)
log.warn("...{}", error.getMessage())   →  log.warn("...{}", error.getMessage(), error)
```

- 仅当最后一个实参是 `<异常变量>.getMessage()` 时追加该异常对象；非异常对象（如 `ReturnPageResult.getMessage()`）不改。

### 4. equals 空指针防护（OOP 规约）

- `<变量>.equals("字面量")` → `"字面量".equals(<变量>)`，接收者为 `null` 时由抛 NPE 变为返回 `false`。

### 5. 控制语句（1.4 / 4.x）

- `if/for/while/else` 单语句一律补大括号。
- `switch` 必须含 `default`；**箭头式 switch 表达式**由编译器保证穷尽性，不强制补 `default`。

## 三、复核后确认合规的项

| 检查项 | 结论 |
| --- | --- |
| `e.printStackTrace()` | 0（仅出现在注释示例中） |
| 中文标识符 / 类名、方法名大小写 | 0 违规 |
| `static SimpleDateFormat` | 0（仅工厂方法每次 new，线程安全） |
| 废弃 API：`new Integer()`、`Class.newInstance()`、`Date.getYear()` | 0 |
| `new BigDecimal(double)` | 0（全部为字符串构造） |
| `System.gc()` | 0 |
| `finally` 块中的 `return` | 0 |
| 子类引用父类 private `log` | 0 |

## 四、遗留项（需人工判断，未批量修改）

1. **527 处仅含 `@param/@return` 的 Javadoc**：缺一句话摘要，需按方法语义撰写，机械生成会降低可读性。
2. **54 处 `Executors.newXxx()`**：P3C 要求改用 `ThreadPoolExecutor`。但其中大量为虚拟线程执行器（`newVirtualThreadPerTaskExecutor`，无队列堆积风险），改造会改变并发语义（队列容量、拒绝策略），需按场景设计。
3. **324 处 `catch (Throwable ...)`**：框架入口、任务执行器、插件加载器属合理用途；其余应收窄为具体异常。
4. **`new Thread(...)` 83 处**：均为具名长生命周期线程、`ThreadFactory` 实现或 `addShutdownHook`，符合「使用 ThreadFactory 创建线程」的要求。
5. **`System.out/err` 372 处（51 文件）**：复查后剩余均为 CLI 子命令（oshi 系列）、基准测试/演示 `main`、`TraceContext` 与 `SelfDefense` 的「日志系统不可用」兜底输出，属合理用途。
6. **62 处 `throw new RuntimeException("msg")`**：均为参数校验或上游返回为空抛出的新异常（无 cause 可传），是否改为 `IllegalArgumentException` / `IllegalStateException` 待定。

## 五、验证方式

```powershell
# 1. 受影响模块编译（全量重编译，避免增量缓存掩盖问题）
mvn -o compile -DskipTests "-Dpmd.skip=true" "-Dspotbugs.skip=true" `
    "-Dmaven.javadoc.skip=true" "-Dmaven.compiler.useIncrementalCompilation=false"

# 2. 分批编译（Windows 命令行长度受限时）
mvn -o -q compile -pl "module-a,module-b" -DskipTests "-Dpmd.skip=true" `
    "-Dspotbugs.skip=true" "-Dmaven.javadoc.skip=true"
```

### 注意事项

- **增量编译会掩盖 lombok 可见性错误**：父类 `@Slf4j` 生成 `private static final Logger log`，子类若直接引用 `log`，只有在子类被重新编译时才会报错。已修复 `SeedModeDiscovery` / `RouteModeDiscovery` 并静态复查全仓，同类问题为 0。
- **未纳入 reactor 的模块**：`utils-support-deeplearning-arcsoft-starter`、`utils-support-extra-parent/utils-support-playwright-starter` 未在父 POM 的 `<modules>` 中，不参与 Maven 构建，其改动无法通过编译验证。
- **并发开发中的模块**：`utils-support-cloud-parent/utils-support-openai-starter` 存在在途改动（`EacChatClient` / `PteroChatClient` / `InceptionCdpClient` 重构），本轮整改全程排除该模块。
