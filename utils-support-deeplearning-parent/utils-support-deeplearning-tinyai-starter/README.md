# utils-support-deeplearning-tinyai-starter

> utils 体系深度学习家族中的**本地纯 Java 神经网络分类模块**：基于 leavesfly/TinyAI 的 MLP，对表格数据完成二分类 / 多分类的训练、预测、评估、K 折交叉验证、增量微调与模型落盘，并以 SPI 名称 `tinyai-mlp` 接入统一的 `ClassifierTask` 分类任务口。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 POM `maven.compiler.source/target=25`）｜包名：`com.chua.deeplearning.support.tinyai`（全部公开类型 `@since 4.0.0.42`）

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-tinyai-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块 POM 描述：`TinyAI 本地神经网络分类训练：基于 leavesfly/TinyAI 的 MLP 分类（SPI classifier=tinyai-mlp）`。父模块：`utils-support-deeplearning-parent`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 能力 | 表格数据的 MLP 二分类 / 多分类：训练、单条与批量预测、评估（准确率 + Kappa）、K 折交叉验证、增量微调、模型 JDK 序列化落盘 / 恢复 | 不做回归 / 聚类 / 排序，不做 NLP / CV / 语音 / 生成式任务 |
| 引擎 | 基于 leavesfly/TinyAI（`io.leavesfly.tinyai`，源码 `1.0-SNAPSHOT` 本地安装），手写训练循环（`SoftmaxCrossEntropy` 损失 + `Adam` 优化器） | 不用 TinyAI 自带 `Trainer`（其收尾会调 `Monitor.plot()` 弹 Swing 绘图窗口，CI / 服务端会卡住） |
| 接入 | 实现 `ClassifierTask` SPI（接口定义在 `utils-support-common-starter`），扩展名 `tinyai-mlp`；也提供直接 `new TinyAiMlpClassifier()` 的普通 API | 不含 REST 端点、不依赖 Spring、无自动装配类、无数据库 / 持久层 |
| 运行形态 | 纯 Java 进程内计算，无外部服务依赖 | 无 GPU / 分布式训练、无超参搜索、无模型格式转换（如 ONNX 导出） |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 声明训练数据 | `TinyAiTrainData.classification("label").numeric("age").categorical("city").addRow(...)` | 特征列 + 标签列 + 行数据三件套，链式 Builder |
| JSON 行数据入口 | `TinyAiTrainData.fromJson("label", json)` / `TinyAiTrainData.infer("label", rows)` | 自动推断数值 / 类别特征列 |
| 训练模型 | `new TinyAiMlpClassifier().train(data, MlpOptions.defaults())` | 返回 `TinyAiMlpModel` |
| 预测 | `classifier.predict(model, row)`、`classifier.predictBatch(model, rows)`，或 `model.predict(row)` | 返回 `ClassificationResult`：`label()` / `labelIndex()` / `confidence()` / `probabilities()` |
| 评估 | `classifier.evaluate(model, data)`；交叉验证用 `classifier.crossValidate(data, options, numFolds)` | `EvaluationReport`：`accuracyPct()`（%）+ `kappa()` |
| 增量训练 | `classifier.finetune(model, deltaData, MlpOptions.defaults())` | 原地更新权重并返回同一模型实例 |
| 落盘 / 恢复 | `model.save(Path)` / `TinyAiMlpModel.load(Path)` | JDK 序列化（权重 + 数据域快照 + 训练参数） |
| SPI 方式 | `ServiceProvider.of(ClassifierTask.class).getExtension("tinyai-mlp")` | `task.train(labelColumn, rows)` 传裸行数据即可 |

### 2.2 类型一览

