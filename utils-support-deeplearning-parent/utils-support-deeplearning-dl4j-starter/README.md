# utils-support-deeplearning-dl4j-starter

> utils 深度学习体系中的 **DL4J 训练模块**：基于 ImageNet 预训练的 ResNet50 迁移学习模型，提供图片分类训练（含进度回调与最优模型保存），以及分类推理、2048 维特征提取与图片 1:1 比对。
>
> 版本：`4.0.0.51`｜JDK：`25`（仓库 `maven.compiler.source`/`maven.compiler.target` = `25`）｜包名：`com.chua.deeplearning.support.dl4j`（含子包 `train`、`infer`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 核心 API](#3-核心-api)
- [4. 快速开始](#4-快速开始)
- [5. 配置项](#5-配置项)
- [6. SPI 与扩展点](#6-spi-与扩展点)
- [7. 构建与依赖](#7-构建与依赖)
- [8. 边界：这些能力不在本模块](#8-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-dl4j-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 「DL4J / Deeplearning4j 训练模块：基于 ImageNet 预训练模型的迁移学习训练能力」（pom `description` 原文） | 不是通用深度学习抽象层（接口契约在 `utils-support-deeplearning-starter`） |
| 训练 | 以 ResNet50 为默认架构的迁移学习：替换 `fc1000` 输出层、按 epoch 训练、测试集 F1 评估、保存最优 F1 模型 | 不含 Vgg16 等其它架构的实现（`Trainer` javadoc 中仅作为可扩展方向提及） |
| 推理 | 图片分类（argMax）、2048 维特征提取（`flatten_1` 层）、1:1 余弦相似度比对 | 不含目标检测 / OCR / 语音 / 时序等能力（见第 8 章） |
| 集成形态 | 纯 Java API：工厂 + 链式管道 + 回调，可被上层当普通库依赖 | 无 Spring Boot 自动配置、无 HTTP 端点、无定时任务、无持久化 |
| 计算后端 | 默认 CPU：`nd4j-native-platform` | 不内置 CUDA 依赖；需 GPU 时把 `nd4j.backend` 换成 `nd4j-cuda-X-platform`（pom 注释口径） |

### 1.3 规模

- Java 源文件 **14** 个：根包 `dl4j` 9 个，`train` 2 个，`infer` 3 个。
- 移植来源（各类 javadoc 注明）：AIAS `2_training_platform` 的 `TrainResNet50` / `ResNet50Model` / `TrainArgument` 与对应推理能力。
- 无 `src/main/resources` 目录、无测试目录、无 SPI 注册文件。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 一行发起训练 | `TrainerFactory.fluent().data(root).model(zip).saveTo(dir).fit()` | 链式管道；超参数走 `TrainArgument.defaults()` |
| 手动控制训练 | `TrainerFactory.get().train(argument, modelPath, savePath, dataRootPath, listener)` | `Trainer` 完整签名，参数全部显式 |
| 默认超参数 | `TrainArgument.defaults()` | epoch=10、batchSize=2、nClasses=3、learningRate=1e-3、lrMomentum=0.9、trainPercent=70 |
| 训练进度回调 | `TrainListener` | `onStarted` / `onEpochCompleted` / `onFinished` / `onFailed`；不需要时传 `TrainListener.NOOP` |
| 从已有模型续训 | `ChainedTrainer.resume(path)` | 写入 `TrainArgument.resumeModelPath`，优先于预训练模型路径 |
| 替换训练器实现 | `TrainerFactory.setCurrent(trainer)` | 传 `null` 恢复默认的 `ResNet50TransferTrainer` |
| 图片分类推理 | `new ResNet50InferenceModel(modelPath, labels).classify(mat)` | 返回 `ClassPrediction`（类别名 + 置信度） |
| 特征提取 / 1:1 比对 | `ResNet50InferenceModel.extractFeature(mat)`、`compare(mat1, mat2)` | 2048 维特征；余弦相似度 &gt; 0.5 判定匹配 |

---

## 3. 核心 API

### 3.1 训练入口：`Trainer` 与 `TrainerFactory`

`com.chua.deeplearning.support.dl4j.Trainer` 是训练器接口，不同模型架构（ResNet50 / Vgg16 等）各自实现：

```java
public interface Trainer {
    /** 链式管道入口（default 方法）：等价 ChainedTrainer.of(this) */
    default ChainedTrainer fluent() { ... }

    /** 执行训练 */
    TrainResult train(TrainArgument argument,   // 训练超参数
                      String modelPath,         // 预训练模型路径（本地 zip 或目录）
                      String savePath,          // 模型保存路径（目录或完整文件路径）
                      String dataRootPath,      // 训练数据根目录（子目录为类别）
                      TrainListener listener    // 进度回调，可为 TrainListener.NOOP
    ) throws Exception;
}
```

`com.chua.deeplearning.support.dl4j.TrainerFactory` 是最终类（静态工厂，懒加载单例）：

| 成员 | 说明 |
| --- | --- |
| `TrainerFactory.get()` | 获取当前训练器；未设置时懒加载默认实现 `ResNet50TransferTrainer`（双重检查锁） |
| `TrainerFactory.setCurrent(Trainer)` | 替换当前实现（例如注入自定义实现）；传 `null` 恢复默认 |
| `TrainerFactory.fluent()` | 便捷入口，等价 `get().fluent()` |

### 3.2 链式训练管道：`ChainedTrainer`

`com.chua.deeplearning.support.dl4j.ChainedTrainer` 是不可变的一次性管道：每个链式方法都返回**新管道**，不影响源管道；`branch()` 可从当前管道派生分支做并行试验。

静态工厂：

| 方法 | 说明 |
| --- | --- |
| `ChainedTrainer.of()` | 默认训练器（`ResNet50TransferTrainer`）起步 |
| `ChainedTrainer.of(Trainer)` | 指定底层训练器（便于替换模型架构实现） |
| `ChainedTrainer.of(Trainer, TrainArgument, modelPath, savePath, dataRootPath)` | 完全自定义构造 |

链式配置方法（均返回新管道）：

| 方法 | 写入的目标 |
| --- | --- |
| `data(String)` | 训练数据根目录（子目录即类别名） |
| `saveTo(String)` | 模型保存路径 |
| `model(String)` | 预训练模型路径（训练时 `TrainArgument.resumeModelPath` 优先于它；与 `resume(String)` 至少提供一个本地模型路径，见 4.1） |
| `resume(String)` | 续训练模型路径（从已有模型继续微调），写 `TrainArgument.resumeModelPath` |
| `argument(TrainArgument)` | 整体覆盖超参数 |
| `epochs(int)` / `batchSize(int)` / `classifier(int)` | 迭代轮次 / 批次大小 / 分类数（输出层维度） |
| `learningRate(double)` / `momentum(double)` | 学习率 / 动量 |
| `trainPercent(int)` | 训练集占比（0~100，其余作测试集） |
| `listener(TrainListener)` | 进度回调 |

终端操作：

| 方法 | 说明 |
| --- | --- |
| `fit()` | 以当前配置执行一次训练；`data` / `saveTo` 缺失时抛 `IllegalStateException`（消息：`请先通过 .data(path) 指定训练数据根目录` / `请先通过 .saveTo(path) 指定模型保存路径`） |
| `fitUnchecked()` | 同 `fit()`，但把受检异常包装为 `TrainException`（运行时），便于回调 / 线程中调用 |

访问器：`dataRoot()`、`trainer()`、`arguments()`（返回超参数副本）。

### 3.3 训练超参数：`TrainArgument`

`com.chua.deeplearning.support.dl4j.TrainArgument`：`@Data` + `@Builder` 的可序列化配置对象。

| 字段 | 含义 | `defaults()` 默认值 |
| --- | --- | --- |
| `epoch` | 迭代周期（轮次） | `10` |
| `batchSize` | 批次大小 | `2` |
| `nClasses` | 分类数量（迁移学习输出层维度） | `3` |
| `classLabels` | 图像分类标签（训练完成后由数据目录自动推导并回填） | — |
| `detLabels` | 目标检测分类标签（预留） | — |
| `resumeModelPath` | 续训练模型路径；指定后从该模型继续微调（否则用预训练模型） | — |
| `learningRate` | 学习率 | `1e-3` |
| `lrMomentum` | 动量 | `0.9` |
| `trainPercent` | 训练集占比（0~100，剩余作为测试集） | `70` |

方法：

- `TrainArgument.defaults()`：返回上表默认配置。
- `TrainArgument.merge(TrainArgument source)`：把 `source` 中**非空字段**覆盖到当前对象（部分更新语义），返回自身；`source` 为 `null` 时原样返回。

### 3.4 进度与结果：`TrainListener` / `TrainProgress` / `TrainStatus` / `TrainResult` / `TrainException`

`TrainListener`（训练过程回调接口，4 个方法 + 空实现常量）：

| 方法 | 时机 |
| --- | --- |
| `onStarted(TrainProgress)` | 训练即将开始（加载数据、构建模型阶段） |
| `onEpochCompleted(TrainProgress)` | 每轮 epoch 训练完成 |
| `onFinished(TrainProgress)` | 训练成功完成 |
| `onFailed(TrainProgress, Throwable)` | 训练过程中发生异常而失败 |
| `TrainListener.NOOP` | 空实现常量，忽略所有回调 |

`TrainProgress`（进度快照，`@Data` + `@Accessors(chain = true)`，可序列化）：

| 字段 | 含义 |
| --- | --- |
| `status` | 训练状态（`TrainStatus`） |
| `currentEpoch` / `totalEpochs` | 当前轮次 / 总轮次数 |
| `stage` | 当前阶段描述（源码取值：`加载数据`、`构建模型`、`训练中`、`训练中 - epoch X/Y`、`训练完成`） |
| `progress` | 进度百分比（0~100） |
| `bestScore` | 当前最优 F1 值 |
| `error` | 错误信息（`FAILED` 时填充） |
| `modelPath` | 模型保存路径（训练完成时填充） |
| `baseModelPath` | 实际使用的基模型路径（显示是预训练还是续训模型，启动时填充） |
| `classLabels` | 类别标签串（训练完成后填充） |

`TrainStatus` 枚举：`PENDING`、`RUNNING`、`SUCCESS`、`FAILED`、`CANCELLED`。

`TrainResult`（训练结果，`@Data` + `@Accessors(chain = true)`，可序列化）：

| 字段 | 含义 |
| --- | --- |
| `success` | 是否训练成功 |
| `modelPath` | 模型保存路径 |
| `labels` | 类别标签列表（`List<String>`） |
| `bestScore` | 最优 F1 值 |
| `elapsedMs` | 训练耗时（毫秒） |
| `errorMessage` | 错误信息（失败时填充） |

`TrainException`：训练过程运行时异常（`RuntimeException`），构造器 `(String message)` 与 `(String message, Throwable cause)`，保留原始 cause。

### 3.5 默认训练实现：`ResNet50TransferTrainer` 与 `ResNet50Model`

`com.chua.deeplearning.support.dl4j.train.ResNet50TransferTrainer`（`Trainer` 的默认实现）核心流程：

1. **加载数据**（进度 5%）：校验数据目录存在，按 `trainPercent`（默认 70）划分训练/测试集，从目录名推导类别标签并回填 `classLabels`；
2. **构建模型**（进度 15%）：`resumeModelPath` 优先、否则用 `modelPath` 加载预训练 ResNet50，替换 `fc1000` 输出层为新的分类数；
3. **训练**（进度 15 + 70 × 已完成轮次占比）：每轮先跑完训练集，再在测试集上评估 F1；F1 创新高时立即用 `ModelSerializer.writeModel(graph, modelFile, true)` 保存模型；
4. **完成**（进度 100%）：返回 `TrainResult`（成功标志、模型路径、标签、最优 F1、耗时）。

补充行为：

- 每轮循环前检查 `Thread.currentThread().isInterrupted()`，被中断则置 `CANCELLED`、回调 `onFailed`，并返回 `success=false`、`errorMessage=训练被取消` 的结果。
- `savePath` 为目录（或以文件分隔符结尾）时，保存为目录下的 `NewResNet50.zip`（内部常量 `DEFAULT_MODEL_NAME`）；为完整文件路径时原样使用；父目录不存在会自动创建。
- 回调对象可为 `null`（等价不回调）；`ChainedTrainer` 侧则统一兜底为 `TrainListener.NOOP`。

`com.chua.deeplearning.support.dl4j.train.ResNet50Model`（数据加载 + 模型构建）：

| 成员 | 说明 |
| --- | --- |
| 字段默认值 | `nClasses=3`、`batchSize=10`、`width=224`、`height=224`、`nChannels=3`、`learningRate=1e-3`、`lrMomentum=0.9`、随机种子 `13` |
| `loadData(File parentDir, int trainPerc)` | 按比例划分训练/测试集；`trainPerc >= 100` 抛 `IllegalArgumentException`（消息：`训练百分比应该小于 100%.`） |
| `loadData(File trainDir, File testDir)` | 使用已划分好的训练/测试目录 |
| `build()` | 从 DL4J zoo 在线下载 ResNet50 预训练权重并做迁移学习改造 |
| `build(File model)` | 从本地模型文件（zip）加载预训练权重后改造 |
| `loadModel(File)` | `ComputationGraph.load(model, true)` 加载已训练模型 |
| `getTrainIter()` / `getTestIter()` / `getLabels()` / `getComputationGraph()` | 访问内部产物 |

数据处理细节：`ImageRecordReader`（224×224×3，`ParentPathLabelGenerator` 以父目录名作标签）+ 训练集数据增强（`FlipImageTransform` 概率 0.9 / 0.8、`WarpImageTransform` 概率 0.5，经 `PipelineImageTransform` 串联）+ `VGG16ImagePreProcessor` 预处理；测试集批次为 1。

模型改造细节：`FineTuneConfiguration` = SGD（`OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT`）+ `Nesterovs`（学习率 + 动量）+ `GradientNormalization.RenormalizeL2PerLayer`（阈值 1.0）+ `l2(0.00001)` + `WorkspaceMode.ENABLED`；随后 `TransferLearning.GraphBuilder` 移除 `fc1000` 并重建输出层（`nIn=2048`、`nOut=nClasses`、`SOFTMAX`、`NEGATIVELOGLIKELIHOOD`，接在 `flatten_1` 之后）。

### 3.6 推理：`ResNet50InferenceModel` / `ClassPrediction` / `ComparisonResult`

`com.chua.deeplearning.support.dl4j.infer.ResNet50InferenceModel`：有状态推理模型，构造时加载一次，后续复用。入参图片为 `org.opencv.core.Mat`（该类型经依赖 `utils-support-deeplearning-starter` 传递引入 `org.openpnp:opencv` 而来）。

| 成员 | 说明 |
| --- | --- |
| `new ResNet50InferenceModel(String modelPath, List<String> labels)` | 加载模型并指定类别标签；文件不存在抛 `IOException` |
| `new ResNet50InferenceModel(String modelPath)` | 不带标签（仅特征提取 / 比对场景） |
| `ClassPrediction classify(Mat image)` | 分类推理（`outputSingle` 后取 argMax）；未指定标签时抛 `IllegalStateException`（消息：`分类需要标签列表，构造时未指定`） |
| `float[] extractFeature(Mat image)` | 临时移除 `fc1000`、以 `flatten_1` 为输出，返回 2048 维特征向量 |
| `ComparisonResult compare(Mat image1, Mat image2)` | 1:1 比对：两次特征提取 + 余弦相似度 |
| `static float cosineSimilarity(float[] a, float[] b)` | 余弦相似度，按 `(cos + 1) / 2` 映射到 0.0~1.0；维度不一致抛 `IllegalArgumentException` |
| 常量 | `IMG_SIZE = 224`、`FEATURE_DIMENSION = 2048`（匹配阈值 `0.5` 为私有常量） |
| `getModel()` / `getLabels()` 等 | lombok `@Getter` 生成的访问器 |

`com.chua.deeplearning.support.dl4j.infer.ComparisonResult`：Java `record`，组件为 `similarity`（0.0~1.0）、`feature1`、`feature2`（特征向量）、`match`（相似度 &gt; 0.5 判定）；规范构造器与访问器都对数组做防御性拷贝。

`com.chua.deeplearning.support.dl4j.infer.ClassPrediction`：`getClassName()`（预测类别名）+ `getProbability()`（置信度 0.0~1.0）；`toString()` 输出形如 `类别名(0.0000)`。

---

## 4. 快速开始

### 4.1 链式训练（推荐）

前提：训练数据根目录下**每个子目录是一个类别**（目录名即标签）；预训练模型路径与续训模型路径至少提供一个（`ResNet50TransferTrainer` 直接用该路径构造 `File` 加载模型，两者皆缺会导致 `new File(null)` 失败）。

```java
import com.chua.deeplearning.support.dl4j.TrainResult;
import com.chua.deeplearning.support.dl4j.TrainerFactory;

public class QuickStart {
    public static void main(String[] args) throws Exception {
        TrainResult result = TrainerFactory.fluent()
                .data("D:/data/animals")            // 子目录如 cat / dog / bird
                .model("D:/pretrained/resnet50.zip")// 预训练 ResNet50 权重（本地 zip）
                .saveTo("D:/models/animals")        // 目录：最优模型写入 NewResNet50.zip
                .classifier(3)                      // 类别数（迁移学习输出层维度）
                .epochs(10).batchSize(2)            // 与 TrainArgument.defaults() 一致
                .fit();

        System.out.println(result.isSuccess());     // true
        System.out.println(result.getModelPath());  // 最优 F1 模型文件
        System.out.println(result.getBestScore());  // 最优 F1 值
        System.out.println(result.getLabels());     // 由目录名推导的标签
    }
}
```

超参数默认值已可用，通常只需 `data` / `model`（或 `resume`）/ `saveTo` / `classifier` 四项：

```java
TrainResult result = TrainerFactory.fluent()
        .data("D:/data/animals")
        .resume("D:/models/animals/NewResNet50.zip")  // 从上次训练的模型继续微调
        .saveTo("D:/models/animals")
        .fitUnchecked();                              // 运行时异常，免 throws
```

### 4.2 进度回调

```java
import com.chua.deeplearning.support.dl4j.TrainListener;
import com.chua.deeplearning.support.dl4j.TrainProgress;

TrainListener listener = new TrainListener() {
    @Override public void onStarted(TrainProgress p) {
        System.out.println("开始：" + p.getStage());
    }
    @Override public void onEpochCompleted(TrainProgress p) {
        // currentEpoch / totalEpochs / bestScore / progress / classLabels
        System.out.printf("epoch %d/%d | F1=%.4f | %.1f%%%n",
                p.getCurrentEpoch(), p.getTotalEpochs(), p.getBestScore(), p.getProgress());
    }
    @Override public void onFinished(TrainProgress p) {
        System.out.println("完成：" + p.getModelPath());
    }
    @Override public void onFailed(TrainProgress p, Throwable cause) {
        cause.printStackTrace();
    }
};

TrainResult result = TrainerFactory.fluent()
        .data("D:/data/animals")
        .model("D:/pretrained/resnet50.zip")
        .saveTo("D:/models/animals")
        .listener(listener)
        .fitUnchecked();
```

也可用 `TrainerFactory.get().train(argument, modelPath, savePath, dataRootPath, listener)` 走完整签名，无需回调时 `listener` 传 `TrainListener.NOOP`。

### 4.3 推理：分类、特征、比对

```java
import com.chua.deeplearning.support.dl4j.infer.ClassPrediction;
import com.chua.deeplearning.support.dl4j.infer.ComparisonResult;
import com.chua.deeplearning.support.dl4j.infer.ResNet50InferenceModel;
import org.opencv.core.Mat;

// 用训练产物 + 训练时回填的标签构造推理模型
ResNet50InferenceModel model = new ResNet50InferenceModel(
        result.getModelPath(), result.getLabels());

Mat image = /* org.opencv.core.Mat 图像 */;
ClassPrediction p = model.classify(image);       // 分类：类别名 + 置信度
System.out.println(p);                           // 形如 bird(0.9871)

float[] feature = model.extractFeature(image);   // 2048 维特征（flatten_1）

Mat image2 = /* 另一张图 */;
ComparisonResult cmp = model.compare(image, image2);  // 1:1 比对
System.out.println(cmp.similarity() + " match=" + cmp.match()); // 相似度>0.5 为 true
```

> `classify` / `extractFeature` / `compare` 声明抛出 `IOException`，调用方需捕获或继续上抛。

---

## 5. 配置项

### 5.1 运行时配置

**无。** 依据：本模块源码没有读取 Spring 配置属性、系统属性或环境变量的代码，也没有 `application*.yml/properties`、`META-INF` 配置或 Spring Boot 自动配置类；训练行为完全由 `TrainArgument` 字段与 `ChainedTrainer` 链式方法入参决定。

### 5.2 训练参数默认值

见 [3.3](#33-训练超参数trainargument)（`TrainArgument.defaults()`：epoch=10、batchSize=2、nClasses=3、learningRate=1e-3、lrMomentum=0.9、trainPercent=70）。`ResNet50Model` 侧兜底默认：`width=224`、`height=224`、`nChannels=3`、`batchSize=10`、`learningRate=1e-3`、`lrMomentum=0.9`（`setLearningRate`/`setLrMomentum` 对 `null` 与非法值做保护，不覆盖默认）。

### 5.3 构建期 Maven 属性

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `dl4j.version` | `1.0.0-M2.1` | DL4J / ND4J / DataVec 全家桶统一版本 |
| `nd4j.backend` | `nd4j-native-platform` | ND4J 后端；pom 注释：改为 `nd4j-cuda-X-platform` 可使用 CUDA GPU |

---

## 6. SPI 与扩展点

- 本模块**没有**向 `utils-support-common-starter` 的 SPI 体系注册任何扩展（无 `src/main/resources/META-INF/extensions` 文件）。
- 唯一扩展点是 `Trainer` 接口本身：支持新模型架构时实现 `Trainer`，然后：
  - 调用 `TrainerFactory.setCurrent(myTrainer)` 全局替换默认实现（传 `null` 恢复 `ResNet50TransferTrainer`）；或
  - 用 `ChainedTrainer.of(myTrainer)` 按次包裹使用；或
  - Spring 容器场景在装配阶段把 `Trainer` Bean 通过 `setCurrent` 桥接进工厂（`TrainerFactory` javadoc 口径：可优先取容器中注入的 `Trainer` Bean）。
- `TrainListener` 是训练进度的回调扩展点（普通接口，非 SPI），训练平台可借其实时推送或轮询 `TrainProgress`。

---

## 7. 构建与依赖

模块坐标：`com.chua:utils-support-deeplearning-dl4j-starter:4.0.0.51`，父模块 `utils-support-deeplearning-parent`，根 `utils-support-parent-starter`。

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随仓 | 工具类（如 `ChainedTrainer` 使用的 `StringUtils`） |
| `com.chua:utils-support-deeplearning-starter` | `${project.version}` | 深度学习核心接口与工具；传递引入 `org.openpnp:opencv`（`4.9.0-0`，提供 `org.opencv.core.Mat`） |
| `org.nd4j:nd4j-native-platform` | `1.0.0-M2.1` | ND4J CPU 后端（`${nd4j.backend}`） |
| `org.datavec:datavec-api` / `datavec-data-image` / `datavec-local` | `1.0.0-M2.1` | 图像数据加载与增强 |
| `org.deeplearning4j:deeplearning4j-core` | `1.0.0-M2.1` | 训练核心（`ComputationGraph`、`ModelSerializer` 等） |
| `org.deeplearning4j:deeplearning4j-zoo` | `1.0.0-M2.1` | 预训练 zoo 模型（`ResNet50`） |
| `org.deeplearning4j:deeplearning4j-parallel-wrapper` | `1.0.0-M2.1` | `ParallelWrapper` / `ParallelInference`（本模块源码未直接使用） |
| `org.projectlombok:lombok` | provided | 编译期代码生成 |

在仓库根目录构建（与全仓口径一致）：

```bash
mvn -o -q compile -DskipTests
```

仅构建本模块及其依赖链：

```bash
mvn -o -q -pl utils-support-deeplearning-parent/utils-support-deeplearning-dl4j-starter -am compile -DskipTests
```

> 使用 GPU：把本模块 `pom.xml` 中 `nd4j.backend` 属性改为 `nd4j-cuda-X-platform`（对应 CUDA 版本）。

---

## 8. 边界：这些能力不在本模块

| 能力 | 去处 |
| --- | --- |
| 通用深度学习能力接口与组件（图像分类/检测、OCR、姿态、车牌、语音、时序、翻译器等，如 `ImageClassifier`、`OcrPipeline`、`PoseEstimator`、`SpeechRecognizer`、`TimeSeriesForecaster`、`TranslatorRegistry`） | `utils-support-deeplearning-starter`（`com.chua.deeplearning.support` 下各包），本模块不重复实现 |
| 其它框架的训练/推理实现（ONNX、sherpa-onnx、PyTorch、Paddle、TensorFlow、MXNet、AgentScope、LangChain4j、OpenCV、safetensors、3D、jitllm、ollama、laya、weka） | 同属 `utils-support-deeplearning-parent` 的兄弟 starter |
| Spring Boot 自动配置、HTTP 端点、定时任务、持久化 | 均不存在；本模块是纯 Java 训练/推理库 |
| 预训练权重的在线下载 | 仅 `ResNet50Model.build()`（无参）走 DL4J zoo 在线下载；经 `Trainer` / `ChainedTrainer` 的训练链路要求调用方提供本地模型文件（`modelPath` 或 `resumeModelPath`） |
| OpenCV 原生库的加载 | 本模块不做 `System.loadLibrary` 类初始化，`org.opencv.core.Mat` 的可用性由依赖与环境准备 |
| 多 GPU / 分布式训练编排 | 未实现；`deeplearning4j-parallel-wrapper` 仅为已引入依赖，源码未使用 |
| 目标检测标签训练 | `TrainArgument.detLabels` 仅为预留字段，无对应实现 |

