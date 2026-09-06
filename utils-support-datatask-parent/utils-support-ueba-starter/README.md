# utils-support-ueba-starter

UEBA（用户实体行为分析）分析引擎模块。提供 IP 异常流量检测（AutoEncoder）、行为序列分类（GRU + Attention）、语义解释（MiniMind）三大能力，支持从零训练与基于已有模型续训。

---

## 快速开始

### 1. 添加依赖

```xml
<!-- 推理 -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ueba-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
<!-- 训练 + 推理（含 Python 训练脚本） -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ueba-starter</artifactId>
    <version>4.0.0.42</version>
</dependency>
<!-- 可选：ONNX 预训练模型（需训练后放入 models-parent） -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-models-onnx-ueba</artifactId>
    <version>4.0.0.42</version>
</dependency>
```

### 2. 基础推理（规则回退）

无需模型文件即可运行，自动降级到规则评分。

```java
try (UebaEngine engine = Ueba.engine().disableLlm().build()) {
    UebaResult result = engine.analyze(TrafficEvent.builder()
            .ip("192.168.1.100")
            .path("/admin/login")
            .method("POST")
            .statusCode(200)
            .timestamp(System.currentTimeMillis())
            .responseTimeMs(120)
            .build());
    System.out.println(result.getRiskLevel()); // NORMAL / LOW / MEDIUM / HIGH / CRITICAL
}
```

### 3. 配置驱动

在 `application.yml` 或 `ueba-config.yaml`（classpath）中配置特征与模型：

```yaml
# 示例配置（完整 schema 见 src/main/resources/ueba-config.yaml）
features:
  - name: request_rate
    type: NUMERIC
    normalize: MINMAX
    window: 60
  - name: path_entropy
    type: NUMERIC
    normalize: ZSCORE
autoEncoder:
  threshold: 0.8
  modelFile: autoencoder_ip.onnx
lstm:
  windowSize: 20
  numClasses: 3
  classLabels: [normal, suspicious, attack]
  modelFile: lstm_attention_behavior.onnx
risk:
  ipWeight: 0.5
  behaviorWeight: 0.5
  highThreshold: 0.7
  mediumThreshold: 0.35
preprocessing:
  scalers:
    request_rate: {min: 0, max: 100}
    path_entropy: {mean: 2.5, std: 1.2}
  vocab:
    /login: 1
    /admin: 2
```

### 4. 加载 ONNX 模型

模型加载优先级（由高到低）：

1. 配置中的 `autoEncoder.modelPath` / `lstm.modelPath`（显式绝对路径）
2. 系统属性 `-Dueba.model.dir=<dir>` 或构建器 `.modelDir(dir)`
3. classpath 资源 `models/ueba/*.onnx`（依赖 `utils-support-models-onnx-ueba` 时可用）

```java
UebaEngine engine = Ueba.engine()
        .configResource("ueba-config.yaml")
        .modelDir("/path/to/trained/models")
        .enableLlm()  // 启用 MiniMind 语义解释（默认开启）
        .build();
```

---

## 训练

### Python CLI（直接）

```bash
pip install torch numpy pyyaml
python train_ueba.py \
    --config ueba-config.yaml \
    --data access.csv \
    --output ./models \
    --epochs 50 --batch-size 64 --learning-rate 0.001
```

**CSV 格式**：`timestamp,ip,path,method,status,user_agent,response_time,response_size`

训练产物：
- `autoencoder_ip.onnx` — IP 异常检测模型
- `lstm_attention_behavior.onnx` — 行为序列分类模型
- `ueba-config-derived.yaml` — 回写 scaler/vocab/threshold（复制到 `ueba-config.yaml`）
- `checkpoint/*.pt` — 续训 checkpoint

### Java 链式

```java
// 从零训练
Ueba.training()
    .configResource("ueba-config.yaml")
    .data(Path.of("access.csv"))          // 或 .events(List<TrafficEvent>)
    .outputDir(Path.of("target/ueba-models"))
    .epochs(50)
    .batchSize(64)
    .learningRate(1e-3)
    .build()
    .execute();
```

### 基于已有模型续训

```java
// resume 指向上一轮产出目录
Ueba.training()
    .configResource("ueba-config.yaml")
    .data(newAccess.csv)
    .outputDir(Path.of("target/ueba-models-v2"))
    .resume(Path.of("target/ueba-models"))  // ← 续训来源
    .epochs(30)
    .build()
    .execute();
```

Python 侧等价：`--resume <oldOutputDir>`

---

## Spring Boot 集成

引入 `spring-support-ueba-starter` 并配置属性：

```yaml
plugin:
  ueba:
    enabled: true           # 总开关（默认 true）
    llm: true               # MiniMind 语义解释（默认 true）
    configResource: ueba-config.yaml
    configPath: ""          # 优先外部配置文件
    modelDir: ""            # 模型目录
```

注入使用：

```java
@Resource
private UebaEngine uebaEngine;

UebaResult result = uebaEngine.analyze(event);
```

---

## 架构

```
TrafficEvent
      ↓
FeatureExtractor（特征提取）
      ├── AutoEncoderIpTranslator  →  IP 聚合特征 → 重建误差 → IpAnomalyResult
      └── LstmAttentionBehaviorTranslator → 行为序列 → 类别概率 → BehaviorProfile
      ↓
UebaEngine（风险合并 + 等级判定）
      ↓
MiniMindUebaAnalyzer（可选，语义解释）
      ↓
UebaResult（综合风险分数 + 等级 + 解释）
```

---

## 依赖

| 模块 | 作用 |
|---|---|
| `utils-support-common-starter` | SnakeYAML、NativeLoader 等基础工具 |
| `utils-support-deeplearning-onnx-starter` | ONNX 推理（AutoEncoder / LSTM 翻译器） |
| `utils-support-models-onnx-ueba`（可选）| 预训练 ONNX 模型（classpath 自动加载） |

---

## P3C 合规

- 全量 Javadoc（每个类/方法/字段）
- 参数前置校验（`Objects.requireNonNull` / `IllegalArgumentException`）
- 无 Mock / 无测试框架引入
- 资源 try-with-resources 关闭
- 规则与 ML 双路径，模型缺失自动降级
- 自适应 AE 阈值（在线 Welford 统计，学习期不误报）
- Hash fallback 不越界（受限于训练词表大小）

---

## 测试

完整示例在 `utils-support-example-starter` 的 `UebaExample` / `UebaTrainExample` 以及 `spring-support-test-app` 的 `UebuRealTest` 中，全部真实执行通过。
