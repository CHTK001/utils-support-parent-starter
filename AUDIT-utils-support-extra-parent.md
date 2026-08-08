# utils-support-extra-parent 修复跟踪表

**文件总数:** 177
**规范:** ch-java-coding-style(强制) + P3C(强制)
**状态:** ⬜ 未检查 / � 检查中 / ✅ 已修复 / ⚠️ 暂不修 / ❌ 失败

## 文件清单与修复状态

| # | 相对路径 | 状态 | 主要修复项 | 备注 |
|---:|---|---|---|---|
| 1 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageHealthChecker.java` | ⬜ | | |
| 2 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageInstaller.java` | ⬜ | | |
| 3 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageInstance.java` | ⬜ | | |
| 4 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageLifecycleManager.java` | ⬜ | | |
| 5 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageManagementResponse.java` | ⬜ | | |
| 6 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageManager.java` | ⬜ | | |
| 7 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImagePackager.java` | ⬜ | | |
| 8 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageProcessManager.java` | ⬜ | | |
| 9 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageProperties.java` | ⬜ | | |
| 10 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageRuntimeConfig.java` | ⬜ | | |
| 11 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageStatus.java` | ⬜ | | |
| 12 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/AppImageType.java` | ⬜ | | |
| 13 | `utils-support-appimage-starter/src/main/java/com/chua/utils/support/appimage/exception/AppImageException.java` | ⬜ | | |
| 14 | `utils-support-asm-starter/src/main/java/com/chua/common/support/bean/AsmBeanCopier.java` | ⬜ | | |
| 15 | `utils-support-asm-starter/src/main/java/com/chua/common/support/lang/compile/AsmCompiler.java` | ⬜ | | |
| 16 | `utils-support-asm-starter/src/main/java/com/chua/common/support/proxy/asm/AsmProxyFactory.java` | ⬜ | | |
| 17 | `utils-support-asm-starter/src/main/java/com/chua/common/support/proxy/javassist/JavassistProxyFactory.java` | ⬜ | | |
| 18 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/AutoClose.java` | ⬜ | | |
| 19 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/CleanNull.java` | ⬜ | | |
| 20 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/DefaultValue.java` | ⬜ | | |
| 21 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/PadTruncate.java` | ⬜ | | |
| 22 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/Retry.java` | ⬜ | | |
| 23 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/Timed.java` | ⬜ | | |
| 24 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/Trace.java` | ⬜ | | |
| 25 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/Trim.java` | ⬜ | | |
| 26 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/annotation/Virtual.java` | ⬜ | | |
| 27 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/internal/AbstractAstProcessor.java` | ⬜ | | |
| 28 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/internal/AstUtils.java` | ⬜ | | |
| 29 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/AstUtils.java` | ⬜ | | |
| 30 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/AutoCloseAstProcessor.java` | ⬜ | | |
| 31 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/CleanNullAstProcessor.java` | ⬜ | | |
| 32 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/DefaultValueAstProcessor.java` | ⬜ | | |
| 33 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/DependencyDetector.java` | ⬜ | | |
| 34 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/PadTruncateAstProcessor.java` | ⬜ | | |
| 35 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/RetryAstProcessor.java` | ⬜ | | |
| 36 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/TimedAstProcessor.java` | ⬜ | | |
| 37 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/TraceAstProcessor.java` | ⬜ | | |
| 38 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/TrimAstProcessor.java` | ⬜ | | |
| 39 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/processor/VirtualAstProcessor.java` | ⬜ | | |
| 40 | `utils-support-ast-starter/src/main/java/com/chua/ast/support/trace/TraceContext.java` | ⬜ | | |
| 41 | `utils-support-captcha-starter/src/main/java/com/chua/captcha/support/CaptchaParser.java` | ⬜ | | |
| 42 | `utils-support-captcha-starter/src/main/java/com/chua/captcha/support/CaptchaRequest.java` | ⬜ | | |
| 43 | `utils-support-captcha-starter/src/main/java/com/chua/captcha/support/CaptchaResponse.java` | ⬜ | | |
| 44 | `utils-support-captcha-starter/src/main/java/com/chua/captcha/support/CaptchaRunClient.java` | ⬜ | | |
| 45 | `utils-support-captcha-starter/src/main/java/com/chua/captcha/support/CaptchaSetting.java` | ⬜ | | |
| 46 | `utils-support-captcha-starter/src/main/java/com/chua/captcha/support/CaptchaType.java` | ⬜ | | |
| 47 | `utils-support-captcha-starter/src/main/java/com/chua/captcha/support/FileTaskPersistence.java` | ⬜ | | |
| 48 | `utils-support-captcha-starter/src/main/java/com/chua/captcha/support/TaskPersistence.java` | ⬜ | | |
| 49 | `utils-support-desktop-starter/src/main/java/com/chua/desktop/support/DesktopPush.java` | ⬜ | | |
| 50 | `utils-support-desktop-starter/src/main/java/com/chua/desktop/support/LinuxDesktopNotifier.java` | ⬜ | | |
| 51 | `utils-support-desktop-starter/src/main/java/com/chua/desktop/support/MacOsDesktopNotifier.java` | ⬜ | | |
| 52 | `utils-support-desktop-starter/src/main/java/com/chua/desktop/support/NativeDesktopNotifier.java` | ⬜ | | |
| 53 | `utils-support-desktop-starter/src/main/java/com/chua/desktop/support/WindowsDesktopNotifier.java` | ⬜ | | |
| 54 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/backup/EnhanceBackupStrategyProvider.java` | ⬜ | | |
| 55 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/emoji/AbstractEmoji.java` | ⬜ | | |
| 56 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/emoji/Emoji.java` | ⬜ | | |
| 57 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/emoji/EmojiManager.java` | ⬜ | | |
| 58 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/emoji/EmojiTrie.java` | ⬜ | | |
| 59 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/emoji/EmojiUtils.java` | ⬜ | | |
| 60 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/AuthServerFilter.java` | ⬜ | | |
| 61 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/CorsServerFilter.java` | ⬜ | | |
| 62 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/DosServerFilter.java` | ⬜ | | |
| 63 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/IpRateLimitServerFilter.java` | ⬜ | | |
| 64 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/LoggingServerFilter.java` | ⬜ | | |
| 65 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/PathTraversalServerFilter.java` | ⬜ | | |
| 66 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/QpsServerFilter.java` | ⬜ | | |
| 67 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/SignatureServerFilter.java` | ⬜ | | |
| 68 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/SsrfServerFilter.java` | ⬜ | | |
| 69 | `utils-support-enhance-starter/src/main/java/com/chua/enhance/support/network/server/filter/security/XssServerFilter.java` | ⬜ | | |
| 70 | `utils-support-example-starter/src/main/java/com/chua/example/ai/chat/AiProxyDetectorExample.java` | ⬜ | | |
| 71 | `utils-support-example-starter/src/main/java/com/chua/example/ai/chat/AiProxyDetectorExampleSpi.java` | ⬜ | | |
| 72 | `utils-support-example-starter/src/main/java/com/chua/example/ai/rag/RagChatExample.java` | ⬜ | | |
| 73 | `utils-support-example-starter/src/main/java/com/chua/example/ai/rag/RagChatExampleSpi.java` | ⬜ | | |
| 74 | `utils-support-example-starter/src/main/java/com/chua/example/ai/usage/ExampleModelPricingProvider.java` | ⬜ | | |
| 75 | `utils-support-example-starter/src/main/java/com/chua/example/concurrent/offset/OffsetFlowExample.java` | ⬜ | | |
| 76 | `utils-support-example-starter/src/main/java/com/chua/example/concurrent/offset/OffsetFlowExampleSpi.java` | ⬜ | | |
| 77 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/integrated/DatalakeIntegratedExample.java` | ⬜ | | |
| 78 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/integrated/DatalakeIntegratedExampleSpi.java` | ⬜ | | |
| 79 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/pipeline/PipelineEngineExample.java` | ⬜ | | |
| 80 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/pipeline/PipelineEngineExampleSpi.java` | ⬜ | | |
| 81 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/query/HttpDatalakeQueryEngineExample.java` | ⬜ | | |
| 82 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/server/DatalakeServerExample.java` | ⬜ | | |
| 83 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/server/DatalakeServerExampleSpi.java` | ⬜ | | |
| 84 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/sink/DataSinkExample.java` | ⬜ | | |
| 85 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/sink/DataSinkExampleSpi.java` | ⬜ | | |
| 86 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/subscribe/SubscriberExample.java` | ⬜ | | |
| 87 | `utils-support-example-starter/src/main/java/com/chua/example/datalake/subscribe/SubscriberExampleSpi.java` | ⬜ | | |
| 88 | `utils-support-example-starter/src/main/java/com/chua/example/datasync/DataSyncExample.java` | ⬜ | | |
| 89 | `utils-support-example-starter/src/main/java/com/chua/example/datasync/DataSyncExampleSpi.java` | ⬜ | | |
| 90 | `utils-support-example-starter/src/main/java/com/chua/example/engine/EngineExample.java` | ⬜ | | |
| 91 | `utils-support-example-starter/src/main/java/com/chua/example/engine/SimpleEngineDataSource.java` | ⬜ | | |
| 92 | `utils-support-example-starter/src/main/java/com/chua/example/flow/FlowCompleteExample.java` | ⬜ | | |
| 93 | `utils-support-example-starter/src/main/java/com/chua/example/flow/FlowEchoNode.java` | ⬜ | | |
| 94 | `utils-support-example-starter/src/main/java/com/chua/example/flow/FlowExample.java` | ⬜ | | |
| 95 | `utils-support-example-starter/src/main/java/com/chua/example/flow/FlowTraceExample.java` | ⬜ | | |
| 96 | `utils-support-example-starter/src/main/java/com/chua/example/lang/document/DocumentExample.java` | ⬜ | | |
| 97 | `utils-support-example-starter/src/main/java/com/chua/example/lang/document/DocumentExampleSpi.java` | ⬜ | | |
| 98 | `utils-support-example-starter/src/main/java/com/chua/example/media/ScreenCaptureExample.java` | ⬜ | | |
| 99 | `utils-support-example-starter/src/main/java/com/chua/example/media/ScreenCaptureExampleSpi.java` | ⬜ | | |
| 100 | `utils-support-example-starter/src/main/java/com/chua/example/media/VideoCodecExample.java` | ⬜ | | |
| 101 | `utils-support-example-starter/src/main/java/com/chua/example/media/VideoCodecExampleSpi.java` | ⬜ | | |
| 102 | `utils-support-example-starter/src/main/java/com/chua/example/osgi/HelloService.java` | ⬜ | | |
| 103 | `utils-support-example-starter/src/main/java/com/chua/example/osgi/HelloServiceImpl.java` | ⬜ | | |
| 104 | `utils-support-example-starter/src/main/java/com/chua/example/osgi/OsgiIntegrationExampleTest.java` | ⬜ | | |
| 105 | `utils-support-example-starter/src/main/java/com/chua/example/runner/ExampleRunner.java` | ⬜ | | |
| 106 | `utils-support-example-starter/src/main/java/com/chua/example/runner/SpiderStoreTest.java` | ⬜ | | |
| 107 | `utils-support-example-starter/src/main/java/com/chua/example/spi/Example.java` | ⬜ | | |
| 108 | `utils-support-example-starter/src/main/java/com/chua/example/tui/TuiDashboardExample.java` | ⬜ | | |
| 109 | `utils-support-example-starter/src/main/java/com/chua/example/tui/TuiDashboardExampleSpi.java` | ⬜ | | |
| 110 | `utils-support-example-starter/src/main/java/com/chua/example/tui/TuiLauncher.java` | ⬜ | | |
| 111 | `utils-support-example-starter/src/main/java/com/chua/example/vector/VectorStorageExampleSpi.java` | ⬜ | | |
| 112 | `utils-support-jdk15on-starter/src/main/java/com/chua/jdk15on/support/crypto/BcDesedeCipher.java` | ⬜ | | |
| 113 | `utils-support-jdk15on-starter/src/main/java/com/chua/jdk15on/support/crypto/BcEciesCipher.java` | ⬜ | | |
| 114 | `utils-support-jdk15on-starter/src/main/java/com/chua/jdk15on/support/crypto/BcNoekeonCipher.java` | ⬜ | | |
| 115 | `utils-support-jdk15on-starter/src/main/java/com/chua/jdk15on/support/crypto/BcRsaCipher.java` | ⬜ | | |
| 116 | `utils-support-jdk15on-starter/src/main/java/com/chua/jdk15on/support/crypto/BcSm2Cipher.java` | ⬜ | | |
| 117 | `utils-support-jdk15on-starter/src/main/java/com/chua/jdk15on/support/crypto/BcSm4Cipher.java` | ⬜ | | |
| 118 | `utils-support-jdk15on-starter/src/main/java/com/chua/jdk15on/support/crypto/BcTwofishCipher.java` | ⬜ | | |
| 119 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/BatteryInfo.java` | ⬜ | | |
| 120 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/CpuCore.java` | ⬜ | | |
| 121 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/DiskInfo.java` | ⬜ | | |
| 122 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/DiskIo.java` | ⬜ | | |
| 123 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/GpuInfo.java` | ⬜ | | |
| 124 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MemorySlot.java` | ⬜ | | |
| 125 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MetricsAutoConfiguration.java` | ⬜ | | |
| 126 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MetricsDataSyncAgent.java` | ⬜ | | |
| 127 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MetricsJsonParser.java` | ⬜ | | |
| 128 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MetricsNativeLibrary.java` | ⬜ | | |
| 129 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MetricsProperties.java` | ⬜ | | |
| 130 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MetricsService.java` | ⬜ | | |
| 131 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MetricsSnapshot.java` | ⬜ | | |
| 132 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/MetricsTable.java` | ⬜ | | |
| 133 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/NetworkInterface.java` | ⬜ | | |
| 134 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/ProcessInfo.java` | ⬜ | | |
| 135 | `utils-support-metrics-starter/src/main/java/com/chua/metrics/support/SystemLoad.java` | ⬜ | | |
| 136 | `utils-support-multipart-starter/src/main/java/com/chua/multipart/support/ApacheMultipartParser.java` | ⬜ | | |
| 137 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Baseboard.java` | ⬜ | | |
| 138 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Battery.java` | ⬜ | | |
| 139 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/ComputerSystem.java` | ⬜ | | |
| 140 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Cpu.java` | ⬜ | | |
| 141 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Disk.java` | ⬜ | | |
| 142 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Display.java` | ⬜ | | |
| 143 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Firmware.java` | ⬜ | | |
| 144 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Gpu.java` | ⬜ | | |
| 145 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/HWPartition.java` | ⬜ | | |
| 146 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/LogicalVolumeGroup.java` | ⬜ | | |
| 147 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Mem.java` | ⬜ | | |
| 148 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Network.java` | ⬜ | | |
| 149 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Oshi.java` | ⬜ | | |
| 150 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/PhysicalMemory.java` | ⬜ | | |
| 151 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/ProcessInfo.java` | ⬜ | | |
| 152 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Sensor.java` | ⬜ | | |
| 153 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/SensorInfo.java` | ⬜ | | |
| 154 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/SoundCard.java` | ⬜ | | |
| 155 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/Sys.java` | ⬜ | | |
| 156 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/SysFile.java` | ⬜ | | |
| 157 | `utils-support-oshi-starter/src/main/java/com/chua/oshi/support/VirtualMemory.java` | ⬜ | | |
| 158 | `utils-support-serialize-starter/src/main/java/com/chua/serialize/support/auto/AutoSerializer.java` | ⬜ | | |
| 159 | `utils-support-serialize-starter/src/main/java/com/chua/serialize/support/auto/AutoSerializerProvider.java` | ⬜ | | |
| 160 | `utils-support-serialize-starter/src/main/java/com/chua/serialize/support/kryo/KryoSerializer.java` | ⬜ | | |
| 161 | `utils-support-serialize-starter/src/main/java/com/chua/serialize/support/pool/KryoPoolManager.java` | ⬜ | | |
| 162 | `utils-support-serialize-starter/src/main/java/com/chua/serialize/support/SerializeSupport.java` | ⬜ | | |
| 163 | `utils-support-tika-starter/src/main/java/com/chua/tika/support/file/extractor/TikaTextExtractor.java` | ⬜ | | |
| 164 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/dashboard/SystemMonitorService.java` | ⬜ | | |
| 165 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/MordantHelper.java` | ⬜ | | |
| 166 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/TuiDashboard.java` | ⬜ | | |
| 167 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/TuiDashboardBuilder.java` | ⬜ | | |
| 168 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/TuiLayout.java` | ⬜ | | |
| 169 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/TuiWidget.java` | ⬜ | | |
| 170 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/widgets/CpuWidget.java` | ⬜ | | |
| 171 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/widgets/DiskWidget.java` | ⬜ | | |
| 172 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/widgets/HtopWidget.java` | ⬜ | | |
| 173 | `utils-support-tui-starter/src/main/java/com/chua/tui/support/widgets/MemoryWidget.java` | ⬜ | | |
| 174 | `utils-support-video-processor-starter/src/main/java/com/chua/video/processor/support/bridge/VideoProcessorBridge.java` | ⬜ | | |
| 175 | `utils-support-video-processor-starter/src/main/java/com/chua/video/processor/support/example/HlsTranscodeExample.java` | ⬜ | | |
| 176 | `utils-support-video-processor-starter/src/main/java/com/chua/video/processor/support/example/VideoProcessorSpiExample.java` | ⬜ | | |
| 177 | `utils-support-video-processor-starter/src/main/java/com/chua/video/processor/support/VideoProcessor.java` | ⬜ | | |

## 修复汇总

| 项目 | 数量 |
|---|---:|
| 总文件 | 177 |
| 已检查 | 0 |
| 已修复 | 0 |
| 暂不修 | 0 |
| 失败 | 0 |
