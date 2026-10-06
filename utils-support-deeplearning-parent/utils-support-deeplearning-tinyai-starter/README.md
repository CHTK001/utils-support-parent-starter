# utils-support-deeplearning-tinyai-starter

基于 leavesfly/TinyAI（源码本地安装 `io.leavesfly.tinyai:1.0-SNAPSHOT`，仅用 deeplearning 4 件套）的 MLP 神经网络分类训练模块。

- 包名：`com.chua.deeplearning.support.tinyai`
- SPI：`com.chua.common.support.task.classifier.ClassifierTask`（common-starter），本模块提供 `tinyai-mlp` 实现
- 依赖：`utils-support-common-starter`、TinyAI 4 件套（`ml/nnet/func/ndarr`）、Jackson（经 common-starter 透传，供 JSON 行数据解析）

## 数据模型：训练数据三件套

| 概念 | 类型 | 含义 |
|---|---|---|
| `features` | `List<FeatureColumn>` | **字段声明**（列名 + 类型：数值/类别），相当于表结构 |
| `labelColumn` | 字符串列名 | 答案列（名义值，如 `label`） |
| `rows` | `List<Map<String, Object>>` | **数据行**：每行一个对象（列名 -> 值），一行 = 一个样本 |

行取值规则：数值列传 `Number`（或可解析字符串），类别/标签列传 `String`，缺失传 `null`（数值缺失按 0，类别未见取值 one-hot 全 0）。

## 使用流程

### 1. 训练（TrainData 对象版，用户侧首选）

```java
TinyAiTrainData data = TinyAiTrainData.classification("label")
        .numeric("x1").numeric("x2")
        .addRow(Map.of("x1", 0.1, "x2", 0.2, "label", "low"))
        .addRow(Map.of("x1", 0.9, "x2", 0.8, "label", "high"));
TinyAiMlpModel model = new TinyAiMlpClassifier().train(data, MlpOptions.defaults());
```

### 2. JSON 行数据（纯 JSON 用户入口，内部自动推断特征列）

```java
TinyAiTrainData data = TinyAiTrainData.fromJson("label",
        "[{\"x1\":0.1,\"x2\":0.2,\"label\":\"low\"},{\"x1\":0.9,\"x2\":0.8,\"label\":\"high\"}]");
TinyAiMlpModel model = new TinyAiMlpClassifier().train(data, MlpOptions.defaults());
```

### 3. 预测 / 评估 / 落盘

```java
var one = classifier.predict(model, Map.of("x1", 0.41, "x2", 0.5));
one.label();          // 预测标签
one.probabilities(); // 各类别概率

var report = classifier.evaluate(model, data);
report.accuracyPct(); // 准确率（%）

model.save(Path.of("tinyai-mlp.ser"));
TinyAiMlpModel loaded = TinyAiMlpModel.load(Path.of("tinyai-mlp.ser"));
```

### 4. 增量训练（新标注数据追加几轮）

```java
classifier.finetune(model, deltaData, MlpOptions.defaults());
```

增量数据特征口径须与模型一致（同名/同类型/同顺序），标签须落在原标签表内；类别列新取值允许（全 0 编码），新标签不允许（输出层维度固定）。

### 5. SPI 方式（裸行数据，内部走同一套 TrainData 转化）

```java
ClassifierTask task = ServiceProvider.of(ClassifierTask.class).getExtension("tinyai-mlp");
ClassifierTask.Model model = task.train("label", rows);
ClassifierTask.Result r = model.predict(row);
ClassifierTask.Report report = model.evaluate(rows);
model.save(path);
```

## 注意

- TinyAI 上游无中央仓库构件，需源码本地安装到 `D:\maven-repo`（`ndarr/func/nnet/ml` + 父 POM，版本 `1.0-SNAPSHOT`）；另需修复上游 `func/math/ReLu.java` 内 `public class ReLU` 大小写问题后安装。
- 训练循环为手写实现（不用上游 `Trainer`，其收尾调 `Monitor.plot()` 弹 Swing 窗口，CI/服务端会卡住）。
- 模型落盘为 JDK 序列化（含权重 + 数据域快照 + 训练参数），恢复后可直接预测，也可继续 `finetune`。