| 类型 | 职责 |
| --- | --- |
| `TinyAiMlpClassifier` | 场景入口（`@Spi("tinyai-mlp")`）：train / finetune / predict / predictBatch / evaluate / crossValidate |
| `TinyAiTrainData` | 用户侧训练数据入口：`classification(...)` Builder、`infer` / `fromJson` 自动推断、`toDomain()` 转内部编码口径 |
| `TinyAiMlpModel` | 模型包装：`predict`、`save` / `load`、`getModel` / `getOptions` / `getDomain` / `getCreatedAtMillis`；内含 `ClassificationResult` record |
| `ModelDomain` | 数据域 record（特征列 + 标签列名 + 标签有序取值表）：`inputSize()` / `encode(row)` / `labelIndex(label)` / `categorySnapshot()` |
| `MlpOptions` | 训练参数 record：`epochs` / `batchSize` / `learningRate` / `hiddenLayers` / `seed`；`defaults()` 与 `testDefaults()` |
| `FeatureColumn` | 特征列定义：`numeric(name)` / `categorical(name)`、`name()` / `type()` / `isNumeric()` / `categories()` |
| `TinyAiEngine` | 训练引擎：`train` / `finetune` / `infer` / `accuracy` / `buildModel` / `toDataset`（实现细节，业务侧一般不直接用） |
| `TinyAiException` | 模块运行时异常：训练数据非法、特征编码失败、训练 / 预测 / 评估失败等均抛本异常 |

### 2.3 TinyAiMlpClassifier 方法

| 方法 | 说明 |
| --- | --- |
| `train(TinyAiTrainData data, MlpOptions options)` | 用户侧首选训练入口；`options` 传 `null` 用默认值 |
| `train(ModelDomain domain, List<Map<String,Object>> samples, MlpOptions options)` | 显式样本版训练 |
| `finetune(TinyAiMlpModel model, TinyAiTrainData data, MlpOptions options)` | 增量训练（TrainData 版）：先校验与模型数据域兼容 |
| `finetune(TinyAiMlpModel model, List<Map<String,Object>> newSamples, MlpOptions options)` | 增量训练（样本行版） |
| `predict(TinyAiMlpModel model, Map<String,Object> row)` | 单条预测，行可缺省标签列 |
| `predictBatch(TinyAiMlpModel model, List<Map<String,Object>> rows)` | 批量预测，保持输入顺序 |
| `evaluate(TinyAiMlpModel model, TinyAiTrainData data)` / `evaluate(model, samples)` | 复测口径：准确率 + Kappa（由混淆矩阵计算） |
| `crossValidate(TinyAiTrainData data, MlpOptions options, int numFolds)` / `crossValidate(domain, samples, options, numFolds)` | K 折交叉验证：每折独立训练评估，汇总准确率 |
| `train(String labelColumn, List<Map<String,Object>> samples)` | `ClassifierTask` SPI 入口：内部走 `TinyAiTrainData.infer` 自动推断 |

`EvaluationReport`（record）字段：`regression`（分类恒 `false`）、`numInstances`、`numFolds`（复测口径为 1）、`accuracyPct`（%）、`kappa`、`rmse` / `mae`（分类为 0）、`elapsedMillis`；`snapshot()` 返回含 `numInstances` / `numFolds` / `accuracyPct` / `kappa` 的 Map 快照。

### 2.4 训练参数 MlpOptions

| 字段 | 含义 | `defaults()` | `testDefaults()` |
| --- | --- | --- | --- |
| `epochs` | 训练轮数（≥ 1） | 200 | 20 |
| `batchSize` | 批大小（≥ 1，超过样本数时按样本数处理） | 32 | 8 |
| `learningRate` | 学习率（> 0） | 0.01 | 0.05 |
| `hiddenLayers` | 隐藏层宽度（顺序即网络深度，每层 ≥ 1） | {64, 32} | {16} |
| `seed` | 随机种子 | 42 | 42 |

网络结构固定为「输入层 → hiddenLayers 层隐藏层（ReLU）→ 输出层（类别数）」；参数非法（轮数 < 1、批 < 1、学习率 ≤ 0、隐藏层为空或宽度 < 1）在构造时即抛 `TinyAiException`。

---

## 3. 快速开始

### 3.1 最短可运行示例

