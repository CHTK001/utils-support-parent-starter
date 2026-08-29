# SubscriberExample 单元测试覆盖矩阵

## 版本信息
- 类名：SubscriberExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-29

## 能力点覆盖矩阵

| 能力 ID | 测试方法      | 前置条件                  | 断言                                  | 通过条件                          |
|:-------|:-------------|:-------------------------|:-------------------------------------|:----------------------------------|
| SB-01  | testPush     | file OffsetFlow 启动      | push(envelope).block() 后 counter=1  | counter.get() == 1                 |
| SB-02  | testReset    | file OffsetFlow 启动      | push 两次 + reset(0) + push，counter=2 | counter.get() == 2                 |

## 执行记录

| 日期       | type     | 结果     | 备注                              |
|:-----------|:---------|:---------|:----------------------------------|
| 2026-07-29 | all      | 预期通过 | 基于 Reactor Mono, 同步 block 等待   |

## 注意事项

- `RealTimeDatalakeSubscriber.push()` 返回 `Mono<Void>`，需 `.block()` 同步等待
- push 内部先 `advance()` 再调 consumer，最后根据 envelope 时间戳 reset
- 依赖 reactor-core 3.7.4（已在 example-starter pom 显式声明）