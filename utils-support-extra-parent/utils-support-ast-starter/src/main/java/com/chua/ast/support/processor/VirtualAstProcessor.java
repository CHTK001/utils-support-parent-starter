package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.Virtual;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * {@link Virtual} 注解的 AST 处理器
 * <p>
 * 在编译期扫描标注了 {@code @Virtual} 注解的 Void Linux 方法，通过 javac 树 API
 * 将方法体替换为 {@code Thread.startVirtualThread()} 的 Runnable lambda 调用。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.Virtual")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class VirtualAstProcessor extends AbstractProcessor {

    /** 抽象语法树工具 */
    private com.sun.source.util.Trees trees;
    /** 消息器 */
    private Messager messager;
    /** 注解处理环境 */
    private ProcessingEnvironment pe;

    @Override
    /** 初始化 */
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
    /** 处理 */
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || trees == null) { return false; }

        for (Element element : roundEnv.getElementsAnnotatedWith(Virtual.class)) {
            if (!(element instanceof ExecutableElement methodElement)) { continue; }

            if (methodElement.getReturnType().getKind() != TypeKind.VOID) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "@Virtual 仅支持 void 返回类型的方法：" + methodElement.getSimpleName(),
                        element);
                continue;
            }

            com.sun.source.tree.Tree methodTree = trees.getTree(methodElement);
            if (!(methodTree instanceof com.sun.source.tree.MethodTree)) { continue; }

            String methodName = methodElement.getSimpleName().toString();

            try {
                applyVirtualTransform((com.sun.source.tree.MethodTree) methodTree);
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "已为方法 " + methodName + " 应用 @Virtual 转换", element);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "@Virtual 转换失败：方法 " + methodName + "，错误：" + e.getMessage(), element);
            }
        }
        return false;
    }

    /**
     * 应用虚拟转换
     *
     * @param methodTree 方法树
     */
    private void applyVirtualTransform(com.sun.source.tree.MethodTree methodTree) throws Exception {

        var jcMethod = AstUtils.asJcMethodDecl(methodTree);
        if (jcMethod == null || jcMethod.body == null) { return; }

        var maker = AstUtils.getTreeMaker(pe);
        var names = AstUtils.getNames(pe);

        var originalStats = jcMethod.body.stats;

        var tryBody = maker.Block(0, originalStats);

        var exceptionType = maker.Ident(names.fromString("Exception"));
        var catchVar = maker.VarDef(maker.Modifiers(0),
                names.fromString("e"),
                exceptionType,
                null);

        var rteSelect = maker.Ident(names.fromString("RuntimeException"));
        var rteNew = maker.NewClass(null,
                com.sun.tools.javac.util.List.nil(),
                rteSelect,
                com.sun.tools.javac.util.List.of(maker.Ident(names.fromString("e"))),
                null);
        var throwStmt = maker.Throw(rteNew);
        var catchBlock = maker.Block(0, com.sun.tools.javac.util.List.of(throwStmt));
        var catcher = maker.Catch(catchVar, catchBlock);

        var wrappedBody = maker.Try(tryBody,
                com.sun.tools.javac.util.List.of(catcher),
                null);

        var lambdaBody = maker.Block(0, com.sun.tools.javac.util.List.of(wrappedBody));
        var lambda = maker.Lambda(com.sun.tools.javac.util.List.nil(), lambdaBody);

        var threadType = maker.Ident(names.fromString("Thread"));
        var methodSelect = maker.Select(threadType, names.fromString("startVirtualThread"));
        var call = maker.Apply(com.sun.tools.javac.util.List.nil(),
                methodSelect,
                com.sun.tools.javac.util.List.of(lambda));
        var callStmt = maker.Exec(call);

        jcMethod.body = maker.Block(0, com.sun.tools.javac.util.List.of(callStmt));
    }
}
