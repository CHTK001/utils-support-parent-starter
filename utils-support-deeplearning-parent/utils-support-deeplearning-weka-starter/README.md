# utils-support-deeplearning-weka-starter

Weka 随机森林建模模块：以「业务数据对象进、预测与评估结果出」的方式，封装**分类 / 回归 / 特征重要性**三类场景。

- 坐标：`com.chua:utils-support-deeplearning-weka-starter:4.0.0.51`（版本继承自 `utils-support-deeplearning-parent`；核心类自 `4.0.0.42` 起提供）
- 包名：`com.chua.deeplearning.support.weka`（子包 `data` / `rf` / `result`）
- 底层引擎：`nz.ac.waikato.cms.weka:weka-stable:3.8.7`（Java 8+ 字节码，项目已在 JDK 25 环境验证兼容）
- SPI：向 common-starter 的 `com.chua.common.support.task.classifier.ClassifierTask` 注册 `weka-random-forest` 实现

## 目录

1. 它是什么 / 不是什么
2. 核心 API 与速查表
3. 快速开始
4. 配置项
5. SPI 扩展点
6. 构建与依赖
7. 边界：这些能力不在本模块

## 它是什么 / 不是什么

| 它是 | 它不是 |
|---|---|
| 基于 Weka `weka.classifiers.trees.RandomForest` 的随机森林封装（分类 / 回归 / 特征重要性） | 不是 Weka 全家桶：不含 J48 决策树、SMO、朴素贝叶斯、聚类等其他 Weka 算法封装 |
| 表格式数据建模门面：`List<Map<String, Object>>` 行数据 + `FeatureColumn` 特征列声明 | 不是 DataFrame / 表计算库：不提供 SQL、Join、聚合等数据加工能力 |
| common-starter `ClassifierTask` SPI 的 `weka-random-forest` 实现 | 不是 Spring Boot starter：无自动装配、无配置文件绑定、无 Web 接口 |
| 可 JDK 序列化落盘 / 恢复的模型包装（`RandomForestModel`） | 不是模型仓库：不提供数据库存储、远程模型服务或版本管理 |
| K 折交叉验证评估（分类：准确率 / Kappa；回归：RMSE / MAE） | 不是 AutoML：不做自动调参、特征工程或模型选型 |

## 核心 API 与速查表

| 你想做 | 入口 |
|---|---|
| 声明一列特征 | `FeatureColumn.numeric("age")` / `FeatureColumn.categorical("city")`（均可附带描述参数，描述仅供业务展示） |
| 构建分类 / 回归训练数据 | `WekaInstanceData.classification(features, "label", rows)` / `WekaInstanceData.regression(features, "target", rows)` |
| 从 CSV 导入数据 | `WekaCsvLoader.load(Path.of("data.csv"))`，再链式 `.withLabelColumn("label")` 或 `.withTargetColumn("target")` |
| 训练分类模型 | `new WekaRandomForestClassifier().train(data, options)` → `RandomForestModel` |
| 训练回归模型 | `new WekaRandomForestRegressor().train(data, options)` → `RandomForestModel` |
| 单条 / 批量预测 | `classifier.predict(model, row)`、`classifier.predictBatch(model, rows)`（回归器同名方法，返回 `RegressionResult`） |
| 交叉验证评估 | `classifier.evaluate(model, data)` 或 `evaluate(model, data, numFolds)`；分类取 `accuracyPct()` / `kappa()`，回归取 `rmse()` / `mae()` |
| 特征重要性 | `new WekaRandomForestFeatureImportance().analyze(data, options)` → `List<FeatureImportance>` |
| 模型落盘 / 恢复 | `model.save(Path)` / `RandomForestModel.load(Path)`（JDK 序列化） |
| 走 SPI（免声明特征列） | `new WekaRandomForestClassifier().train("label", samples)` → `ClassifierTask.Model`，再 `predict` / `predictBatch` / `evaluate` / `save` |

数据行约定：每行一个 `Map<String, Object>`（列名 -> 值）。数值列传 `Number` 或可解析为数值的字符串；类别列 / 标签列传 `String`；缺失传 `null`（内部按 `WekaInstanceData.MISSING_VALUE`，即 `Double.NaN` 处理；预测行出现训练时未见的类别取值也按缺失处理）。

