# utils-support-runtime-parent 修复跟踪表

**文件总数:** 80
**规范:** ch-java-coding-style(强制) + P3C(强制)
**状态:** ⬜ 未检查 / � 检查中 / ✅ 已修复 / ⚠️ 暂不修 / ❌ 失败

## 文件清单与修复状态

| # | 相对路径 | 状态 | 主要修复项 | 备注 |
|---:|---|---|---|---|
| 1 | `utils-support-runtime-agent-demo/src/main/java/com/example/demo/AgentController.java` | ⬜ | | |
| 2 | `utils-support-runtime-agent-demo/src/main/java/com/example/demo/DemoApplication.java` | ⬜ | | |
| 3 | `utils-support-runtime-agent-demo/src/main/java/com/example/demo/OrderController.java` | ⬜ | | |
| 4 | `utils-support-runtime-agent/src/main/java/com/chua/runtime/agent/Bootstrap.java` | ⬜ | | |
| 5 | `utils-support-runtime-agent/src/main/java/com/chua/runtime/agent/RuntimeAgent.java` | ⬜ | | |
| 6 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/ApmBootstrap.java` | ⬜ | | |
| 7 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/DependencyGraphHandler.java` | ⬜ | | |
| 8 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/FileHandler.java` | ⬜ | | |
| 9 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/HandleLeakHandler.java` | ⬜ | | |
| 10 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/JedisHandler.java` | ⬜ | | |
| 11 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/KafkaHandler.java` | ⬜ | | |
| 12 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/LogEntry.java` | ⬜ | | |
| 13 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/LogHandler.java` | ⬜ | | |
| 14 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/NetHandler.java` | ⬜ | | |
| 15 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/SoftwareDetector.java` | ⬜ | | |
| 16 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/TraceHandler.java` | ⬜ | | |
| 17 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/TransmissionHandler.java` | ⬜ | | |
| 18 | `utils-support-runtime-apm/src/main/java/com/chua/runtime/apm/handler/ZooKeeperHandler.java` | ⬜ | | |
| 19 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/manager/DefaultJavaAgentManager.java` | ⬜ | | |
| 20 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/manager/DefaultRuntimeInstance.java` | ⬜ | | |
| 21 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/manager/DefaultRuntimeManager.java` | ⬜ | | |
| 22 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/manager/RuntimeInstance.java` | ⬜ | | |
| 23 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/manager/RuntimeManager.java` | ⬜ | | |
| 24 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/model/LogStream.java` | ⬜ | | |
| 25 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/model/ManagedService.java` | ⬜ | | |
| 26 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/model/RuntimeArtifact.java` | ⬜ | | |
| 27 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/model/RuntimeStatus.java` | ⬜ | | |
| 28 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/service/JavaAgentManager.java` | ⬜ | | |
| 29 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/service/ServiceManager.java` | ⬜ | | |
| 30 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/service/SystemdServiceManager.java` | ⬜ | | |
| 31 | `utils-support-runtime-core/src/main/java/com/chua/runtime/core/service/WindowsServiceManager.java` | ⬜ | | |
| 32 | `utils-support-runtime-e2e-test/src/test/java/com/chua/runtime/e2e/AppLayerHandlersTest.java` | ⬜ | | |
| 33 | `utils-support-runtime-e2e-test/src/test/java/com/chua/runtime/e2e/DebugTransformerTest.java` | ⬜ | | |
| 34 | `utils-support-runtime-e2e-test/src/test/java/com/chua/runtime/e2e/RuntimeE2ETest.java` | ⬜ | | |
| 35 | `utils-support-runtime-e2e-test/src/test/java/com/chua/runtime/e2e/RuntimeTestConfiguration.java` | ⬜ | | |
| 36 | `utils-support-runtime-e2e-test/src/test/java/com/chua/runtime/e2e/ShellE2ETest.java` | ⬜ | | |
| 37 | `utils-support-runtime-e2e-test/src/test/java/com/chua/runtime/e2e/SpringBootApmTest.java` | ⬜ | | |
| 38 | `utils-support-runtime-e2e-test/src/test/java/com/chua/runtime/e2e/TestController.java` | ⬜ | | |
| 39 | `utils-support-runtime-e2e-test/src/test/java/com/chua/runtime/e2e/W3CTraceContextTest.java` | ⬜ | | |
| 40 | `utils-support-runtime-e2e-test/src/test/java/com/example/biz/BusinessLogger.java` | ⬜ | | |
| 41 | `utils-support-runtime-e2e-test/src/test/java/com/example/biz/OrderService.java` | ⬜ | | |
| 42 | `utils-support-runtime-plugin/src/main/java/com/chua/runtime/plugin/InterceptPoint.java` | ⬜ | | |
| 43 | `utils-support-runtime-plugin/src/main/java/com/chua/runtime/plugin/loader/PluginClassLoader.java` | ⬜ | | |
| 44 | `utils-support-runtime-plugin/src/main/java/com/chua/runtime/plugin/loader/PluginManager.java` | ⬜ | | |
| 45 | `utils-support-runtime-plugin/src/main/java/com/chua/runtime/plugin/loader/PluginRegistry.java` | ⬜ | | |
| 46 | `utils-support-runtime-plugin/src/main/java/com/chua/runtime/plugin/loader/PluginScanner.java` | ⬜ | | |
| 47 | `utils-support-runtime-plugin/src/main/java/com/chua/runtime/plugin/Plugin.java` | ⬜ | | |
| 48 | `utils-support-runtime-plugin/src/main/java/com/chua/runtime/plugin/PluginContext.java` | ⬜ | | |
| 49 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/DependencyEdge.java` | ⬜ | | |
| 50 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/Endpoint.java` | ⬜ | | |
| 51 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/EndpointKind.java` | ⬜ | | |
| 52 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/Protocol.java` | ⬜ | | |
| 53 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/Software.java` | ⬜ | | |
| 54 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/Span.java` | ⬜ | | |
| 55 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/SpanEvent.java` | ⬜ | | |
| 56 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/SpanKind.java` | ⬜ | | |
| 57 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/SpanLink.java` | ⬜ | | |
| 58 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/StatusCode.java` | ⬜ | | |
| 59 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/Trace.java` | ⬜ | | |
| 60 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/TraceContextPropagator.java` | ⬜ | | |
| 61 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/TransmissionRecord.java` | ⬜ | | |
| 62 | `utils-support-runtime-protocol/src/main/java/com/chua/runtime/protocol/W3CTraceContext.java` | ⬜ | | |
| 63 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/builtin/ApmCommand.java` | ⬜ | | |
| 64 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/builtin/HelpCommand.java` | ⬜ | | |
| 65 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/builtin/InfoCommand.java` | ⬜ | | |
| 66 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/builtin/MemoryCommand.java` | ⬜ | | |
| 67 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/builtin/RuntimeCommand.java` | ⬜ | | |
| 68 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/builtin/StatusCommand.java` | ⬜ | | |
| 69 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/builtin/ThreadsCommand.java` | ⬜ | | |
| 70 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/Command.java` | ⬜ | | |
| 71 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/command/CommandRegistry.java` | ⬜ | | |
| 72 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/output/Console.java` | ⬜ | | |
| 73 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/ShellSession.java` | ⬜ | | |
| 74 | `utils-support-runtime-shell/src/main/java/com/chua/runtime/shell/TelnetServer.java` | ⬜ | | |
| 75 | `utils-support-runtime-spy/src/main/java/com/chua/runtime/spy/InterceptContext.java` | ⬜ | | |
| 76 | `utils-support-runtime-spy/src/main/java/com/chua/runtime/spy/RuntimeSpy.java` | ⬜ | | |
| 77 | `utils-support-runtime-spy/src/main/java/com/chua/runtime/spy/SpyBootstrap.java` | ⬜ | | |
| 78 | `utils-support-runtime-spy/src/main/java/com/chua/runtime/spy/SpyTransformer.java` | ⬜ | | |
| 79 | `utils-support-runtime-starter/src/main/java/com/chua/runtime/starter/GuacamoleArtifact.java` | ⬜ | | |
| 80 | `utils-support-runtime-starter/src/main/java/com/chua/runtime/starter/RuntimeBoot.java` | ⬜ | | |

## 修复汇总

| 项目 | 数量 |
|---|---:|
| 总文件 | 80 |
| 已检查 | 0 |
| 已修复 | 0 |
| 暂不修 | 0 |
| 失败 | 0 |