```java
import com.chua.deeplearning.support.tinyai.MlpOptions;
import com.chua.deeplearning.support.tinyai.TinyAiMlpClassifier;
import com.chua.deeplearning.support.tinyai.TinyAiMlpModel;
import com.chua.deeplearning.support.tinyai.TinyAiTrainData;
import java.nio.file.Path;
import java.util.Map;

// 1. 训练数据：标签列 + 特征列（数值 / 类别）+ 行数据（至少 2 行、2 种标签）
TinyAiTrainData data = TinyAiTrainData.classification("label")
        .numeric("age").categorical("city")
        .addRow(Map.of("age", 35, "city", "北京", "label", "high"))
        .addRow(Map.of("age", 22, "city", "上海", "label", "low"))
        .addRow(Map.of("age", 40, "city", "北京", "label", "high"))
        .addRow(Map.of("age", 20, "city", "上海", "label", "low"));

// 2. 训练（testDefaults 为小参数，演示更快；生产用 MlpOptions.defaults()）
TinyAiMlpClassifier classifier = new TinyAiMlpClassifier();
TinyAiMlpModel model = classifier.train(data, MlpOptions.testDefaults());

// 3. 预测（行可缺省标签列；未见类别取值编码为全 0，不抛错）
var one = classifier.predict(model, Map.of("age", 41, "city", "广州"));
one.label();          // 预测标签
one.confidence();     // 置信度（0~1）
one.probabilities();  // 各类别概率分布（顺序与标签表一致）

// 4. 评估：准确率（%）+ Kappa
var report = classifier.evaluate(model, data);
report.accuracyPct();

// 5. 落盘 / 恢复（JDK 序列化）
model.save(Path.of("tinyai-mlp.ser"));
TinyAiMlpModel loaded = TinyAiMlpModel.load(Path.of("tinyai-mlp.ser"));
```

### 3.2 JSON 行数据（自动推断特征列）

```java
TinyAiTrainData data = TinyAiTrainData.fromJson("label",
        "[{\"age\":35,\"city\":\"北京\",\"label\":\"high\"},{\"age\":22,\"city\":\"上海\",\"label\":\"low\"}]");
TinyAiMlpModel model = new TinyAiMlpClassifier().train(data, MlpOptions.defaults());
```

JSON 为对象数组；除标签列外的列自动推断类型：该列全部非空值均为 `Number`（或可解析为数值的字符串）→ 数值列，否则类别列。

### 3.3 SPI 方式（裸行数据）

```java
ClassifierTask task = ServiceProvider.of(ClassifierTask.class).getExtension("tinyai-mlp");
ClassifierTask.Model model = task.train("label", rows);   // rows: List<Map<String,Object>>
ClassifierTask.Result result = model.predict(row);
ClassifierTask.Report report = model.evaluate(rows);
model.save(path);
```

SPI 侧内部走同一套 `TinyAiTrainData` 转化，与显式 TrainData 入口同口径。

### 3.4 关键行为与边界值

| 规则 | 说明 |
| --- | --- |
| 数据校验 | 特征列非空、标签列名非空白、样本 ≥ 2 行、标签 ≥ 2 种取值，违反抛 `TinyAiException` |
| 特征编码 | 数值列占 1 维（缺失按 0）；类别列按训练期收集的取值表 one-hot 展开，预测期出现训练期未见取值时全 0（不抛错，保证线上可预测） |
| 标签口径 | 标签表按样本首次出现顺序固定；训练按标签下标编码，推理返回与标签表顺序一致的概率向量 |
| 数据集切分 | 内部 `TabularDataset` 按 8/1/1 切分训练 / 测试 / 验证；`batchSize` 钳到样本数；保留余数 batch（小样本可用）；每轮 shuffle |
| 增量训练兼容性 | 标签列、特征列数、各特征列名与类型须与模型一致；新样本标签须落在原标签表内（新标签抛错——输出层维度固定）；类别列新取值允许（全 0 编码） |
| 交叉验证 | 折数钳到 [2, 样本数]；每折独立训练评估；至少 2 行有效样本；Kappa 上报 0（不产出完整混淆矩阵），以准确率为主 |
| 评估口径 | 空样本抛错；逐行评估时跳过 null 行与标签缺失 / 未知的行；无有效标签行抛 `TinyAiException` |

---

## 4. 配置项

**无。** 依据：

- `src/main/resources` 下仅有 SPI 注册与 native-image 反射配置（`META-INF/services/`、`META-INF/extensions/`、`META-INF/native-image/`），没有任何 `application*.properties` / `application*.yml` 配置文件；
- 源码中无 `@ConfigurationProperties` / `@Value` 等配置注入，也无环境变量 / 系统属性键约定；
- 全部训练参数通过 `MlpOptions` 以代码显式传入（`epochs` / `batchSize` / `learningRate` / `hiddenLayers` / `seed`），不读取任何外部配置。

---

## 5. SPI 扩展点

本模块是 `ClassifierTask` SPI 的**实现方**，不是扩展点提供方。

