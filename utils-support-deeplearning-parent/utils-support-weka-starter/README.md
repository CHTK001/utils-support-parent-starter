# utils-support-weka-starter

基于 Weka（`nz.ac.waikato.cms.weka:weka-stable:3.8.6`）的随机森林建模模块。
提供**分类 / 回归 / 特征重要性**三类场景对象，输入业务数据对象，输出预测与评估结果。

- 包名：`com.chua.deeplearning.support.weka`
- SPI：`com.chua.common.support.task.classifier.ClassifierTask`（common-starter），本模块提供 `weka-random-forest` 实现
- 依赖：`utils-support-common-starter`、`weka-stable`（Java 8+ 字节码，JDK 25 环境已验证）

## 数据模型：三件套

| 概念 | 类型 | 含义 |
|---|---|---|
| `features` | `List<FeatureColumn>` | **字段声明**（列名 + 类型：数值/类别），相当于表结构 |
| `rows` | `List<Map<String, Object>>` | **数据行**：每行一个对象（列名 -> 值），一行 = 一个样本（如一个 IP） |
| 标签 / 目标列 | 字符串列名 | 答案列：分类用名义值（`label`），回归用数值（`target`） |

行取值规则：数值列传 `Number`（或可解析字符串），类别 / 标签列传 `String`，缺失传 `null`。
特征类型可用 `FeatureColumn` 手工声明，也可通过 SPI 方式由模块自动推断。

## 使用流程

### 1. 分类（判断某对象是否属于某类，如：IP 是否攻击）

```java
// ① 数据准备：特征列 + 行数据 + 标签列
List<FeatureColumn> features = List.of(
        FeatureColumn.numeric("req_count"),
        FeatureColumn.numeric("ua_count"),
        FeatureColumn.categorical("country"));
List<Map<String, Object>> rows = ...;   // 每个对象一行，含 label 列
WekaInstanceData data = WekaInstanceData.classification(features, "label", rows);

// ② 训练
WekaRandomForestClassifier classifier = new WekaRandomForestClassifier();
RandomForestModel model = classifier.train(data, RandomForestOptions.defaults());

// ③ 预测新数据
ClassificationResult r = classifier.predict(model, Map.of("req_count", 800, "ua_count", 30, "country", "BR"));
r.label();          // 预测标签
r.probabilities(); // 各类别概率

// ④ 评估（K 折交叉验证，默认 10 折）
EvaluationReport report = classifier.evaluate(model, data);
report.accuracyPct(); report.kappa();

// ⑤ 模型落盘 / 恢复
model.save(Path.of("rf.ser"));
RandomForestModel loaded = RandomForestModel.load(Path.of("rf.ser"));
```

### 2. 回归（预测数值，如：销量、金额）

```java
WekaInstanceData data = WekaInstanceData.regression(features, "target", rows);
WekaRandomForestRegressor regressor = new WekaRandomForestRegressor();
RandomForestModel model = regressor.train(data, null);
RegressionResult r = regressor.predict(model, Map.of("price", 12.5));
r.predictedValue();
EvaluationReport report = regressor.evaluate(model, data); // rmse / mae
```

### 3. 特征重要性（特征筛选 / 可解释性）

```java
List<FeatureImportance> list = new WekaRandomForestFeatureImportance()
        .analyze(data, RandomForestOptions.defaults());
list.get(0); // rank=1 的最重要特征（平均不纯度下降 + 归一化 + 排名）
```

### 4. SPI 方式（不手工声明特征列，自动类型推断）

```java
ClassifierTask task = new WekaRandomForestClassifier(); // @Spi("weka-random-forest")
ClassifierTask.Model model = task.train("label", rows);
ClassifierTask.Result r = model.predict(row);
ClassifierTask.Report report = model.evaluate(rows);
model.save(path);
```

## CSV 数据

```java
WekaInstanceData data = WekaCsvLoader.load(Path.of("data.csv")).withLabelColumn("label");
```
自动按逗号解析（支持引号包裹），全部非空值可解析为数值 → 数值列，否则类别列。

## 典型场景：请求 IP 异常分析

1. 从访问日志按 IP 聚合统计特征（请求数、UA 数、404 比例、地域数等）→ 每个 IP 一行 `Map`
2. 有人工标注时走分类（`label` = 正常/攻击）；无标注可先按规则筛可疑样本再半监督
3. `WekaRandomForestClassifier` 训练 + 预测 + `EvaluationReport` 评估
4. 特征重要性（`WekaRandomForestFeatureImportance`）找出最具判别力的行为特征

## 注意

- 数据量需 >= 2 行（SPI `train` 校验）；默认 10 折交叉验证时数据量不足 10 行会按 2 折降级，标签需有 2 种以上取值
- `RandomForestOptions`：`numTrees`（树数）、`numFeatures`（每树候选特征数，0=自动）、`maxDepth`、`bagSizePercent`（100=全量重采样）、`seed`（可复现）