统一异常：参数非法、数据不满足建模条件或 Weka 底层失败时抛 `WekaException`（`RuntimeException`，保留原始 cause）。

## 快速开始

最短可运行示例（分类场景）：

```java
import com.chua.deeplearning.support.weka.data.FeatureColumn;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import com.chua.deeplearning.support.weka.rf.RandomForestModel;
import com.chua.deeplearning.support.weka.rf.RandomForestOptions;
import com.chua.deeplearning.support.weka.rf.WekaRandomForestClassifier;
import com.chua.deeplearning.support.weka.result.ClassificationResult;
import com.chua.deeplearning.support.weka.result.EvaluationReport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// ① 特征列 + 行数据 + 标签列
List<FeatureColumn> features = List.of(
        FeatureColumn.numeric("req_count"),
        FeatureColumn.categorical("country"));
List<Map<String, Object>> rows = List.of(
        Map.of("req_count", 10,  "country", "CN", "label", "normal"),
        Map.of("req_count", 800, "country", "BR", "label", "attack"),
        Map.of("req_count", 900, "country", "US", "label", "attack"),
        Map.of("req_count", 20,  "country", "DE", "label", "normal"));
WekaInstanceData data = WekaInstanceData.classification(features, "label", rows);

// ② 训练（options 传 null 时使用默认参数）
WekaRandomForestClassifier classifier = new WekaRandomForestClassifier();
RandomForestModel model = classifier.train(data, RandomForestOptions.defaults());

// ③ 预测
ClassificationResult r = classifier.predict(model, Map.of("req_count", 850, "country", "BR"));
r.label();           // 预测标签
r.confidence();      // 置信度（0.0 ~ 1.0）
r.probabilities();   // 各类别概率分布

// ④ 评估（K 折交叉验证，默认 10 折；本例 4 行数据自动按 4 折）
EvaluationReport report = classifier.evaluate(model, data);
report.accuracyPct(); // 准确率（%）
report.kappa();       // Kappa 一致性系数

// ⑤ 落盘 / 恢复
model.save(Path.of("rf-model.ser"));
RandomForestModel loaded = RandomForestModel.load(Path.of("rf-model.ser"));
```

回归场景：把 ① 换成 `WekaInstanceData.regression(features, "target", rows)`（目标列须为数值），分类器换成 `WekaRandomForestRegressor`；预测结果取 `RegressionResult.predictedValue()`，评估报告取 `rmse()` / `mae()`。

## 配置项

**无 Spring 配置项。** 本模块不是 Spring Boot starter：pom 中不含任何 Spring 依赖，也不存在 `application.yml` 配置键、环境变量或 `@ConfigurationProperties` 前缀。所有参数均通过编程式对象传入：

| 参数 | 位置 | 默认值 | 说明 |
|---|---|---|---|
| `numTrees` | `RandomForestOptions` | 10 | 树的数量（Bagging 迭代次数） |
| `seed` | `RandomForestOptions` | 1 | 随机种子；交叉验证的随机源也取自它 |
| `bagSizePercent` | `RandomForestOptions` | 100 | Bagging 采样比例（1~100；100 = 全量重采样，即 Weka 默认） |
| `numFeatures` | `RandomForestOptions` | 0 | 每棵树分裂时的候选特征数；0 = 自动取 sqrt(总特征数) |
| `maxDepth` | `RandomForestOptions` | 0 | 最大树深；0 = 不限制 |
| `breakTiesRandomly` | `RandomForestOptions` | false | 候选特征得分并列时是否随机选择 |
| `hasHeader` | `WekaCsvLoader.load(path, hasHeader)` | true | CSV 首行是否为表头；false 时列名按 `col_0`、`col_1` 生成 |
| `numFolds` | `evaluate(model, data, numFolds)` | 10 | 交叉验证折数；实际取 `min(numFolds, 实例数)` 且不低于 2，实例数不足 2 时抛 `WekaException` |