- 接口：`com.chua.common.support.task.classifier.ClassifierTask`（定义在 `utils-support-common-starter`）
- 实现类：`com.chua.deeplearning.support.tinyai.TinyAiMlpClassifier`（注解 `@Spi("tinyai-mlp")`）
- 注册文件（模块内）：
  - `src/main/resources/META-INF/services/com.chua.common.support.task.classifier.ClassifierTask` → `com.chua.deeplearning.support.tinyai.TinyAiMlpClassifier`
  - `src/main/resources/META-INF/extensions/com.chua.common.support.task.classifier.ClassifierTask` → `tinyai-mlp=com.chua.deeplearning.support.tinyai.TinyAiMlpClassifier`
- GraalVM native-image：`src/main/resources/META-INF/native-image/com.chua/utils-support-deeplearning-tinyai-starter/reflect-config.json` 注册了 `TinyAiMlpClassifier`、`TinyAiMlpModel`、`MlpOptions`、`ModelDomain`、`TinyAiTrainData`、`FeatureColumn` 的全量反射元数据。

模块内没有供外部实现的新扩展点（无额外 SPI 接口 / 抽象类）；如需替换分类实现，请在自己的模块实现 `ClassifierTask` 并注册新的 SPI 名称。

---

## 6. 构建与依赖

### 6.1 依赖清单（模块 POM）

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 由父 POM 管理 | SPI 契约（`ClassifierTask`）、集合工具等 |
| `io.leavesfly.tinyai:tinyai-deeplearning-ml` | `1.0-SNAPSHOT` | TinyAI 训练 / 模型 / 数据集 |
| `io.leavesfly.tinyai:tinyai-deeplearning-nnet` | `1.0-SNAPSHOT` | 网络层（`Sequential` / `Linear` / `ReLU`） |
| `io.leavesfly.tinyai:tinyai-deeplearning-func` | `1.0-SNAPSHOT` | 自动微分 `Variable` |
| `io.leavesfly.tinyai:tinyai-deeplearning-ndarr` | `1.0-SNAPSHOT` | 张量 `NdArray` / `Shape` |
| `org.slf4j:slf4j-api` | 由父 POM 管理 | 日志门面 |
| `org.projectlombok:lombok` | provided | — |

Jackson（`TinyAiTrainData.fromJson` 的 JSON 解析所用）经 `utils-support-common-starter` 透传，模块 POM 未直接声明。

### 6.2 TinyAI 构件获取（重要）

TinyAI 上游**无中央仓库构件**，按模块 POM 注释处理：

1. 用 leavesfly/TinyAI 源码本地安装（版本 `1.0-SNAPSHOT`，安装到本地仓库，如 `D:\maven-repo`），仅用 deeplearning 4 件套（`ml` / `nnet` / `func` / `ndarr`）+ 父 POM；
2. 安装前需修复上游 `ReLu.java` 的大小写问题；
3. 父 POM（`utils-support-deeplearning-parent`）注释标注：因 Aliyun 私服 `tinyai:1.0-SNAPSHOT` 不可达（401），本模块**暂不进入聚合构建**——需要时在模块目录单独构建。

### 6.3 编译验证

```bash
cd utils-support-parent-starter/utils-support-deeplearning-parent/utils-support-deeplearning-tinyai-starter
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| ONNX / sherpa-onnx / PyTorch / Paddle / TensorFlow / MXNet / AgentScope / LangChain4j / OpenCV / safetensors / 3D / gpu-llama3 / Ollama / laya / DL4J 等其它深度学习引擎 | 同仓 `utils-support-deeplearning-parent` 下对应的 `utils-support-deeplearning-*-starter` 模块 |
| Weka 实现的同类表格分类（本模块 `ModelDomain` / `FeatureColumn` / `EvaluationReport` 与 weka 侧同构） | `utils-support-deeplearning-weka-starter` |
| `ClassifierTask` 等 SPI 接口定义与 `ServiceProvider` 装配 | `utils-support-common-starter` |
| 回归 / 聚类 / 排序 / 推荐、NLP / CV / 语音 / 生成式任务 | 不在本模块（本模块仅表格数据 MLP 分类） |
| GPU 加速、分布式训练、超参搜索 / 自动调参、特征工程管道、模型格式转换（如导出 ONNX） | 不在本模块 |
| Web 端点 / REST API、Spring 自动装配、数据库持久化 | 不在本模块（纯进程内 Java API + SPI） |
