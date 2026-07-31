package com.chua.ast.support.processor;


import com.chua.ast.support.annotation.AutoClose;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * {@link AutoClose} 注解的 AST 处理器
 * <p>
 * 在编译期扫描标注了 {@code @AutoClose} 注解的方法参数，通过 javac Tree API
 * 在方法外部包裹 try-finally 块，在 finally 中实现 null 检查 + close() 调用。
 * </p>
 * <p>
 * 转换示例：
 * <pre>{@code
 * // 转换前：public void process(@AutoClose InputStream is) { is.read(); }
 * // 转换后：
 * public void process(InputStream is) {
 *     try {
 *         is.read();
 *     } finally {
 *         if (is != null) {
 *             try { is.close(); } catch (Exception e) { /* suppressed *&#47; }
 *         }
 *     }
 * }
 * }</pre>
 * </p>
 *
 * @author CH
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.AutoClose")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class AutoCloseAstProcessor extends AbstractProcessor {

    private com.sun.source.util.Trees trees;
    private Messager messager;
    private ProcessingEnvironment pe;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.pe = processingEnv;
        this.messager = processingEnv.getMessager();
        try {
            this.trees = com.sun.source.util.Trees.instance(processingEnv);
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "无法获取 Trees 实例: " + e.getMessage());
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || trees == null) {
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(AutoClose.class)) {
            if (!(element instanceof VariableElement paramElement)) {
                continue;
            }
            Element enclosing = paramElement.getEnclosingElement();
            if (!(enclosing instanceof ExecutableElement methodElement)) {
                continue;
            }

            // 检查参数类型是否实现了 AutoCloseable
            TypeMirror paramType = paramElement.asType();
            if (!isAutoCloseable(paramType)) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "参数 "
                                + paramElement.getSimpleName() + " 类型 " + paramType + " 未实现 AutoCloseable",
                        element);
                continue;
            }

            String paramName = paramElement.getSimpleName().toString();

            com.sun.source.tree.Tree methodTree = trees.getTree(methodElement);
            if (!(methodTree instanceof com.sun.source.tree.MethodTree)) {
                continue;
            }

            try {
                applyAutoCloseTransform((com.sun.source.tree.MethodTree) methodTree,
                        paramName);
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "已为参数 " + paramName + " 应用 AutoClose 转换", element);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "AutoClose 转换失败：参数 " + paramName + "，错误：" + e.getMessage(), element);
            }
        }
        return false;
    }

    /**
     * 判断 TypeMirror 是否为 AutoCloseable 类型
     *
     * @param type 类型镜像
     * @return 如果是 AutoCloseable 返回 true，否则返回 false
     */
    private boolean isAutoCloseable(TypeMirror type) {
        TypeElement autoCloseableElement = pe.getElementUtils()
                .getTypeElement("java.lang.AutoCloseable");
        if (autoCloseableElement == null) {
            return false;
        }
        TypeMirror autoCloseableType = autoCloseableElement.asType();
        return pe.getTypeUtils().isAssignable(type, autoCloseableType)
                || pe.getTypeUtils().isSubtype(type, autoCloseableType);
    }

    /**
     * 应用 AutoClose 编译期转换，将方法体包裹进 try-finally 块
     * <pre>{@code
     * try {
     *     // 原始方法体
     * } finally {
     *     if (param != null) {
     *         try { param.close(); } catch (Exception e) { /* suppressed *&#47; }
     *     }
     * }
     * }</pre>
     *
     * @param methodTree 方法树节点
     * @param paramName  参数名称
     */
    @SuppressWarnings("unchecked")
    private void applyAutoCloseTransform(com.sun.source.tree.MethodTree methodTree,
                                         String paramName) throws Exception {

        var jcMethod = AstUtils.asJcMethodDecl(methodTree);
        if (jcMethod == null || jcMethod.body == null) {
            return;
        }

        var maker = AstUtils.getTreeMaker(pe);
        var names = AstUtils.getNames(pe);

        // 保存原始方法体语句
        var originalStats = jcMethod.body.stats;

        // 构建 finally 块：if (param != null) { try { param.close(); } catch (Exception e) {} }
        var finallyBlock = buildFinallyBlock(maker, names, paramName);

        // 构建 try-finally: try { originalStats } finally { finallyBlock }
        var tryBody = maker.Block(0, originalStats);
        var tryFinally = maker.Try(tryBody,
                com.sun.tools.javac.util.List.nil(),
                finallyBlock);

        // 替换方法体
        jcMethod.body = maker.Block(0, com.sun.tools.javac.util.List.of(tryFinally));
    }

    /**
     * 构建 finally 块中的关闭逻辑
     * <pre>{@code
     * if (param != null) {
     *     try { param.close(); } catch (Exception e) { /* suppressed *&#47; }
     * }
     * }</pre>
     *
     * @param maker     TreeMaker 实例
     * @param names     Names 实例
     * @param paramName 参数名称
     * @return finally 代码块
     */
    private com.sun.tools.javac.tree.JCTree.JCBlock buildFinallyBlock(com.sun.tools.javac.tree.TreeMaker maker,
                                                                      com.sun.tools.javac.util.Names names,
                                                                      String paramName) {

        // param.close()
        var paramIdent = AstUtils.makeIdent(maker, names, paramName);
        var closeSelect = maker.Select(paramIdent, names.fromString("close"));
        var closeCall = maker.Apply(com.sun.tools.javac.util.List.nil(),
                closeSelect,
                com.sun.tools.javac.util.List.nil());

        // try { param.close(); } catch (Exception e) {}
        var closeStmt = maker.Exec(closeCall);
        var tryBody = maker.Block(0, com.sun.tools.javac.util.List.of(closeStmt));

        // catch (Exception e) { /* empty */ }
        var exceptionType = maker.Ident(names.fromString("Exception"));
        var catchVar = maker.VarDef(maker.Modifiers(0),
                names.fromString("e"),
                exceptionType,
                null);
        var catchBlock = maker.Block(0, com.sun.tools.javac.util.List.nil());
        var catcher = maker.Catch(catchVar, catchBlock);

        var innerTry = maker.Try(tryBody,
                com.sun.tools.javac.util.List.of(catcher),
                null);

        // if (param != null) { innerTry }
        var paramIdent2 = AstUtils.makeIdent(maker, names, paramName);
        var nullLit = AstUtils.makeNullLiteral(maker);
        var notNullCondition = maker.Binary(com.sun.tools.javac.tree.JCTree.Tag.NE,
                paramIdent2, nullLit);
        var thenBlock = maker.Block(0, com.sun.tools.javac.util.List.of(innerTry));
        var ifStmt = maker.If(notNullCondition, thenBlock, null);

        return maker.Block(0, com.sun.tools.javac.util.List.of(ifStmt));
    }
}
