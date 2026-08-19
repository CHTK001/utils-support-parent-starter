package com.chua.ast.support.processor;

import com.chua.ast.support.annotation.Retry;
import com.chua.ast.support.internal.AbstractAstProcessor;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/**
 * {@link Retry} 注解的 AST 处理器
 *
 * @since 4.0.0.42
 */
@SupportedAnnotationTypes("com.chua.ast.support.annotation.Retry")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class RetryAstProcessor extends AbstractAstProcessor {

    @Override
    /** 处理 */
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || !isTreeApiAvailable()) {
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(Retry.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                continue;
            }
            processMethod((ExecutableElement) element);
        }

        return false;
    }

    /** 处理Method */
    private void processMethod(ExecutableElement methodElement) {
        com.sun.source.tree.Tree tree = trees.getTree(methodElement);
        if (!(tree instanceof JCTree.JCMethodDecl jcMethod)) {
            return;
        }

        JCTree.JCBlock body = jcMethod.body;
        if (body == null) {
            return;
        }

        TreeMaker maker = getTreeMaker();
        if (maker == null) {
            return;
        }
        Names names = getNames(maker);
        if (names == null) {
            return;
        }

        Retry retry = methodElement.getAnnotation(Retry.class);
        if (retry == null) {
            return;
        }

        int times = retry.times();
        long delay = retry.delay();
        long maxDelay = retry.maxDelay();
        Retry.RetryStrategy strategy = retry.strategy();

        if (times <= 0) {
            note("@Retry times 必须大于 0，已忽略重试", methodElement);
            return;
        }
        if (delay < 0) {
            delay = 0;
        }
        if (maxDelay < delay) {
            maxDelay = delay;
        }

        JCTree.JCStatement retryStatement = buildRetryStatement(maker, names, body, times, delay, maxDelay, strategy);
        if (retryStatement != null) {
            jcMethod.body = maker.Block(0, com.sun.tools.javac.util.List.of(retryStatement));
            note("已为方法 " + methodElement.getSimpleName() + " 插入 @Retry 重试逻辑", methodElement);
        }
    }

    /**
     * 构建RetryStatement
     * @param maker maker
     * @param names names
     * @param originalBody originalBody
     * @param times times
     * @param delay delay
     * @param maxDelay maxDelay
     * @param strategy strategy
     */
    private JCTree.JCStatement buildRetryStatement(TreeMaker maker, Names names,
                                                     JCTree.JCBlock originalBody, int times, long delay, long maxDelay,
                                                     Retry.RetryStrategy strategy) {
        JCTree.JCVariableDecl retryCountVar = maker.VarDef(
                maker.Modifiers(0),
                names.fromString("_retryCount"),
                maker.TypeIdent(com.sun.tools.javac.code.TypeTag.INT),
                maker.Literal(com.sun.tools.javac.code.TypeTag.INT, 0));

        JCTree.JCIdent retryCountIdent = maker.Ident(names.fromString("_retryCount"));
        JCTree.JCLiteral timesLit = maker.Literal(com.sun.tools.javac.code.TypeTag.INT, times);
        JCTree.JCBinary loopCondition = maker.Binary(JCTree.Tag.LT, retryCountIdent, timesLit);

        JCTree.JCUnary increment = maker.Unary(JCTree.Tag.POSTINC, retryCountIdent);
        JCTree.JCExpressionStatement incrementStmt = maker.Exec(increment);

        com.sun.tools.javac.util.ListBuffer<JCTree.JCStatement> tryStats = new com.sun.tools.javac.util.ListBuffer<>();
        boolean hasReturn = false;
        for (JCTree.JCStatement stmt : originalBody.stats) {
            tryStats.append(stmt);
            if (stmt instanceof JCTree.JCReturn) {
                hasReturn = true;
            }
        }
        // 只有当方法体没有 return 语句时才添加 break
        if (!hasReturn) {
            tryStats.append(maker.Break(null));
        }
        JCTree.JCBlock tryBody = maker.Block(0, tryStats.toList());

        JCTree.JCVariableDecl exceptionVar = maker.VarDef(
                maker.Modifiers(0),
                names.fromString("e"),
                maker.Ident(names.fromString("Exception")),
                null);

        JCTree.JCStatement delayStatement;
        if (strategy == Retry.RetryStrategy.EXPONENTIAL) {
            JCTree.JCExpression exponentialDelay = maker.Binary(JCTree.Tag.MUL,
                    maker.Literal(com.sun.tools.javac.code.TypeTag.LONG, delay),
                    maker.Binary(JCTree.Tag.SL, maker.Literal(com.sun.tools.javac.code.TypeTag.INT, 1), retryCountIdent));

            JCTree.JCExpression minCall = maker.Apply(
                    com.sun.tools.javac.util.List.nil(),
                    maker.Select(maker.Ident(names.fromString("Math")), names.fromString("min")),
                    com.sun.tools.javac.util.List.of(exponentialDelay,
                            maker.Literal(com.sun.tools.javac.code.TypeTag.LONG, maxDelay)));

            delayStatement = buildThreadSleep(maker, names, minCall);
        } else {
            delayStatement = buildThreadSleep(maker, names,
                    maker.Literal(com.sun.tools.javac.code.TypeTag.LONG, delay));
        }

        JCTree.JCIdent retryCountIdent2 = maker.Ident(names.fromString("_retryCount"));
        JCTree.JCLiteral timesMinusOneLit = maker.Literal(com.sun.tools.javac.code.TypeTag.INT, times - 1);
        JCTree.JCBinary ifCondition = maker.Binary(JCTree.Tag.LT, retryCountIdent2, timesMinusOneLit);

        JCTree.JCThrow throwStmt = maker.Throw(maker.Ident(names.fromString("e")));
        JCTree.JCIf ifElse = maker.If(ifCondition, delayStatement, throwStmt);

        JCTree.JCBlock catchBody = maker.Block(0, com.sun.tools.javac.util.List.of(ifElse));
        JCTree.JCCatch catchClause = maker.Catch(exceptionVar, catchBody);

        JCTree.JCTry tryStatement = maker.Try(tryBody, com.sun.tools.javac.util.List.of(catchClause), null);

        JCTree.JCForLoop forLoop = maker.ForLoop(
                com.sun.tools.javac.util.List.of(retryCountVar),
                loopCondition,
                com.sun.tools.javac.util.List.of(incrementStmt),
                tryStatement);

        // 包装在 try-catch 中，确保异常被抛出
        com.sun.tools.javac.util.ListBuffer<JCTree.JCStatement> stats = new com.sun.tools.javac.util.ListBuffer<>();
        stats.append(forLoop);
        // 添加不可能到达的 return 语句，避免编译器报错
        stats.append(maker.Return(maker.Literal(com.sun.tools.javac.code.TypeTag.BOT, null)));

        return maker.Block(0, stats.toList());
    }

    /** 构建ThreadSleep */
    private JCTree.JCStatement buildThreadSleep(TreeMaker maker, Names names, JCTree.JCExpression delayExpr) {
        JCTree.JCExpression sleepCall = maker.Apply(
                com.sun.tools.javac.util.List.nil(),
                maker.Select(maker.Ident(names.fromString("Thread")), names.fromString("sleep")),
                com.sun.tools.javac.util.List.of(delayExpr));

        JCTree.JCExpressionStatement sleepStmt = maker.Exec(sleepCall);

        JCTree.JCBlock tryBody = maker.Block(0, com.sun.tools.javac.util.List.of(sleepStmt));
        JCTree.JCVariableDecl exceptionVar = maker.VarDef(
                maker.Modifiers(0),
                names.fromString("ignored"),
                maker.Ident(names.fromString("InterruptedException")),
                null);

        JCTree.JCExpression threadCall = maker.Apply(
                com.sun.tools.javac.util.List.nil(),
                maker.Select(maker.Ident(names.fromString("Thread")), names.fromString("currentThread")),
                com.sun.tools.javac.util.List.nil());
        JCTree.JCExpression interruptCall = maker.Apply(
                com.sun.tools.javac.util.List.nil(),
                maker.Select(threadCall, names.fromString("interrupt")),
                com.sun.tools.javac.util.List.nil());
        JCTree.JCExpressionStatement interruptStmt = maker.Exec(interruptCall);

        JCTree.JCBlock catchBody = maker.Block(0, com.sun.tools.javac.util.List.of(interruptStmt));
        JCTree.JCCatch catchClause = maker.Catch(exceptionVar, catchBody);

        return maker.Try(tryBody, com.sun.tools.javac.util.List.of(catchClause), null);
    }
}
