# 1.3.7 剩余超长行清单（139 处）

> 口径：代码行长度 > 120 字符，排除注释行、文本块（`"""`）内部行。数据由全仓扫描生成，可直接用于逐行精修。

## 一、按类型汇总

| 类型 | 数量 | 典型成因 | 建议处理方式 |
| --- | --- | --- | --- |
| literal | 51 | 单条字符串超过 120 字符（HTML/CSS 片段、提示词、错误消息） | 长字符串字面量：可提取为 `private static final String` 常量，或按 `+` 拼接拆分（值不变，但可读性下降） |
| other | 51 | 深层调用/长表达式（builder 链、三元、try-with-resources） | 深层表达式：需按语义重排（提取局部变量或拆分调用链） |
| signature | 24 | 方法签名参数多或含全限定类型名 | 超长签名：多为泛型/长类型名，建议把全限定名改 import 简写，或按参数逐个换行 |
| regex | 13 | 正则表达式字面量 | 正则字面量：建议保留原样，或提取为 `static final Pattern` 常量以便复用与预编译 |
| **合计** | **139** | — | — |

## 二、完整清单

### literal（51 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |
| 1 | `com.chua.common.support.lang.json.JacksonJsonProvider.java:205` | 126 | `objectMapper.registerModule(ClassUtils.forObject("com.fasterxml.jackson.module.paramnames.ParameterNamesModule"));` |
| 2 | `com.chua.common.support.matcher.AntPathMatcher.java:727` | 121 | `throw new IllegalArgumentException("The number of capturing groups in the pattern segment " +` |
| 3 | `com.chua.common.support.service.ServiceBuilder.java:656` | 126 | `throw new IllegalStateException("[service-remote] SSH host 未设置，调用 withHost() 或 toRemote(host,port,user,pwd)");` |
| 4 | `com.chua.common.support.task.scheduler.CronExpression.java:118` | 137 | `throw new IllegalArgumentException("Cron expression must have 6 fields (second minute hour day-of-month month day-of-wee…` |
| 5 | `com.chua.common.support.utils.StringUtils.java:4212` | 148 | `Pattern.compile("(insert\|delete\|update\|select\|create\|drop\|truncate\|grant\|alter\|deny\|revoke\|call\|execute\|exe…` |
| 6 | `com.chua.datalake.support.engine.HttpDatalakeQueryEngine.java:121` | 124 | `throw new UnsupportedOperationException("[datalake-query] HttpDatalakeQueryEngine 不支持 Lambda 查询，请使用 getExecutor()");` |
| 7 | `com.chua.common.support.datasearch.music.spi.impl.TencentMusicSourceProvider.java:180` | 310 | `"musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=…` |
| 8 | `com.chua.common.support.datasearch.music.spi.impl.TencentMusicSourceProvider.java:231` | 148 | `"musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=…` |
| 9 | `com.chua.common.support.datasearch.music.spi.impl.TencentMusicSourceProvider.java:245` | 148 | `"musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=…` |
| 10 | `com.chua.common.support.datasearch.music.spi.impl.TencentMusicSourceProvider.java:290` | 127 | `+ "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0";` |
| 11 | `com.chua.common.support.datasearch.music.spi.impl.TencentMusicSourceProvider.java:327` | 121 | `"Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)")), "req", "data", "track_info");` |
| 12 | `com.chua.common.support.datasearch.video.spi.impl.BilibiliResourceProvider.java:95` | 127 | `.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +` |
| 13 | `com.chua.deeplearning.support.sherpa.SherpaModelRegistrar.java:129` | 128 | `HF + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.int8.onnx",` |
| 14 | `com.chua.playwright.support.NativeEngineProductionTest.java:433` | 134 | `assertFalse((Boolean) ev(p, "function(){ return document.getElementById('sw').getAttribute('aria-checked') === 'true'; "…` |
| 15 | `com.chua.playwright.support.NativeEngineProductionTest.java:436` | 133 | `assertTrue((Boolean) ev(p, "function(){ return document.getElementById('sw').getAttribute('aria-checked') === 'true'; " …` |
| 16 | `com.chua.playwright.support.NativeEngineProductionTest.java:441` | 134 | `assertFalse((Boolean) ev(p, "function(){ return document.getElementById('mi').getAttribute('aria-checked') === 'true'; "…` |
| 17 | `com.chua.playwright.support.NativeEngineProductionTest.java:446` | 128 | `assertTrue((Boolean) ev(p, "function(){ return document.getElementById('tree1').getAttribute('aria-checked') === " +` |
| 18 | `com.chua.filestorage.support.preview.provider.AvroPreviewProvider.java:190` | 127 | `.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 19 | `com.chua.filestorage.support.preview.provider.BlenderPreviewProvider.java:105` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 20 | `com.chua.filestorage.support.preview.provider.CalendarPreviewProvider.java:153` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 21 | `com.chua.filestorage.support.preview.provider.DjvuPreviewProvider.java:504` | 127 | `.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 22 | `com.chua.filestorage.support.preview.provider.EmailPreviewProvider.java:153` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 23 | `com.chua.filestorage.support.preview.provider.FbxPreviewProvider.java:106` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 24 | `com.chua.filestorage.support.preview.provider.HeicPreviewProvider.java:780` | 127 | `.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 25 | `com.chua.filestorage.support.preview.provider.HtmlPreviewProvider.java:31` | 143 | `"html,body{margin:0;padding:12px;height:100%;box-sizing:border-box;font-family:system-ui,-apple-system,BlinkMacSystemFon…` |
| 26 | `com.chua.filestorage.support.preview.provider.HtmlPreviewProvider.java:67` | 122 | `.embeddedCss("html,body{margin:0;padding:0;height:100%;width:100%;overflow:hidden;font-family:system-ui}")` |
| 27 | `com.chua.filestorage.support.preview.provider.IworkPreviewProvider.java:583` | 127 | `.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 28 | `com.chua.filestorage.support.preview.provider.JupyterPreviewProvider.java:236` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 29 | `com.chua.filestorage.support.preview.provider.MobiPreviewProvider.java:772` | 127 | `.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 30 | `com.chua.filestorage.support.preview.provider.OldOfficePreviewProvider.java:694` | 121 | `+ "body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 31 | `com.chua.filestorage.support.preview.provider.OpenDocumentPreviewProvider.java:84` | 168 | `sb.append("<div id=\"app\" style=\"height:100vh;width:100%;display:flex;flex-direction:column;align-items:center;justify…` |
| 32 | `com.chua.filestorage.support.preview.provider.ParquetPreviewProvider.java:116` | 127 | `.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 33 | `com.chua.filestorage.support.preview.provider.PdfViewerPreviewProvider.java:77` | 131 | `+ "#pdf-container{width:100%;min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:8px " +` |
| 34 | `com.chua.filestorage.support.preview.provider.PdfViewerPreviewProvider.java:100` | 122 | `+ "var canvas=document.createElement('canvas');canvas.height=viewport.height;canvas.width=viewport.width;"` |
| 35 | `com.chua.filestorage.support.preview.provider.PsdPreviewProvider.java:58` | 121 | `+ "body{margin:0;background:#1a1a1a;color:#d4d4d4;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 36 | `com.chua.filestorage.support.preview.provider.RawPreviewProvider.java:356` | 127 | `.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 37 | `com.chua.filestorage.support.preview.provider.RtfPreviewProvider.java:203` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 38 | `com.chua.filestorage.support.preview.provider.ShapefilePreviewProvider.java:131` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 39 | `com.chua.filestorage.support.preview.provider.ShapefilePreviewProvider.java:196` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 40 | `com.chua.filestorage.support.preview.provider.SqlitePreviewProvider.java:191` | 121 | `+ "body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 41 | `com.chua.filestorage.support.preview.provider.SvgPreviewProvider.java:74` | 123 | `.embeddedCss("html,body{margin:0;padding:0;height:100%;width:100%;overflow:hidden;font-family:system-ui}" +` |
| 42 | `com.chua.filestorage.support.preview.provider.TextPreviewProvider.java:35` | 132 | `String css = "body{margin:0;padding:16px;font-family:'Georgia',serif;line-height:1.8;color:#333;max-width:720px;margin:0…` |
| 43 | `com.chua.filestorage.support.preview.provider.ThreeModelPreviewProvider.java:60` | 122 | `"<div id=\"loading\" style=\"position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);" +` |
| 44 | `com.chua.filestorage.support.preview.provider.ThreeModelPreviewProvider.java:108` | 132 | `"var r=new m.WebGLRenderer({antialias:true});r.setSize(innerWidth,innerHeight);r.setPixelRatio(devicePixelRatio);" +` |
| 45 | `com.chua.filestorage.support.preview.provider.ThreeModelPreviewProvider.java:167` | 141 | `"if(grps[i].c===0&&grps[i].v==='SECTION'&&i+1<grps.length&&grps[i+1].c===2&&grps[i+1].v==='ENTITIES'){inEnt=true;continu…` |
| 46 | `com.chua.filestorage.support.preview.provider.VCardPreviewProvider.java:154` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 47 | `com.chua.filestorage.support.preview.provider.XMindPreviewProvider.java:396` | 121 | `sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 48 | `com.chua.filestorage.support.preview.provider.XpsPreviewProvider.java:436` | 127 | `.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe " +` |
| 49 | `com.chua.chronicle.support.dispatcher.ChronicleDispatcherProvider.java:123` | 131 | `throw new RuntimeException("Chronicle Queue 初始化失败。请添加 --add-opens 相关 JVM 参数，或使用 directDispatch=true 绕过。topic=" + topic,` |
| 50 | `com.chua.kafka.support.spi.KafkaClientProvider.java:159` | 123 | `return MiddlewareQueryResult.fail("查询表达式为空。支持：topics / brokers / groups / describe <topic> / preview <topic>");` |
| 51 | `com.chua.prometheus.support.engine.PrometheusEngine.java:350` | 121 | `throw new UnsupportedOperationException("Prometheus 引擎为只读数据源，不支持数据操作语句，请使用 queryInstant(promql) 执行 PromQL 即时查询");` |

### other（51 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |
| 1 | `com.chua.alibaba.support.sms.AlibabaSmsMessagePush.java:16` | 123 | `import com.aliyun.dysmsapi20170525.models.QuerySmsTemplateListResponseBody.QuerySmsTemplateListResponseBodySmsTemplateLi…` |
| 2 | `com.chua.dingding.support.bot.DingTalkBotClient.java:804` | 121 | `executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {` |
| 3 | `com.chua.google.support.GoogleChatClient.java:330` | 133 | `configBuilder.systemInstruction(Content.builder().parts(List.of(Part.builder().text(actualSystem).build())).build());` |
| 4 | `com.chua.common.support.config.loader.ConfigValueBindingManager.java:434` | 126 | `ReflectUtils.invoke(binding.getBean(), callback.getName(), callback.getReturnType(), callback.getParameterTypes(),` |
| 5 | `com.chua.common.support.converter.definition.BufferedImageTypeConverter.java:99` | 125 | `try (ByteArrayInputStream arrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray())) {` |
| 6 | `com.chua.common.support.converter.definition.StringArrayTypeConverter.java:54` | 131 | `} else if (stringValue.startsWith(SYMBOL_LEFT_BIG_PARENTHESES) && stringValue.endsWith(SYMBOL_RIGHT_BIG_PARENTHESES)) {` |
| 7 | `com.chua.common.support.lang.json.JsonReference.java:45` | 131 | `return StringUtils.isBlank(json) \|\| !json.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && !json.startsWith(SYMBOL_LEFT_BIG_P…` |
| 8 | `com.chua.common.support.spi.DefaultServiceProvider.java:705` | 137 | `return ProxyUtils.newProxy(service, classLoader, new DelegateMethodIntercept<>(service, new SafeFunction<ProxyMethod, Ob…` |
| 9 | `com.chua.common.support.spi.resolver.CustomServiceResolver.java:105` | 142 | `allExtensionClass.addAll(Optional.ofNullable(readLine(line, url, type, classLoader)).orElse(Collections.emptyList()));` |
| 10 | `com.chua.common.support.spi.ServiceProvider.java:601` | 142 | `return ProxyUtils.newProxy(getType(), getClassLoader(), new DelegateMethodIntercept<>(getType(), new Function<ProxyMetho…` |
| 11 | `com.chua.common.support.spi.ServiceProvider.java:678` | 142 | `return ProxyUtils.newProxy(getType(), getClassLoader(), new DelegateMethodIntercept<>(getType(), new Function<ProxyMetho…` |
| 12 | `com.chua.common.support.utils.ClassUtils.java:2187` | 150 | `int allModes = MethodHandles.Lookup.PUBLIC \| MethodHandles.Lookup.PRIVATE \| MethodHandles.Lookup.PROTECTED \| MethodHa…` |
| 13 | `com.chua.common.support.utils.ClassUtils.java:2189` | 135 | `return (constructor.getParameterCount() == 2 ? ((MethodHandles.Lookup) constructor.newInstance(declaringClass, allModes)…` |
| 14 | `com.chua.common.support.utils.DateUtils.java:1380` | 123 | `return Date.from(localDate.atStartOfDay().atZone(Optional.ofNullable(zoneId).orElse(DEFAULT_ZONE_ID)).toInstant());` |
| 15 | `com.chua.common.support.utils.NumberUtils.java:2374` | 138 | `return (value.startsWith(Hex.PREFIX, index) \|\| value.startsWith(Hex.PREFIX_UPPER, index) \|\| value.startsWith(SYMBOL_…` |
| 16 | `com.chua.common.support.utils.StringUtils.java:94` | 122 | `"                ", "                 ", "                  ", "                   ", "                    "};` |
| 17 | `com.chua.datasource.support.engine.JdbcReactorEngine.java:1000` | 121 | `.flatMap(r -> Flux.from(r.map((row, meta) -> JdbcReactorEngine.this.toObject(row, rowType))))` |
| 18 | `com.chua.deeplearning.support.ai.client.DeeplearningModels.java:76` | 126 | `boolean inputOk = inputType == null \|\| entry.inputType() == null \|\| inputType.isAssignableFrom(entry.inputType());` |
| 19 | `com.chua.deeplearning.support.ai.client.DeeplearningModels.java:77` | 131 | `boolean outputOk = outputType == null \|\| entry.outputType() == null \|\| outputType.isAssignableFrom(entry.outputType(…` |
| 20 | `com.chua.deeplearning.support.recognition.TextDirectionPipeline.java:204` | 128 | `\|\| (RecognitionSupport.contains(name, "ocrv6-det") \|\| RecognitionSupport.contains(name, "paddleocrv6-det"));` |
| 21 | `com.chua.deeplearning.support.zeroshot.ZeroShot.java:156` | 136 | `.filter(id -> id.contains("zero-shot") \|\| id.contains("clip-vit") \|\| id.contains("siglip") \|\| id.contains("mobilec…` |
| 22 | `com.chua.deeplearning.support.zeroshot.ZeroShot.java:167` | 131 | `.filter(id -> id.contains("zero-shot") \|\| id.contains("world") \|\| id.contains("owlv2") \|\| id.contains("grounding")…` |
| 23 | `com.chua.runtime.support.service.SystemdServiceManager.java:307` | 123 | `service.getEnv().forEach((k, v) -> sb.append("Environment=\"").append(k).append("=").append(v).append("\"\n"));` |
| 24 | `com.chua.common.support.datasearch.music.spi.impl.TencentMusicSourceProvider.java:265` | 136 | `.coverUrl(firstNonBlank(text(basic, "cover", "medium_url"), text(basic, "cover_url_medium"), text(basic, "imgurl")))` |
| 25 | `com.chua.common.support.datasearch.video.spi.impl.ProxyHttpClient.java:36` | 121 | `builder.proxy(ProxySelector.of(new InetSocketAddress(parts[0], Integer.parseInt(parts[1]))));` |
| 26 | `com.chua.ast.support.internal.AbstractAstProcessor.java:112` | 143 | `Class.forName( // [P3C 1.10 豁免] javac 编译器内部实现类（com.sun.tools.javac.*），本模块未依赖 utils-support-common-starter，无 ReflectUtils…` |
| 27 | `com.chua.enhance.support.network.server.filter.security.CorsServerFilter.java:67` | 122 | `this.configured = present(origin) \|\| present(methods) \|\| present(headers) \|\| present(age) \|\| present(credentials…` |
| 28 | `com.chua.ffmpeg.support.processor.JaffreeFFmpegProcessor.java:198` | 123 | `.addInput(UrlInput.fromPath(listFile.toPath()).addArguments("-f", "concat").addArguments("-safe", "0"))` |
| 29 | `com.chua.filestorage.support.cache.PreviewPdfCache.java:192` | 122 | `this.convertExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {` |
| 30 | `com.chua.filestorage.support.preview.provider.VCardPreviewProvider.java:210` | 170 | `sb.append("<div class=\"field\"><span class=\"field-label\">电话</span><span class=\"field-value\">").append(escapeHtml(ph…` |
| 31 | `com.chua.filestorage.support.preview.provider.VCardPreviewProvider.java:214` | 170 | `sb.append("<div class=\"field\"><span class=\"field-label\">邮箱</span><span class=\"field-value\">").append(escapeHtml(em…` |
| 32 | `com.chua.filestorage.support.preview.provider.VCardPreviewProvider.java:218` | 180 | `sb.append("<div class=\"field\"><span class=\"field-label\">地址</span><span class=\"field-value\">").append(escapeHtml(co…` |
| 33 | `com.chua.image.support.filter.BscAdjustImageFilter.java:228` | 146 | `(ta << 24) \| (ImageProcessorUtils.clamp(tr) << 16) \| (ImageProcessorUtils.clamp(tg) << 8) \| ImageProcessorUtils.clamp…` |
| 34 | `com.chua.image.support.filter.SepiaToneImageFilter.java:96` | 146 | `(ta << 24) \| (ImageProcessorUtils.clamp(fr) << 16) \| (ImageProcessorUtils.clamp(fg) << 8) \| ImageProcessorUtils.clamp…` |
| 35 | `com.chua.zxing.support.qr.background.EmptinessBackgroundResolver.java:159` | 143 | `return size != 1 && (x > (size(size) - 1) - 9 && x < (size(size) - 1) - 3 && y > (size(size) - 1) - 9 && y < (size(size)…` |
| 36 | `com.chua.zxing.support.qr.draw.eye.ImageDrawEyeResolver.java:62` | 125 | `.getScaledInstance(infoSize * detectCornerSize, infoSize * detectCornerSize, Image.SCALE_SMOOTH),` |
| 37 | `com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils.java:150` | 136 | `QrCodeOptions.FrontImgOptions frontImgOptions) {` |
| 38 | `com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils.java:252` | 132 | `QrCodeOptions.BgImgOptions bgImgOptions) {` |
| 39 | `com.chua.zxing.support.qr.toolkit.QrCodeRenderUtils.java:547` | 125 | `.getScaledInstance(infoSize * detectCornerSize, infoSize * detectCornerSize, Image.SCALE_SMOOTH),` |
| 40 | `com.chua.minio.support.storage.MinioFileStorage.java:151` | 126 | `? Long.parseLong(Objects.requireNonNull(response.headers().get("Content-Length")))` |
| 41 | `com.chua.rpc.support.dubbo.DubboRpcClient.java:73` | 123 | `if (cfg.getParameters() != null && !cfg.getParameters().isEmpty()) { item.setParameters(cfg.getParameters()); }` |
| 42 | `com.chua.rpc.support.dubbo.DubboRpcClient.java:123` | 123 | `if (rpcConsumerConfig.getLoadBalance() != null) { ref.setLoadbalance(rpcConsumerConfig.getLoadBalance()); }` |
| 43 | `com.chua.rpc.support.dubbo.DubboRpcClient.java:126` | 123 | `if (rpcConsumerConfig.getConnections() != null) { ref.setConnections(rpcConsumerConfig.getConnections()); }` |
| 44 | `com.chua.rpc.support.dubbo.DubboRpcServer.java:92` | 132 | `if (config.getParameters() != null && !config.getParameters().isEmpty()) { item.setParameters(config.getParameters()); }` |
| 45 | `com.chua.rpc.support.json.JsonRpcClient.java:154` | 124 | `? 0 : (consumerConfig != null && consumerConfig.getRetries() != null ? consumerConfig.getRetries() : 0);` |
| 46 | `com.chua.rpc.support.sofa.SofaRpcClient.java:85` | 123 | `if (rpcConsumerConfig.getLoadBalance() != null)  { c.setLoadBalancer(rpcConsumerConfig.getLoadBalance()); }` |
| 47 | `com.chua.rpc.support.sofa.SofaRpcClient.java:86` | 124 | `if (rpcConsumerConfig.getConnections() != null)  { c.setConnectionNum(rpcConsumerConfig.getConnections()); }` |
| 48 | `com.chua.rpc.support.sofa.SofaRpcClient.java:87` | 126 | `if (rpcConsumerConfig.getSerialization() != null){ c.setSerialization(rpcConsumerConfig.getSerialization()); }` |
| 49 | `com.chua.rpc.support.sofa.SofaRpcClient.java:89` | 132 | `if (rpcConsumerConfig.getAsync() != null)        { c.setInvokeType(Boolean.TRUE.equals(rpcConsumerConfig.getAsync())` |
| 50 | `com.chua.rpc.support.sofa.SofaRpcClient.java:94` | 129 | `if (rpcConsumerConfig.getConnectTimeout() != null){ c.setConnectTimeout(rpcConsumerConfig.getConnectTimeout()); }` |
| 51 | `com.chua.runtime.core.service.SystemdServiceManager.java:259` | 123 | `service.getEnv().forEach((k, v) -> sb.append("Environment=\"").append(k).append("=").append(v).append("\"\n"));` |

### signature（24 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |
| 1 | `com.chua.common.support.network.server.http.HttpReflectiveDefaultServerHandler.java:22` | 128 | `public class HttpReflectiveDefaultServerHandler extends DefaultHttpServerHandler implements ReflectiveHttpDefaultServerH…` |
| 2 | `com.chua.common.support.utils.AnnotationDefinition.java:99` | 122 | `public static <A extends Annotation> AnnotationDefinition<A> ofAliasResolved(A annotation, Class<A> annotationClass) {` |
| 3 | `com.chua.common.support.utils.AnnotationDefinition.java:110` | 125 | `public static <A extends Annotation> AnnotationDefinition<A> ofOverriddenMethod(A annotation, Class<A> annotationClass) …` |
| 4 | `com.chua.common.support.utils.AnnotationDefinition.java:120` | 124 | `public static <A extends Annotation> AnnotationDefinition<A> subclassOverrides(Class<A> annotationClass, A annotation) {` |
| 5 | `com.chua.common.support.utils.ClassUtils.java:1252` | 142 | `private static <T> T createAlgorithm(Constructor<?> declaredConstructor, Map<Class<?>, Object> params, Map<Constructor<?…` |
| 6 | `com.chua.filesystem.support.stream.ArchiveInputStreamAdapter.java:23` | 146 | `private final org.apache.commons.compress.archivers.ArchiveInputStream<? extends org.apache.commons.compress.archivers.A…` |
| 7 | `com.chua.filesystem.support.stream.ArchiveInputStreamAdapter.java:30` | 167 | `public ArchiveInputStreamAdapter(org.apache.commons.compress.archivers.ArchiveInputStream<? extends org.apache.commons.c…` |
| 8 | `com.chua.spring.support.configuration.ApplicationAwareApplicationContextInitializer.java:13` | 133 | `public class ApplicationAwareApplicationContextInitializer implements ApplicationContextInitializer<ConfigurableApplicat…` |
| 9 | `com.chua.spring.support.proxy.intercept.BulkheadIntercept.java:28` | 121 | `public class BulkheadIntercept extends AbstractMethodAnnotationIntercept implements MethodAnnotationIntercept<Bulkhead> …` |
| 10 | `com.chua.spring.support.proxy.intercept.CircuitBreakerIntercept.java:28` | 133 | `public class CircuitBreakerIntercept extends AbstractMethodAnnotationIntercept implements MethodAnnotationIntercept<Circ…` |
| 11 | `com.chua.spring.support.proxy.intercept.RateLimiterIntercept.java:29` | 127 | `public class RateLimiterIntercept extends AbstractMethodAnnotationIntercept implements MethodAnnotationIntercept<RateLim…` |
| 12 | `com.chua.clickhouse.support.engine.ClickHouseEngine.java:89` | 177 | `@Override public EngineDataSource<HikariDataSource> setSource(Object source) { throw new UnsupportedOperationException("…` |
| 13 | `com.chua.h2.support.engine.H2Engine.java:67` | 157 | `public EngineDataSource<Object> setSource(Object source) { throw new UnsupportedOperationException("运行期不支持替换数据源对象，请重新调用 …` |
| 14 | `com.chua.mysql.support.engine.MysqlEngine.java:92` | 177 | `@Override public EngineDataSource<HikariDataSource> setSource(Object source) { throw new UnsupportedOperationException("…` |
| 15 | `com.chua.oracle.support.engine.OracleEngine.java:91` | 177 | `@Override public EngineDataSource<HikariDataSource> setSource(Object source) { throw new UnsupportedOperationException("…` |
| 16 | `com.chua.postgresql.support.engine.PostgresqlEngine.java:89` | 177 | `@Override public EngineDataSource<HikariDataSource> setSource(Object source) { throw new UnsupportedOperationException("…` |
| 17 | `com.chua.sqlserver.support.engine.SqlServerEngine.java:90` | 177 | `@Override public EngineDataSource<HikariDataSource> setSource(Object source) { throw new UnsupportedOperationException("…` |
| 18 | `com.chua.sqlserver.support.engine.SqlServerLegacyEngine.java:131` | 177 | `@Override public EngineDataSource<HikariDataSource> setSource(Object source) { throw new UnsupportedOperationException("…` |
| 19 | `com.chua.common.support.datasearch.video.model.VideoSyncConfig.java:128` | 131 | `public void setVideoSyncConfigEnabled(Boolean videoSyncConfigEnabled) { this.videoSyncConfigEnabled = videoSyncConfigEna…` |
| 20 | `com.chua.common.support.datasearch.video.model.VideoSyncConfig.java:154` | 161 | `public void setVideoSyncConfigLastAsyncTime(LocalDateTime videoSyncConfigLastAsyncTime) { this.videoSyncConfigLastAsyncT…` |
| 21 | `com.chua.common.support.datasearch.video.model.VideoSyncConfig.java:167` | 130 | `public void setVideoSyncConfigHeaders(String videoSyncConfigHeaders) { this.videoSyncConfigHeaders = videoSyncConfigHead…` |
| 22 | `com.chua.common.support.datasearch.video.model.VideoSyncConfig.java:180` | 126 | `public void setVideoSyncConfigRemark(String videoSyncConfigRemark) { this.videoSyncConfigRemark = videoSyncConfigRemark;…` |
| 23 | `com.chua.playwright.support.Page.java:256` | 121 | `public void waitForSelector(String selector, long timeoutMs) { engine.waitForSelector(handle, selector, timeoutMs); }` |
| 24 | `com.chua.zxing.support.qr.background.EmptinessBackgroundResolver.java:122` | 150 | `private static void fillPositionDetectionShape(Graphics2D g, Color eyeDf,Color lf, Color lb, int qrCodeWidth, int startX…` |

### regex（13 处）

| # | 文件:行 | 字符数 | 代码片段 |
| --- | --- | --- | --- |
| 1 | `com.chua.common.support.constant.RegexConstant.java:34` | 483 | `"(?:[a-z0-9!#$%&'*+/=?^_`{\|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{\|}~-]+)*\|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23…` |
| 2 | `com.chua.common.support.constant.RegexConstant.java:105` | 158 | `"^(25[0-5]\|2[0-4]\\d\|[0-1]?\\d?\\d)\\.(25[0-5]\|2[0-4]\\d\|[0-1]?\\d?\\d)\\.(25[0-5]\|2[0-4]\\d\|[0-1]?\\d?\\d)\\.(25[…` |
| 3 | `com.chua.common.support.constant.RegexConstant.java:118` | 201 | `"4})?:)?((25[0-5]\|(2[0-4]\|1?[0-9])?[0-9])\\.){3}(25[0-5]\|(2[0-4]\|1?[0-9])?[0-9])\|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]…` |
| 4 | `com.chua.common.support.constant.RegexConstant.java:124` | 122 | `"([1-9]\|[1-9]\\\\d\|1\\\\d{2}\|2[0-4]\\\\d\|25[0-5])(\\\\.(\\\\d\|[1-9]\\\\d\|1\\\\d{2}\|2[0-4]\\\\d\|25[0-5])){3}");` |
| 5 | `com.chua.common.support.lang.compile.Compiler.java:61` | 182 | `Pattern METHOD_PATTERN = Pattern.compile("(private\|public\|protect)\\s+(([a-zA-z][$_a-zA-z0-9.]*)(<(.*?)>)*)\\s+([a-zA-…` |
| 6 | `com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter.java:279` | 122 | `"(?i)([`\"]?(?!NULL\\b\|NOT\\b\|NULL_\|PRIMARY\\b\|UNIQUE\\b\|KEY\\b\|DEFAULT\\b\|CONSTRAINT\\b)[A-Za-z_]\\w*[`\"]?)"` |
| 7 | `com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter.java:499` | 127 | `"(?is)^ALTER\\s+TABLE\\s+[`\"]?([A-Za-z_]\\w*)[`\"]?\\s+ADD\\s+COLUMN\\s+[`\"]?([A-Za-z_]\\w*)[`\"]?\\s+(.+)$")` |
| 8 | `com.chua.common.support.utils.StringUtils.java:4213` | 177 | `"\\s+.*(into\|from\|set\|where\|table\|database\|view\|index\|on\|cursor\|procedure\|trigger\|for\|password\|union\|and\…` |
| 9 | `com.chua.common.support.utils.Validator.java:42` | 155 | `"^(25[0-5]\|2[0-4]\\d\|[01]?\\d\\d?)\\.(25[0-5]\|2[0-4]\\d\|[01]?\\d\\d?)\\.(25[0-5]\|2[0-4]\\d\|[01]?\\d\\d?)\\.(25[0-5…` |
| 10 | `com.chua.calcite.support.datasource.EngineUpdateSqlExecutor.java:35` | 157 | `"(?is)^\\s*UPDATE\\s+(?:(?:`([^`]+)`\|([A-Za-z_][\\w$]*))\\s*\\.\\s*)?(?:`([^`]+)`\|([A-Za-z_][\\w$]*))\\s+SET\\s+(.+?)(…` |
| 11 | `com.chua.sqlserver.support.flyway.SqlServerScriptConverter.java:130` | 123 | `+ "([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)\\s+ON\\s+([\"\\[]?[A-Za-z_]\\w*[\"\\]]?)\\s*(\\(.*\\))\\s*;?\\s*$");` |
| 12 | `com.chua.kimi.support.KimiChatClient.java:801` | 126 | `"!\\[.*?\\]\\((https?://[^)]+)\\)\|https?://[^\\s)\"'<>]+(?:\\.(?:png\|jpg\|jpeg\|webp\|gif))(?:\\?[^\\s)\"'<>]*)?"` |
| 13 | `com.chua.playwright.support.qwen.QwenProxyChatClient.java:601` | 126 | `"!\\[.*?\\]\\((https?://[^)]+)\\)\|https?://[^\\s)\"'<>]+(?:\\.(?:png\|jpg\|jpeg\|webp\|gif))(?:\\?[^\\s)\"'<>]*)?"` |

## 三、处理优先级建议

1. **signature（24 处）**：最容易改且收益直接——把全限定名改 import 后多数可落到 120 以内。
2. **other（51 处）**：需要按语义拆分，建议随功能迭代顺手处理。
3. **literal（51 处）**：多为 HTML/CSS/提示词模板，拆分会降低可读性，建议仅在必须时提取常量。
4. **regex（13 处）**：拆分会破坏正则可读性，建议保留；如需复用可提取为 `static final Pattern`。
