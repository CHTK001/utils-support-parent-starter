package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.Timed;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * {@link Timed} 注解的 AST 处理器
 * <p>
   * 在编译期扫描标注了 {@code @Timed} 注解的方法，通过 javac 树 API
   * 在方法中插入 尝试-最终 块，实现执行耗时统计。
 * </p>
 * <p>
   * 输出策略（编译期检测 类路径）：
 * <ul>
 *   <li>slf4j 存在 → 优先使用 {@code log.info()}（如果类中有 log 字段），
   * 否则使用 {@code org.slf4j.LoggerFactory.getLogger()} 获取 日志记录器</li>
 *   <li>slf4j 不存在 → 使用 {@code System.out.println()}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.Timed")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class TimedAstProcessor extends AbstractProcessor {

    /** Slf4j 日志字段名称 */
    private static final String SLF4J_LOGGER = "org.slf4j.Logger";
    /** Slf4j 日志工厂字段名称 */
    private static final String SLF4J_LOGGER_FACTORY = "org.slf4j.LoggerFactory";
    /** Slf4j 注解名称 */
    private static final String SLF4J_ANNOTATION = "lombok.extern.slf4j.Slf4j";
    /** 日志字段名称 */
    private static final String LOG_FIELD_NAME = "log";

    /** 抽象语法树工具 */
    private com.sun.source.util.Trees trees;
    /** 消息器 */
    private Messager messager;
    /** 注解处理环境 */
    private ProcessingEnvironment pe;
    /** 依赖探测器 */
    private DependencyDetector dependencyDetector;

    @Override
    /** 初始化 */
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.pe = processingEnv;
        this.messager = processingEnv.getMessager();
        this.dependencyDetector = new DependencyDetector(processingEnv);
        try {
            this.trees = com.sun.source.util.Trees.instance(processingEnv);
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "无法获取 Trees 实例: " + e.getMessage());
        }
    }

    @Override
    /** 处理 */
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || trees == null) {
            return false;
        }

        // 编译期检测 slf4j 是否可用
        boolean slf4jAvailable = dependencyDetector.isPresent(SLF4J_LOGGER)
                && dependencyDetector.isPresent(SLF4J_LOGGER_FACTORY);

        for (Element element : roundEnv.getElementsAnnotatedWith(Timed.class)) {
            if (!(element instanceof ExecutableElement methodElement)) {
                continue;
            }

            if (methodElement.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "抽象方法不支持 @Timed：" + methodElement.getSimpleName(),
                        element);
                continue;
            }

            com.sun.source.tree.Tree methodTree = trees.getTree(methodElement);
            if (methodTree == null) {
                continue;
            }

            String methodName = methodElement.getSimpleName().toString();

 // 检查类中是否存在 日志 字段
            boolean hasLogField = hasLogField(methodElement);

            try {
                applyTimedTransform((com.sun.source.tree.MethodTree) methodTree,
                        methodName, slf4jAvailable, hasLogField);
                String outputMode = slf4jAvailable ? (hasLogField ? "log.info" : "LoggerFactory") : "System.out";
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "已为方法 " + methodName + " 应用 @Timed 转换（输出=" + outputMode + "）", element);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "@Timed 转换失败：方法 " + methodName + "，错误：" + e.getMessage(), element);
            }
        }
        return false;
    }

    /**
      * 检查方法所属的类是否有名为 "日志" 的字段
     * @param methodElement 方法element
     * @return 是否包含日志字段的结果
     */
    private boolean hasLogField(ExecutableElement methodElement) {
        Element enclosing = methodElement.getEnclosingElement();
        if (!(enclosing instanceof TypeElement classElement)) {
            return false;
        }

        // 检查 @Slf4j 注解
        for (AnnotationMirror annotationMirror : classElement.getAnnotationMirrors()) {
            if (SLF4J_ANNOTATION.equals(annotationMirror.getAnnotationType().toString())) {
                return true;
            }
        }

 // 检查类中是否有名为 日志 的字段
        for (Element member : classElement.getEnclosedElements()) {
            if (member.getKind() == ElementKind.FIELD
                    && member.getSimpleName().toString().equals(LOG_FIELD_NAME)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 应用 @Timed 编译期转换，插入计时逻辑
     */
    private void applyTimedTransform(com.sun.source.tree.MethodTree methodTree,
                                     String methodName, boolean slf4jAvailable, boolean hasLogField) throws Exception {

        var jcMethod = AstUtils.asJcMethodDecl(methodTree);
        if (jcMethod == null || jcMethod.body == null) {
            return;
        }

        var maker = AstUtils.getTreeMaker(pe);
        var names = AstUtils.getNames(pe);

        var originalStats = jcMethod.body.stats;

        // long _timedStart = System.nanoTime();
        var startVarDecl = buildNanoTimeVarDecl(maker, names, "_timedStart");

 // 最终 块
        var finallyBlock = buildFinallyBlock(maker, names, methodName, "_timedStart", slf4jAvailable, hasLogField);

 // 尝试 { 原始stats } 最终 { 最终block }
        var tryBody = maker.Block(0, originalStats);
        var tryFinally = maker.Try(tryBody, com.sun.tools.javac.util.List.nil(), finallyBlock);

        jcMethod.body = maker.Block(0, com.sun.tools.javac.util.List.from(
                new com.sun.tools.javac.tree.JCTree.JCStatement[]{startVarDecl, tryFinally}));
    }

    /**
      * 构建nano时间vardecl
     * @param maker maker
     * @param names 名称
     * @param varName var名称
     * @param nanoTimeSelect nano时间选择
     * @param nanoTimeCall nano时间call
     * @param maker maker
     * @param names 名称
     * @param methodName 方法名称
     * @param startVarName 启动var名称
     * @param slf4jAvailable slf4j可用
     * @param hasLogField 是否包含日志字段
     * @param nanoTimeSelect nano时间选择
     * @param nanoTimeCall nano时间call
     * @param startIdent 启动ident
     * @param subtraction subtraction
     * @param 1_000_000L 1_000_000L
     * @param elapsedIdent elapsedident
     * @param divisor divisor
     * @param names 名称
     * @param methodName 方法名称
     * @param msValue ms值
     * @param names 名称
     * @param methodName 方法名称
     * @param msValue ms值
     * @param names 名称
     * @param logInfoStmt 日志信息stmt
     * @param stdoutFallback stdout降级
     * @param names 名称
     * @param methodName 方法名称
     * @param msValue ms值
     * @param names 名称
     * @param methodName 方法名称
     * @param msValue ms值
     * @param names 名称
     * @param loggerInfoStmt 日志记录器信息stmt
     * @param stdoutFallback stdout降级
     * @param names 名称
     * @param methodName 方法名称
     * @param msValue ms值
     * @param msValue ms值
     * @param maker maker
     * @param names 名称
     * @param methodName 方法名称
     * @param msValue ms值
     * @param infoSelect 信息选择
     * @param msValue ms值
     * @param maker maker
     * @param names 名称
     * @param methodName 方法名称
     * @param msValue ms值
     * @param getLoggerSelect 获取日志记录器选择
     * @param infoSelect 信息选择
     * @param maker maker
     * @param names 名称
     * @param methodName 方法名称
     * @param msValue ms值
     * @param prefix 前缀
     * @param msValue ms值
     * @param concat1 连接1
     * @param suffix 后缀
     * @param printlnSelect println选择
     * @param maker maker
     * @param names 名称
     * @param guardedStmt guardedstmt
     * @param fallbackStmt 降级stmt
     * @param ncdfeType ncdfe类型
     * @param null 空
     * @param catchBlock 卡扣block
     * @param null 空
     */
    private com.sun.tools.javac.tree.JCTree.JCVariableDecl buildNanoTimeVarDecl(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names, String varName) {

        var systemIdent = maker.Ident(names.fromString("System"));
        var nanoTimeSelect = maker.Select(systemIdent, names.fromString("nanoTime"));
        var nanoTimeCall = maker.Apply(com.sun.tools.javac.util.List.nil(),
                nanoTimeSelect, com.sun.tools.javac.util.List.nil());

        return maker.VarDef(maker.Modifiers(0),
                names.fromString(varName),
                maker.TypeIdent(com.sun.tools.javac.code.TypeTag.LONG),
                nanoTimeCall);
    }

    /**
      * 构建 最终 块
     */
    private com.sun.tools.javac.tree.JCTree.JCBlock buildFinallyBlock(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names,
            String methodName, String startVarName, boolean slf4jAvailable, boolean hasLogField) {

        // long _timedElapsed = System.nanoTime() - _timedStart;
        var systemIdent = maker.Ident(names.fromString("System"));
        var nanoTimeSelect = maker.Select(systemIdent, names.fromString("nanoTime"));
        var nanoTimeCall = maker.Apply(com.sun.tools.javac.util.List.nil(),
                nanoTimeSelect, com.sun.tools.javac.util.List.nil());
        var startIdent = maker.Ident(names.fromString(startVarName));
        var subtraction = maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.MINUS, nanoTimeCall, startIdent);
        var elapsedVarDecl = maker.VarDef(maker.Modifiers(0),
                names.fromString("_timedElapsed"),
                maker.TypeIdent(com.sun.tools.javac.code.TypeTag.LONG),
                subtraction);

 // _时间elapsed / 1_000_000L
        var elapsedIdent = maker.Ident(names.fromString("_timedElapsed"));
        var divisor = maker.Literal(com.sun.tools.javac.code.TypeTag.LONG, 1_000_000L);
        var msValue = maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.DIV, elapsedIdent, divisor);

        // 输出语句
        com.sun.tools.javac.tree.JCTree.JCStatement outputStmt;
        if (slf4jAvailable && hasLogField) {
            // 有 log 字段：log.info(...) + NoClassDefFoundError 兜底
            var logInfoStmt = buildLogInfoStmt(maker, names, methodName, msValue);
            var stdoutFallback = buildStdoutPrintln(maker, names, methodName, msValue);
            outputStmt = buildNoClassDefFoundGuard(maker, names, logInfoStmt, stdoutFallback);
        } else if (slf4jAvailable) {
            // slf4j 可用但无 log 字段：LoggerFactory.getLogger() + NoClassDefFoundError 兜底
            var loggerInfoStmt = buildLoggerFactoryInfoStmt(maker, names, methodName, msValue);
            var stdoutFallback = buildStdoutPrintln(maker, names, methodName, msValue);
            outputStmt = buildNoClassDefFoundGuard(maker, names, loggerInfoStmt, stdoutFallback);
        } else {
 // slf4j 不可用：系统.出.println
            outputStmt = buildStdoutPrintln(maker, names, methodName, msValue);
        }

        return maker.Block(0, com.sun.tools.javac.util.List.from(
                new com.sun.tools.javac.tree.JCTree.JCStatement[]{elapsedVarDecl, outputStmt}));
    }

    /**
      * 构建 日志.信息 输出：{@code log.info("method 执行耗时: {} ms", ms值)}
     */
    private com.sun.tools.javac.tree.JCTree.JCStatement buildLogInfoStmt(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names,
            String methodName, com.sun.tools.javac.tree.JCTree.JCExpression msValue) {

        var logIdent = maker.Ident(names.fromString("log"));
        var infoSelect = maker.Select(logIdent, names.fromString("info"));
        var template = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS,
                methodName + " 执行耗时: {} ms");
        var infoCall = maker.Apply(com.sun.tools.javac.util.List.nil(), infoSelect,
                com.sun.tools.javac.util.List.from(
                        new com.sun.tools.javac.tree.JCTree.JCExpression[]{template, msValue}));
        return maker.Exec(infoCall);
    }

    /**
      * 构建 日志记录器工厂.获取日志记录器() + 信息 输出：
     * {@code org.slf4j.LoggerFactory.getLogger(Class.class).info("method 执行耗时: {} ms", msValue)}
     */
    private com.sun.tools.javac.tree.JCTree.JCStatement buildLoggerFactoryInfoStmt(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names,
            String methodName, com.sun.tools.javac.tree.JCTree.JCExpression msValue) {

 // org.slf4j.日志记录器工厂
        var slf4jPkg = maker.Select(maker.Ident(names.fromString("org")), names.fromString("slf4j"));
        var loggerFactoryClass = maker.Select(slf4jPkg, names.fromString("LoggerFactory"));
        var getLoggerSelect = maker.Select(loggerFactoryClass, names.fromString("getLogger"));

 // 类.类
        var classIdent = maker.Ident(names.fromString("Class"));
        var classDotClass = maker.Select(classIdent, names.fromString("class"));

        var getLoggerCall = maker.Apply(com.sun.tools.javac.util.List.nil(),
                getLoggerSelect, com.sun.tools.javac.util.List.of(classDotClass));

        // .info(...)
        var infoSelect = maker.Select(getLoggerCall, names.fromString("info"));
        var template = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS,
                methodName + " 执行耗时: {} ms");
        var infoCall = maker.Apply(com.sun.tools.javac.util.List.nil(), infoSelect,
                com.sun.tools.javac.util.List.from(
                        new com.sun.tools.javac.tree.JCTree.JCExpression[]{template, msValue}));
        return maker.Exec(infoCall);
    }

    /**
      * 构建 系统.出.println 输出：{@code System.out.println("method 执行耗时: " + msValue + " ms")}
     */
    private com.sun.tools.javac.tree.JCTree.JCStatement buildStdoutPrintln(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names,
            String methodName, com.sun.tools.javac.tree.JCTree.JCExpression msValue) {

        var systemIdent = maker.Ident(names.fromString("System"));
        var outSelect = maker.Select(systemIdent, names.fromString("out"));

        var prefix = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, methodName + " 执行耗时: ");
        var suffix = maker.Literal(com.sun.tools.javac.code.TypeTag.CLASS, " ms");

        var concat1 = maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.PLUS, prefix, msValue);
        var concat2 = maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.PLUS, concat1, suffix);

        var printlnSelect = maker.Select(outSelect, names.fromString("println"));
        var printlnCall = maker.Apply(com.sun.tools.javac.util.List.nil(), printlnSelect,
                com.sun.tools.javac.util.List.from(
                        new com.sun.tools.javac.tree.JCTree.JCExpression[]{concat2}));
        return maker.Exec(printlnCall);
    }

    /**
      * 构建 no类deffound错误 保护
     */
    private com.sun.tools.javac.tree.JCTree.JCTry buildNoClassDefFoundGuard(
            com.sun.tools.javac.tree.TreeMaker maker, com.sun.tools.javac.util.Names names,
            com.sun.tools.javac.tree.JCTree.JCStatement guardedStmt,
            com.sun.tools.javac.tree.JCTree.JCStatement fallbackStmt) {

        var tryBody = maker.Block(0, com.sun.tools.javac.util.List.from(
                new com.sun.tools.javac.tree.JCTree.JCStatement[]{guardedStmt}));

        var ncdfeType = maker.Ident(names.fromString("NoClassDefFoundError"));
        var catchVar = maker.VarDef(maker.Modifiers(0), names.fromString("e"), ncdfeType, null);
        var catchBlock = maker.Block(0, com.sun.tools.javac.util.List.from(
                new com.sun.tools.javac.tree.JCTree.JCStatement[]{fallbackStmt}));
        var catcher = maker.Catch(catchVar, catchBlock);

        return maker.Try(tryBody,
                com.sun.tools.javac.util.List.from(
                        new com.sun.tools.javac.tree.JCTree.JCCatch[]{catcher}),
                null);
    }
}