CSV 解析行为：按 UTF-8 读取，自动去除表头 BOM；逗号分隔，支持双引号包裹与 `""` 转义；某列全部非空值均可解析为数值时推断为数值列，否则为类别列；空单元格视为缺失（`null`）。

## SPI 扩展点

本模块通过 common-starter 的 SPI 框架暴露实现，注册文件为
`src/main/resources/META-INF/extensions/com.chua.common.support.task.classifier.ClassifierTask`：

```properties
weka-random-forest=com.chua.deeplearning.support.weka.rf.WekaRandomForestClassifier
```

调用方只依赖 `ClassifierTask` 接口即可使用，无需手工声明特征列（类型自动推断）：

```java
ClassifierTask task = new WekaRandomForestClassifier();      // SPI 扩展名: weka-random-forest
ClassifierTask.Model model = task.train("label", samples);   // samples 至少 2 行
ClassifierTask.Result r = model.predict(row);                // label / confidence / probabilities
ClassifierTask.Report report = model.evaluate(samples);      // numInstances / numFolds / accuracyPct / kappa
model.save(path);
```

SPI 行为约定（来自 `ClassifierTask` 契约与本模块实现）：

- `train(labelColumn, samples)`：标签列名非空白、样本至少 2 行，否则抛 `WekaException`；标签需有 2 种以上取值，否则 Weka 训练失败（同样包装为 `WekaException`）。
- 特征类型推断：某列全部非空值均为 `Number` 或可解析为数值的字符串 → 数值列，否则为类别列；列顺序按样本中首次出现顺序，标签列被排除。
- `Model.evaluate(samples)`：K 折交叉验证（默认 10 折，折数随数据量收缩、下限 2），返回分类指标。
- `Model` 实现随 SPI 契约整体支持 JDK 序列化，可经 `save(Path)` 落盘。

自定义扩展：如需接入其他分类算法，实现 common-starter 的 `ClassifierTask` 接口并在自己的 `META-INF/extensions/` 注册文件中声明扩展名即可；本模块本身不提供回归、时间序列等其他 SPI 契约的实现。

## 构建与依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-weka-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

| 依赖 | 说明 |
|---|---|
| `com.chua:utils-support-common-starter` | 公共工具层：SPI 注解、`ClassifierTask` 契约、集合包装等 |
| `nz.ac.waikato.cms.weka:weka-stable:3.8.7` | Weka 引擎；官方文档 https://www.cs.waikato.ac.nz/ml/weka/ ，源码 https://github.com/weka/weka |
| `org.projectlombok:lombok`（provided） | 编译期注解处理器，由 `utils-support-parent-starter` 统一配置 annotationProcessorPaths |

编译命令（在模块目录或父工程下执行）：

```bash
mvn -o -q compile -DskipTests
```

## 边界：这些能力不在本模块

- **其他 Weka 算法**：仅封装 `weka.classifiers.trees.RandomForest`；J48、SMO、朴素贝叶斯、聚类等封装源码未提供。
- **神经网络 / 深度学习推理**：ONNX、TensorFlow、PyTorch、Paddle、Ollama 等属于同父工程 `utils-support-deeplearning-parent` 下的兄弟模块，不在本模块。
- **Spring 自动装配 / Web 接口**：无自动配置类、无 Controller、无 REST 端点；一切能力通过 Java API 使用。
- **外部配置文件 / 配置中心**：无 `application.yml` 键位、无环境变量约定；参数只经 `RandomForestOptions` 等编程式对象传入。
- **数据工程**：不提供数据清洗、特征工程管道与数据标注；CSV 之外的数据源需调用方自行组装为 `List<Map<String, Object>>` 行数据。
- **模型管理**：`save` / `load` 仅覆盖本地文件的 JDK 序列化；无数据库持久化、模型仓库、在线推理服务或灰度发布能力。
- **增量 / 流式学习**：模型为批量训练（`buildClassifier`）产物，不支持在线增量训练或流式更新（源码未提供）。
- **回归场景的 SPI**：`ClassifierTask` SPI 仅登记了分类实现；回归只能通过 `WekaRandomForestRegressor` 直接调用。
