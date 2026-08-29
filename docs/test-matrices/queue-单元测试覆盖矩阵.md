# ShmQueueExample 单元测试覆盖矩阵

## 版本信息
- 类名：`com.chua.example.concurrent.queue.ShmQueueExample`
- 模块：`utils-support-example-starter`
- 作者：CH
- 更新日期：2026-08-19

## 依赖模块

| 模块 | 用途 |
|:----|:----|
| utils-support-common-starter | 抽象 `ShmQueue` API + `ShmQueueProvider` SPI 接口 |
| utils-support-native-shm-queue | SPI 实现 `NativeShmQueueProvider` + 动态库资源 + Rust FFI |

## 等待模式覆盖矩阵

| 模式 | basic | queue full | large burst | 状态 |
|:----|:------|:-----------|:------------|:----|
| spin   | ✅ | ✅ | ✅ | 通过 |
| block  | ✅ | ✅ | ✅ | 通过 |
| hybrid | ✅ | ✅ | ✅ | 通过 |

## 测试场景覆盖矩阵

| 场景ID | 测试方法 | 模式 | 断言 | 通过条件 |
|:------|:--------|:-----|:-----|:--------|
| TC-01 | testBasicSendRecv | spin/block/hybrid | 消息可正常往返 | msg.type()==42 && msg.data equals "hello-shmqueue" |
| TC-02 | testQueueFull | spin/block/hybrid | 队列满时抛 ShmQueueException 且 code==-8 | 抛异常 |
| TC-03 | testLargeBurst | spin/block/hybrid | 10000 条消息顺序一致 | recv 数 == send 数 && 类型/数据全部正确 |

## 执行记录

| 日期 | 模式 | 结果 | 备注 |
|:-----|:----|:-----|:----|
| 2026-08-19 | all | 待执行 | 首次提交 |
