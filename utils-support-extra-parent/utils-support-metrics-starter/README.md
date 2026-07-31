# Metrics Starter

系统指标采集 Spring Boot Starter（基于 Rust native + FlatBuffers）。

## 依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-metrics-starter</artifactId>
</dependency>
```

## 配置

```yaml
metrics:
  enabled: true
  interval-ms: 1000
  enable-cpu: true
  enable-memory: true
  enable-disk: true
  enable-network: true
  enable-process: false
  enable-gpu: true
  enable-battery: true
  enable-load: true
```

## 使用

```java
@Autowired
private MetricsService metricsService;

public void printMetrics() {
    MetricsSnapshot snapshot = metricsService.getCurrentSnapshot();
    System.out.println("CPU 核心数: " + snapshot.getCpuCores().size());
    System.out.println("内存总量: " + snapshot.getMemorySlots().get(0).getTotal());
}
```

## 指标表格

| 指标 | 说明 |
|------|------|
| cpuCores | 每个 CPU 核心的使用率/频率 |
| memorySlots | 每个内存插槽的总量/已用/可用 |
| disks | 每个分区的总量/已用/文件系统 |
| networks | 每个网卡的收发字节/包 |
| processes | 前 N 个进程的 CPU/内存占用 |
| gpus | GPU 使用率/显存/温度 |
| batteries | 电池电量/状态/剩余时间 |
| load | 系统 1/5/15 分钟负载平均值 |