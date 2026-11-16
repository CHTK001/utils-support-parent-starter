# utils-support-flyway-maven-plugin

> utils 体系的**Flyway 辅助 Maven 插件**：递归扫描项目目录下的初始化脚本，合并生成单一 init SQL（`mode=single`）或运行期可直接执行的 init/initdata 两份脚本（`mode=initdata`）。
>
> 版本：`4.0.0.51`｜编译：`release 17`（Maven 插件是构建工具，无需 Java 25 字节码）｜Maven：`maven-plugin-api 3.9.16`｜包名：`com.chua.flyway.maven`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 插件坐标

`packaging = maven-plugin`，goal 名 `generate-init-sql`，goalPrefix `flyway`（`maven-plugin-plugin` 的 `<goalPrefix>flyway</goalPrefix>` 配置）：

```xml
<plugin>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-flyway-maven-plugin</artifactId>
    <version>4.0.0.51</version>
</plugin>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | Maven 构建期插件：把按模块拆分的 `db/init/*.sql` 聚合为可直接消费的合并 SQL | 不是运行期迁移库（运行期迁移在 `utils-support-flyway-starter` / common-starter 的 `DefaultFlyway`） |
| 合并 | 纯文本拼接：`-- source` 注释 + 72 个 `-` 分隔线 + 脚本内容（trim 去空白）+ 语句结束符 `;` 补齐 | 不做方言转换、不解析 SQL 语义、不改写脚本内容 |
| 输出 | `mode=single` 输出单份 `init-all.sql`（默认 `${project.build.directory}/db/init-all.sql`）；`mode=initdata` 输出 `V{版本}__init_all.sql` 与 `V{版本}__initdata_all.sql` 两份运行期可执行脚本 | 不执行脚本、不连数据库、不做增量 |
| 依赖 | 只依赖 `org.apache.maven:maven-plugin-api`（provided）、`org.apache.maven.plugin-tools:maven-plugin-annotations`（provided）、同仓 `com.chua:utils-support-common-starter`（compile，引用 `com.chua.common.support.collection.wrap.Wraps`） | 不依赖 Spring / Spring Boot、不依赖数据库驱动、不引入三方 Flyway |
| 幂等 | 每次执行覆盖输出的合并文件；生成文件本身会被排除在输入之外，避免重复合并 | 无历史记录、无 checksum 比对 |
| 扫描 | 默认只匹配本模块 `src/main/resources/db/init/**/*.sql`（相对 `sourceRoot` 的 glob），默认排除 `target` / `build` / `.git` / `node_modules` | 不以整棵仓库目录树为根（刻意不用 `**/db/init/**/*.sql`，避免把兄弟模块、退役模块的 SQL 卷进同一份产物） |

**判断口径**：需要把按模块拆分的初始化脚本聚合成运行期可直接消费的单一或分类 SQL 时使用本插件；脚本真正落到数据库由运行期迁移器执行，本插件只负责构建期的「扫描 + 拼接 + 落盘」。

### 1.3 规模

- Java 源文件 **2** 个：`com.chua.flyway.maven.GenerateInitSqlMojo`（Mojo 入口）+ `com.chua.flyway.maven.InitSqlMerger`（纯 JDK 合并器工具类，无第三方依赖）。
- 无 `src/main/resources` 目录（不注册任何 SPI 扩展，见 §5）。
- 运行期依赖同仓 `utils-support-common-starter`（compile scope，`Wraps` 集合工具）。

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 触发合并（默认模式） | `flyway:generate-init-sql` | `@Mojo(name = "generate-init-sql", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)`；默认绑定 `generate-resources` 阶段，随 `mvn generate-resources` / `mvn package` 自动执行 |
| 单份 init SQL | `-Dflyway.merge.mode=single` | 输出单份 `init-all.sql`，默认路径 `${project.build.directory}/db/init-all.sql`（即 `target/db/init-all.sql`），存在则覆盖 |
| 运行期 init/initdata 双脚本 | `-Dflyway.merge.mode=initdata`（默认） | 输出 `V{版本}__init_all.sql`（结构/补丁，同步执行）与 `V{版本}__initdata_all.sql`（初始化数据，异步执行）到 `target/db` |
| 改脚本扫描范围 | `-Dflyway.includes` / `-Dflyway.excludes` | 相对 `sourceRoot` 的 glob 通配模式；不配置时用默认值（见 §4） |
| 换扫描根 | `-Dflyway.sourceRoot` | 默认 `${project.basedir}` |
| 指定合并版本 | `-Dflyway.merge.version` | 默认取被合并脚本中的最高版本，缺省回退 `1.0.0`（去发布后缀） |
| initdata 输出目录 | `-Dflyway.merge.outputDir` | 默认与 `outputFile` 同目录（`target/db`） |
| 确定性输出 | `-Dflyway.stampHeader=true` | 默认 `false`（头部不含生成时间戳，可获得确定性输出） |
| 跳过插件 | `-Dflyway.skip=true` | 日志输出「flyway:generate-init-sql 已跳过」后直接返回 |
| 脚本编码 | `-Dflyway.encoding=UTF-8` | 默认 `UTF-8`；非法字符集名告警并回退 UTF-8 |
| 编程式合并（脱离 Maven） | `com.chua.flyway.maven.InitSqlMerger` | 公开静态方法 `scan` / `merge` / `sortByVersionThenPath` / `versionOf` / `isInitData` / `cleanVersion` / `compareVersions` / `maxVersion` |

### 2.2 `InitSqlMerger` 公开静态方法

工具类 `com.chua.flyway.maven.InitSqlMerger`（`final` 类，禁止实例化；纯 JDK 实现，无第三方依赖）：

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `scan(Path root, List<String> includes, List<String> excludes)` | `List<Path>` | 递归扫描根目录下命中包含模式且不命中排除模式的普通文件；匹配基于正斜杠化的相对路径与 glob→正则（`**` 跨目录 / `*` 同目录 / `?` 单字符），结果按相对路径升序稳定排序 |
| `merge(List<Path> scripts, Path root, Charset charset, boolean header, boolean timestamp)` | `String` | 合并为完整初始化 SQL 文本；重载可传自定义头部标题（`header` 为总头部说明开关，`timestamp` 为时间戳开关） |
| `sortByVersionThenPath(List<Path> scripts, Path root)` | `void` | 原地按「版本号数值升序 + 相对路径」重排，与运行期扫描顺序一致；无版本号脚本排最后 |
| `versionOf(Path script)` | `String` | 提取文件名 `V{版本}__{描述}.sql` 的版本段；文件名不符合命名规范返回 `null` |
| `isInitData(Path script)` | `boolean` | 是否为 initdata 脚本（`V{版本}__initdata_*.sql`，运行期按异步数据脚本执行） |
| `cleanVersion(String version)` | `String` | 去掉发布后缀（`1.0.0-beta → 1.0.0`），保证合并文件名按 STABLE 通过运行期版本类型过滤；为空返回 `null` |
| `compareVersions(String left, String right)` | `int` | 点分多段按数值逐段比较，缺段按 0，非数值段退化为字符串比较；超长数字回退字符串比较 |
| `maxVersion(List<Path> scripts)` | `String` | 取脚本列表中的最高版本（已去后缀）；无可解析版本返回 `null` |

通配匹配基于将相对路径统一为正斜杠后的 glob→正则实现，跨平台一致，规避 `PathMatcher` 在 Windows 下对反斜杠路径匹配不稳定的问题（类 javadoc）。

### 2.3 合并语义（关键事实）

- **默认输入**：`src/main/resources/db/init/**/*.sql`（相对 `sourceRoot` 的 glob）。刻意收窄为本模块相对路径：不以 `project.basedir` 为根递归整棵目录树，避免把兄弟模块、已退出 reactor 但目录仍在的退役模块的 SQL 卷进同一份产物；父工程（`packaging=pom`，自身没有 `db/init`）无脚本时按 `flyway.failIfNoScripts` 处理（默认 `true` 构建失败，`false` 时仅告警跳过）。
- **默认排除**：`**/target/**`、`**/build/**`、`**/.git/**`、`**/node_modules/**`。
- **排序**：`flyway.merge.versionSort=true`（默认）时按「版本号数值升序 + 相对路径」排序，与运行期扫描顺序一致。
- **拼接格式**（`header=true` 时）：`-- ` + 72 个 `-` 分隔线 → `-- {标题}` →（`stampHeader=true` 时）`-- 生成时间: yyyy-MM-dd HH:mm:ss` → `-- 脚本数量: N` → `-- 本文件由 Maven 插件自动生成，请勿手工编辑；修改源脚本后重新生成即可覆盖` → 分隔线；每个脚本前输出 `-- source: {正斜杠相对路径}` + 分隔线，内容 trim 后拼接，若不以 `;` 结尾则补 `;`，再空两行。
- **默认标题**：`Flyway 合并生成：按模块初始化的完整 init SQL`（single 模式）。
- **initdata 分类口径**（与运行期一致）：init 组包含全部非 initdata 脚本（结构、补丁、普通脚本，运行期同步执行），initdata 组仅含 `V*__initdata_*`（表结构之后异步执行）；脚本类型前缀判定 `initdata_` / `init_` / `add_` / 其它（`TYPE_INITDATA_PREFIX` 等私有常量）。
- **版本**：`flyway.merge.version` 显式配置优先（`cleanVersion` 去后缀）；否则取被合并脚本中的最高版本（`maxVersion`）；再缺省回退 `1.0.0`。initdata 模式两份文件共享同一版本号。
- **幂等自排除**：single 模式把 `outputFile` 从输入中排除；initdata 模式额外剔除输出目录下上一轮生成的、以 `V` 开头且以 `__init_all.sql` / `__initdata_all.sql`（即两个描述配置）结尾的文件。
- **日志**：成功输出「已生成合并 SQL: {绝对路径}，合并脚本 {N} 个，共 {M} 字符」；initdata 模式下某组无脚本时 warn「未扫描到 init 脚本，跳过生成 …」/「未扫描到 initdata 脚本，跳过生成 …」。

---

## 3. 快速开始

### 3.1 声明插件（自动绑定 `generate-resources` 阶段）

默认参数即可用：把初始化脚本放进 `src/main/resources/db/init/`，声明插件后执行 `mvn generate-resources`（或任意包含该阶段的构建）。

```xml
<plugin>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-flyway-maven-plugin</artifactId>
    <version>4.0.0.51</version>
    <executions>
        <execution>
            <goals>
                <goal>generate-init-sql</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

脚本目录示例（命名即契约，版本支持点分多段）：

```
src/main/resources/db/init/
├── V1.0.0.1__create_user.sql        # 建表
├── V1.0.0.2__add_user_index.sql     # 加索引（init 组，同步执行）
└── V1.0.0.2__initdata_all_user.sql  # 初始化数据（initdata 组，异步执行）
```

默认 `mode=initdata` 在 `target/db/` 下生成两份运行期可执行脚本，版本取被合并脚本中的最高版本 `1.0.0.2`：

```
target/db/
├── V1.0.0.2__init_all.sql        # 结构/补丁：create_user + add_user_index 的合并
└── V1.0.0.2__initdata_all.sql    # 初始化数据：initdata_all_user 的合并
```

### 3.2 不声明插件，命令行直接执行

```bash
mvn com.chua:utils-support-flyway-maven-plugin:4.0.0.51:generate-init-sql
```

goalPrefix 为 `flyway`，已声明插件后可用短形式 `mvn flyway:generate-init-sql`。

### 3.3 单份合并（`mode=single`）

```bash
mvn flyway:generate-init-sql -Dflyway.merge.mode=single
```

默认输出 `${project.build.directory}/db/init-all.sql`（`target/db/init-all.sql`）。产物形如（`header=true` 默认开启，分隔线为 `-- ` + 72 个 `-`）：

```sql
-- ------------------------------------------------------------------------
-- Flyway 合并生成：按模块初始化的完整 init SQL
-- 脚本数量: 2
-- 本文件由 Maven 插件自动生成，请勿手工编辑；修改源脚本后重新生成即可覆盖
-- ------------------------------------------------------------------------

-- source: src/main/resources/db/init/V1.0.0.1__create_user.sql
-- ------------------------------------------------------------------------
CREATE TABLE sys_user (...);

-- source: src/main/resources/db/init/V1.0.0.2__add_user_index.sql
-- ------------------------------------------------------------------------
ALTER TABLE sys_user ADD INDEX idx_name (name);
```

---

## 4. 配置项

全部为 `@Parameter` 注解声明的 Maven 属性（`property` 即键名），无任何外部配置文件。参数命名统一使用 `flyway.merge.*` 前缀，避免与 Spring Boot 依赖管理中的 `flyway.version` 等项目属性撞名（源码注释）。

| 属性键 | 默认值 | 说明 |
| --- | --- | --- |
| `flyway.sourceRoot` | `${project.basedir}`（`required`） | 扫描根目录；不存在或不是目录时抛 `MojoFailureException`（「扫描根目录不存在或不是目录: …」） |
| `flyway.outputFile` | `${project.build.directory}/db/init-all.sql`（`required`） | 输出文件路径，存在则覆盖；同时从输入中排除 |
| `flyway.includes` | `src/main/resources/db/init/**/*.sql` | 包含通配模式（相对 `sourceRoot` 的 glob），命中任意一条即保留；配置后替换默认 |
| `flyway.excludes` | `**/target/**`、`**/build/**`、`**/.git/**`、`**/node_modules/**` | 排除通配模式（相对 `sourceRoot` 的 glob），命中任意一条即剔除；配置后替换默认 |
| `flyway.encoding` | `UTF-8` | 脚本读取字符集；`Charset.forName` 失败时告警「无效字符集 …，回退 UTF-8」并回退 UTF-8 |
| `flyway.header` | `true` | 是否输出总头部说明 |
| `flyway.stampHeader` | `false` | 头部是否包含生成时间戳（格式 `yyyy-MM-dd HH:mm:ss`）；默认关闭以获得确定性输出 |
| `flyway.failIfNoScripts` | `true` | 未扫描到脚本时是否失败；`false` 时仅 warn（消息「未扫描到任何初始化脚本: root=… includes=…」，日志打印生效的 includes 而非可能为 null 的原始配置） |
| `flyway.skip` | `false` | 跳过本插件，日志「flyway:generate-init-sql 已跳过」 |
| `flyway.merge.mode` | `initdata` | `single`=单份 `init-all.sql`；`initdata`=输出 `V{版本}__init_all.sql` 与 `V{版本}__initdata_all.sql` 两份运行期可执行脚本 |
| `flyway.merge.outputDir` | 与 `outputFile` 同目录（`target/db`） | initdata 模式的输出目录 |
| `flyway.merge.version` | 被合并脚本中的最高版本，缺省回退 `1.0.0` | 合并文件版本号（经 `cleanVersion` 去发布后缀） |
| `flyway.merge.initDescription` | `init_all` | init 合并文件的描述段（`V{版本}__{描述}.sql`），需以 `init_` 开头 |
| `flyway.merge.initDataDescription` | `initdata_all` | initdata 合并文件的描述段，需以 `initdata_` 开头 |
| `flyway.merge.versionSort` | `true` | 是否按「版本号数值升序 + 相对路径」排序（与运行期扫描顺序一致） |

---

## 5. SPI 扩展点

### 5.1 本模块注册的 SPI：无

依据：模块内没有 `src/main/resources` 目录（无任何 `META-INF/extensions` 或 `META-INF/services` 声明文件）；两个源文件均无 `@Spi` / `@Extension` / `ServiceLoader` 相关代码，import 列表仅含 Maven API（`org.apache.maven.plugin.*`）与 common-starter 的 `com.chua.common.support.collection.wrap.Wraps`。

### 5.2 消费的 common-starter 能力（非 SPI）

- `com.chua.common.support.collection.wrap.Wraps`：`GenerateInitSqlMojo` 与 `InitSqlMerger` 用它创建列表（如 `Wraps.list(capacity)`）。pom 注释明确：该引用必须进插件运行期 classpath，故 `utils-support-common-starter` 用默认 compile scope（会随 `plugin.xml` 声明为插件依赖），版本由 root 依赖管理托管。
- 脚本类型分类口径（`initdata_` / `init_` / `add_` / normal）是 `InitSqlMerger` 内的私有常量（`TYPE_INITDATA_PREFIX` 等），与运行期 Flyway 分类口径一致（类 javadoc「与运行期分类口径一致」），但本插件不通过 SPI 暴露，也无法运行期替换。

合并行为（排序、类型判定、版本取值、头部格式）如需调整，唯一开关是 §4 的 Maven 参数；更深的定制点源码未提供。

---

## 6. 构建与依赖

### 6.1 依赖关系

| 依赖 | 版本 / scope | 用途 |
| --- | --- | --- |
| `org.apache.maven:maven-plugin-api` | `${maven.api.version}` = 3.9.16 / provided | Maven 插件 API |
| `org.apache.maven.plugin-tools:maven-plugin-annotations` | `${maven.plugin.annotations.version}` = 3.16.0 / provided | `@Mojo` / `@Parameter` 注解 |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51）/ compile | `Wraps` 集合工具，必须进插件运行期 classpath |

### 6.2 构建

- 模块父 POM：`utils-support-core-parent`；版本 `4.0.0.51`（继承自 `utils-support-parent-starter`）。
- **JDK**：core-parent 的 `maven-compiler-plugin` 默认 `<release>25</release>`，本模块在 `pom.xml` 中**显式覆盖为 `<release>17</release>`**——Maven 插件是构建工具，无需 Java 25 字节码，降到 17 以兼容描述符生成器的 ASM。
- **maven-plugin-plugin 3.16.0**（pom 注释：2026-10-06 由 3.9.0 升级，支持 Java 25 环境，与 `maven-plugin-annotations` 3.16.0 配套；3.16.0 已确认存在于本地仓库 `D:\maven-repo`，离线构建可用）。`<goalPrefix>flyway</goalPrefix>`；execution `default-descriptor` 绑定 `process-classes` 阶段生成插件描述符。
- 构建命令（连带构建 common-starter）：

```bash
mvn -pl utils-support-core-parent/utils-support-flyway-maven-plugin -am clean package
```

- 许可证：`pom.xml` 未声明 `<licenses>` 元素（源码未提供）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 状态 | 依据 |
| --- | --- | --- |
| 运行期执行 SQL / 数据库迁移 | 不在 | 插件只读取、拼接、写文件，不连数据库；运行期迁移用 `utils-support-flyway-starter` 的 `DataSourceFlyway`（`Flyway` SPI，扩展键 `flyway`）或 common-starter 的 `DefaultFlyway` |
| 方言转换 / 脚本改写 | 不在 | 类 javadoc：合并语义为纯文本拼接，不做方言转换，保持脚本的通用性 |
| SQL 解析、校验、语句拆分 | 不在 | 拼接仅做 trim 与 `;` 补齐；语句拆分属运行期（`FlywayScripts`） |
| 增量合并 / checksum / 历史管理 | 不在 | 每次执行覆盖输出文件；无记录表、无校验和比对 |
| 跨模块聚合（兄弟模块 / 整仓 SQL 并入同一产物） | 刻意不支持 | 默认 includes 为本模块相对路径 `src/main/resources/db/init/**/*.sql`；源码注释明确收窄缘由（见 §2.3），父工程（`packaging=pom`）无 `db/init` 时按 `flyway.failIfNoScripts` 处理（默认 `true` 构建失败，`false` 时仅告警跳过） |
| `mode=single` 下的版本化命名与 init/initdata 分类 | 不做 | `V{版本}__{描述}.sql` 命名与两组分类只在 `mode=initdata` 下生成；`single` 输出固定文件名 `init-all.sql` |
| 迁移回滚 / down 脚本 | 源码未提供 | 两个源文件中无任何回滚相关逻辑 |
| 除 `.sql` 外的输入输出格式（压缩、打包等） | 无 | 只扫描 `*.sql` 输入、只写 `.sql` 输出 |
| 配置文件 / Spring Boot 属性绑定 | 无 | 只有 Maven 属性参数（`flyway.*`），不依赖 Spring / Spring Boot |
| SQL 语法检查 / 脚本内容校验 | 无 | 纯文本拼接，不分析内容语义 |

---

## 附：契约来源

本模块是构建期工具，产出物面向运行期 Flyway 口径：

- `com.chua.flyway.maven.GenerateInitSqlMojo`（goal `generate-init-sql`，goalPrefix `flyway`，defaultPhase `generate-resources`，`threadSafe = true`）
- `com.chua.flyway.maven.InitSqlMerger`（纯 JDK 合并器：扫描 / 合并 / 排序 / 版本与类型判定）
- 运行期消费方：`utils-support-flyway-starter` 的 `DataSourceFlyway`（`Flyway` SPI）+ common-starter 的 `FlywayScripts` / `FlywayHistory`（命名、分类、记录语义）