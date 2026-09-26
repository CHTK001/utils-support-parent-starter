package com.chua.common.support.rule.codegen;

import com.chua.common.support.rule.Condition;
import com.chua.common.support.rule.Pattern;
import com.chua.common.support.rule.RuleException;
import com.chua.common.support.rule.file.RuleActionRegistry;
import com.chua.common.support.rule.file.RuleAssembler;
import com.chua.common.support.rule.file.RuleRepository;
import com.chua.common.support.rule.file.RuleTypeRegistry;
import com.chua.common.support.reflection.ReflectUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则表达式字节码编译器。
 *
 * <p>把<b>受限</b>规则表达式编译为实现 {@link Condition} 的字节码类。
 * 采用单遍编译：递归下降解析的同时直接写出字节码，不经过中间 AST。</p>
 *
 * <h3>为什么编译而不是解释</h3>
 * <p>属性访问在编译期就解析为类型化 getter 调用
 * （{@code checkcast} + {@code invokevirtual}），
 * 运行时不再有反射调用，也无需重复解析表达式文本。</p>
 *
 * <h3>支持的语法（受限）</h3>
 * <pre>{@code
 * 字面量    123    123L    1.5    'text'    true    false    null
 * 访问      o        o.amount        o.user.level        g.maxAmount
 * 比较      ==   !=   >   >=   <   <=
 * 逻辑      &&   ||   !          （短路）
 * 算术      +   -   *   /        一元 -
 * 括号      ( ... )
 * }</pre>
 *
 * <h3>类型规则</h3>
 * <p>字面量一侧允许<b>自动加宽</b>：{@code o.amount > 500} 中
 * 若 {@code amount} 是 {@code long}，字面量 500 会被重新生成为 long 常量。
 * 两个非字面量的不同数值类型之间的算术会被<b>拒绝并报错</b>，
 * 而不是隐式猜测——规则是配置，静默猜测比报错危险。</p>
 *
 * <h3>刻意不支持（安全边界）</h3>
 * <ul>
 *   <li><b>方法调用</b> — {@code o.getClass().forName(...)} 一律拒绝；
 *       否则规则文件等同于任意代码执行</li>
 *   <li>赋值、自增、位运算、类型转换、数组下标</li>
 *   <li>从表达式引用任意类名</li>
 * </ul>
 * <p>属性只允许无参 getter（{@code getX()} / {@code isX()}）或 public 字段，
 * 且绑定名对应的类型必须已在 {@link RuleTypeRegistry} 注册，
 * 因此生成的字节码不可能触及注册范围之外的类。</p>
 *
 * <h3>编译失败即拒绝装载</h3>
 * <p>任何无法编译的表达式都抛 {@link RuleException}，
 * 由 {@code RuleRepository} 保留上一份可用规则集；
 * 绝不做「编译失败按 true 处理」的静默降级。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleAsmCompiler implements RuleAssembler.ExpressionCompiler {

    /**
     * 生成类名序号，保证唯一
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /**
     * 字节码类加载器
     */
    private final GeneratedClassLoader classLoader;

    /**
     * 编译缓存：表达式 + 规则名 -> 条件
     */
    private final Map<String, Condition> cache = new HashMap<>();

    /**
     * 创建编译器。
     *
     * <p>本编译器<b>不需要</b>类型注册表：表达式里 {@code o.amount} 的 {@code o}
     * 是 {@code when.facts[].binding} 声明的绑定名，其类型由
     * {@link RuleAssembler} 在装配时经白名单解析后，
     * 随 {@code bindings} 参数逐次传入。见
     * {@link #compile(String, String, Map)}。</p>
     */
    public RuleAsmCompiler() {
        this(new GeneratedClassLoader(RuleAsmCompiler.class.getClassLoader()));
    }

    /**
     * 创建编译器。
     *
     * @param classLoader 类加载器
     */
    public RuleAsmCompiler(GeneratedClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * 创建已装配 ASM 表达式编译器的仓库。
     *
     * <p>这是接入 ASM 编译的<b>推荐入口</b>：一次装配好类型注册表、
     * 动作注册表与表达式编译器，避免漏装导致 {@code expr} 节点在装载时报错。</p>
     *
     * <pre>{@code
     * RuleTypeRegistry types = RuleTypeRegistry.create().register("o", OrderFact.class);
     * RuleRepository repository = RuleAsmCompiler.repository(types);
     * repository.loadFrom(Paths.get("rules/orders.json"));
     * }</pre>
     *
     * @param typeRegistry 类型注册表
     * @return 规则仓库
     */
    public static RuleRepository repository(RuleTypeRegistry typeRegistry) {
        RuleTypeRegistry types = typeRegistry == null ? RuleTypeRegistry.create() : typeRegistry;
        return RuleRepository.builder()
                .assembler(new RuleAssembler(types, RuleActionRegistry.createDefault())
                        .expressionCompiler(new RuleAsmCompiler()))
                .build();
    }

    /**
     * 获取类加载器。
     *
     * @return 类加载器
     */
    public GeneratedClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public Condition compile(String expression, String ruleName, Map<String, Class<?>> bindings) {
        if (expression == null || expression.isBlank()) {
            throw new RuleException("规则[" + ruleName + "] 表达式为空");
        }
        String text = expression.trim();
        String cacheKey = ruleName + " " + text;
        synchronized (cache) {
            Condition cached = cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            Condition compiled = doCompile(text, ruleName,
                    bindings == null ? Map.of() : bindings);
            cache.put(cacheKey, compiled);
            return compiled;
        }
    }

    /**
     * 执行单遍编译。
     *
     * @param text     表达式
     * @param ruleName 规则名
     * @param bindings 绑定名到事实类型的映射
     * @return 条件
     */
    private Condition doCompile(String text, String ruleName, Map<String, Class<?>> bindings) {
        Generated generated = generate(text, ruleName, bindings);
        try {
            Class<?> type = classLoader.define(generated.binaryName(), generated.bytes());
            return (Condition) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            throw new RuleException("规则[" + ruleName + "] 表达式字节码装载失败：" + text, e);
        }
    }

    /**
     * 生成的类。
     *
     * @param binaryName 二进制名
     * @param bytes      字节码
     */
    record Generated(String binaryName, byte[] bytes) {
    }

    /**
     * 生成字节码但不定义类，供诊断使用。
     *
     * <p>与真实编译路径完全相同，因此可用于在装载失败时
     * dump 出字节码（{@code javap -c -v}）定位栈图问题。</p>
     *
     * @param text     表达式
     * @param ruleName 规则名
     * @param bindings 绑定名到事实类型的映射
     * @return 生成的类
     */
    Generated generate(String text, String ruleName, Map<String, Class<?>> bindings) {
        String internalName = "com/chua/common/support/rule/generated/Expr$"
                + SEQUENCE.incrementAndGet();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object",
                new String[]{Names.CONDITION});

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "test",
                "(L" + Names.CONTEXT + ";)Z", null, null);
        method.visitCode();
        Parser parser = new Parser(text, ruleName, method, bindings);
        parser.parseOr();
        if (!parser.emitted) {
            // 表达式为空（理论上不会发生），兜底返回 false
            method.visitInsn(Opcodes.ICONST_0);
        }
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return new Generated(internalName.replace('/', '.'), writer.toByteArray());
    }

    /**
     * 关键类型描述符。
     */
    static final class Names {

        /**
         * 条件接口
         */
        static final String CONDITION = "com/chua/common/support/rule/Condition";

        /**
         * 规则上下文
         */
        static final String CONTEXT = "com/chua/common/support/rule/RuleContext";

        /**
         * 全局变量前缀
         */
        static final String GLOBAL_PREFIX = "g";

        /**
         * 私有构造函数
         */
        private Names() {
        }
    }

    /**
     * 栈上值的类型。
     */
    enum Kind {

        /**
         * 布尔（int 0/1）
         */
        BOOLEAN,

        /**
         * int
         */
        INT,

        /**
         * long
         */
        LONG,

        /**
         * double
         */
        DOUBLE,

        /**
         * 引用类型（String 等）
         */
        REFERENCE
    }

    /**
     * 字面量标记：允许在需要时被重新生成为更宽的类型。
     *
     * @param kind    类型
     * @param literal 字面量值，仅数值类型非 null
     */
    record Value(Kind kind, Number literal) {

        /**
         * 是否为可加宽的数值字面量。
         *
         * @return 是返回 true
         */
        boolean widenable() {
            return literal != null;
        }
    }

    /**
     * 单遍递归下降解析器。
     */
    private final class Parser {

        /**
         * 表达式
         */
        private final String source;

        /**
         * 规则名
         */
        private final String ruleName;

        /**
         * 字节码写入器
         */
        private final MethodVisitor mv;

        /**
         * 词法单元
         */
        private final List<Token> tokens;

        /**
         * 当前位置
         */
        private int pos;

        /**
         * 绑定名到事实类型
         */
        private final Map<String, Class<?>> bindings;

        /**
         * 已写入布尔结果
         */
        private boolean emitted;

        /**
         * 创建解析器。
         *
         * @param source   表达式
         * @param ruleName 规则名
         * @param mv       写入器
         * @param bindings 绑定名到类型的映射
         */
        private Parser(String source, String ruleName, MethodVisitor mv,
                Map<String, Class<?>> bindings) {
            this.source = source;
            this.ruleName = ruleName;
            this.mv = mv;
            this.bindings = bindings;
            this.tokens = tokenize(source, ruleName);
        }

        /**
         * 构造编译异常。
         *
         * @param message 描述
         * @return 异常
         */
        private RuleException error(String message) {
            Token token = tokens.get(Math.min(pos, tokens.size() - 1));
            return new RuleException("规则[" + ruleName + "] 表达式编译失败（位置 "
                    + token.pos() + "）：" + message + "；表达式=" + source);
        }

        /**
         * 当前词法单元。
         *
         * @return 词法单元
         */
        private Token peek() {
            return tokens.get(pos);
        }

        /**
         * 是否为 SQL 风格关键字（大小写不敏感）。
         *
         * <p>让规则表达式贴近 SQL 书写习惯，降低学习成本：
         * {@code and / or / not / is / like} 与 {@code && / || / !} 等价。</p>
         *
         * @param keyword 关键字
         * @return 命中返回 true
         */
        private boolean isKeyword(String keyword) {
            Token token = peek();
            return token.type() == TokenType.IDENT && token.text().equalsIgnoreCase(keyword);
        }

        /**
         * 当前是否为逻辑「与」：{@code &&} 或 {@code and}。
         *
         * @return 是返回 true
         */
        private boolean isAnd() {
            return isOp("&&") || isKeyword("and");
        }

        /**
         * 当前是否为逻辑「或」：{@code ||} 或 {@code or}。
         *
         * @return 是返回 true
         */
        private boolean isOr() {
            return isOp("||") || isKeyword("or");
        }

        /**
         * 当前是否为逻辑「非」：{@code !} 或 {@code not}。
         *
         * @return 是返回 true
         */
        private boolean isNot() {
            return isOp("!") || isKeyword("not");
        }

        /**
         * 消费一个逻辑运算符（关键字形式统一记为符号形式）。
         *
         * @param symbol 符号形式
         */
        private void consumeLogic(String symbol) {
            Token token = peek();
            if (token.type() == TokenType.IDENT) {
                pos++;
            } else {
                expectOp(symbol);
            }
        }

        /**
         * 读取 SQL 风格的后缀判定：{@code is null} / {@code is not null}。
         *
         * <p>紧跟在一个基本单元之后求值，因此 {@code a > 1 and b is not null}
         * 中的 {@code b} 必须先被解析成基本单元——这是刻意的，
         * 避免 {@code is} 的优先级在复杂嵌套里变得不可预测。</p>
         *
         * @return 命中 is-null 判定返回 true
         */
        private boolean tryParseIsNull() {
            if (!isKeyword("is")) {
                return false;
            }
            pos++;
            boolean negated = false;
            if (isKeyword("not")) {
                negated = true;
                pos++;
            }
            if (!isKeyword("null")) {
                throw error("is 之后只允许 null 或 not null");
            }
            pos++;
            // 栈: [v]；dup 出来的副本用于判空，原值 v 在 isNull 分支上
            // 仍然留在栈里，所以该分支必须先 pop，否则合并点栈高度不一致
            mv.visitInsn(Opcodes.DUP);
            Label isNull = new Label();
            Label end = new Label();
            mv.visitJumpInsn(Opcodes.IFNULL, isNull);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(isNull);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitLabel(end);
            if (negated) {
                emitLogicalNot();
            }
            return true;
        }

        /**
         * 消费一个运算符。
         *
         * @param text 运算符
         */
        private void expectOp(String text) {
            Token token = peek();
            if (token.type() != TokenType.OP || !token.text().equals(text)) {
                throw error("期望 '" + text + "'，实际为 '" + token.text() + "'");
            }
            pos++;
        }

        /**
         * 是否为指定运算符。
         *
         * @param text 运算符
         * @return 是返回 true
         */
        private boolean isOp(String text) {
            Token token = peek();
            return token.type() == TokenType.OP && token.text().equals(text);
        }

        /**
         * 解析逻辑或。
         *
         * @return 结果类型
         */
        private Value parseOr() {
            Value left = parseAnd();
            while (isOr()) {
                consumeLogic("||");
                Label end = new Label();
                // 布尔值恒为 0/1，因此左操作数「短路成立」时它本身就是结果，
                // 直接跳到 end 保留栈上的值即可，无需再压 1
                mv.visitInsn(Opcodes.DUP);
                mv.visitJumpInsn(Opcodes.IFNE, end);
                mv.visitInsn(Opcodes.POP);
                parseAnd();
                mv.visitLabel(end);
                left = new Value(Kind.BOOLEAN, null);
            }
            return left;
        }

        /**
         * 解析逻辑与。
         *
         * @return 结果类型
         */
        private Value parseAnd() {
            Value left = parseNot();
            while (isAnd()) {
                consumeLogic("&&");
                Label end = new Label();
                // 同 parseOr：左操作数为 0 时它本身就是结果，保留即可
                mv.visitInsn(Opcodes.DUP);
                mv.visitJumpInsn(Opcodes.IFEQ, end);
                mv.visitInsn(Opcodes.POP);
                parseNot();
                mv.visitLabel(end);
                left = new Value(Kind.BOOLEAN, null);
            }
            return left;
        }

        /**
         * 解析逻辑非。
         *
         * <p>优先级刻意低于比较运算：{@code not a = 'x'} 是 {@code not (a = 'x')}，
         * 与 SQL 一致。若把 {@code not} 放在一元运算层，它会只绑定到紧邻的
         * 属性访问上（{@code not a}），把 {@code = 'x'} 留在外面。</p>
         *
         * @return 结果类型
         */
        private Value parseNot() {
            if (isNot()) {
                consumeLogic("!");
                Value operand = parseNot();
                requireBoolean(operand, "not");
                emitLogicalNot();
                return new Value(Kind.BOOLEAN, null);
            }
            return parseEquality();
        }

        /**
         * 要求操作数是布尔类型。
         *
         * <p>否则取反没有意义：{@code IFNE} 只能作用于 int，
         * 作用在引用上会直接被字节码校验拒绝。</p>
         *
         * @param value 操作数
         * @param usage 用于报错
         */
        private void requireBoolean(Value value, String usage) {
            if (value.kind() != Kind.BOOLEAN) {
                throw error(usage + " 的操作数必须是布尔值（例如属性比较的结果），"
                        + "当前类型为 " + value.kind() + "；字符串请改用 = / =~ 比较");
            }
        }

        /**
         * 解析相等性比较。
         *
         * @return 结果类型
         */
        private Value parseEquality() {
            Value left = parseRelational();
            while (isOp("==") || isOp("!=") || isOp("=~") || isKeyword("like")) {
                String op = nextOp();
                boolean regex = "=~".equals(op);
                if (regex || "like".equalsIgnoreCase(op)) {
                    // 正则/通配符的右侧必须是字面量：这样 glob->regex 的翻译与
                    // Pattern.compile 校验都能在编译期完成，配置写错立刻报错
                    Token pattern = peek();
                    if (pattern.type() != TokenType.STRING) {
                        throw error((regex ? "=~ 正则匹配" : "like 通配符匹配")
                                + "的右侧必须是字符串字面量，例如 o.level =~ 'GOLD.*'");
                    }
                    pos++;
                    if (regex) {
                        emitRegexMatch(left, pattern.text());
                    } else {
                        emitWildcardMatch(left, pattern.text());
                    }
                } else {
                    Value right = parseRelational();
                    emitEquality(op, left, right);
                }
                left = new Value(Kind.BOOLEAN, null);
            }
            return left;
        }

        /**
         * 解析关系比较。
         *
         * @return 结果类型
         */
        private Value parseRelational() {
            Value left = parseAdditive();
            while (isOp(">") || isOp(">=") || isOp("<") || isOp("<=")) {
                String op = nextOp();
                Value right = parseAdditive();
                emitRelational(op, left, right);
                left = new Value(Kind.BOOLEAN, null);
            }
            return left;
        }

        /**
         * 解析加减。
         *
         * @return 结果类型
         */
        private Value parseAdditive() {
            Value left = parseMultiplicative();
            while (isOp("+") || isOp("-")) {
                String op = nextOp();
                Value right = parseMultiplicative();
                left = emitArithmetic(op, left, right);
            }
            return left;
        }

        /**
         * 解析乘除。
         *
         * @return 结果类型
         */
        private Value parseMultiplicative() {
            Value left = parseUnary();
            while (isOp("*") || isOp("/")) {
                String op = nextOp();
                Value right = parseUnary();
                left = emitArithmetic(op, left, right);
            }
            return left;
        }

        /**
         * 解析一元运算。
         *
         * @return 结果类型
         */
        private Value parseUnary() {
            if (isOp("!")) {
                // 符号形式的 ! 仍作为一元运算保留（兼容原有表达式），
                // 取反对象必须已经是布尔值
                pos++;
                Value operand = parseUnary();
                requireBoolean(operand, "!");
                emitLogicalNot();
                emitted = true;
                return new Value(Kind.BOOLEAN, null);
            }
            if (isOp("-")) {
                pos++;
                Value value = parseUnary();
                mv.visitInsn(Opcodes.INEG);
                emitted = true;
                return value;
            }
            return parsePrimaryWithIsNull();
        }

        /**
         * 解析基本单元。
         *
         * @return 结果类型
         */
        private Value parsePrimary() {
            Token token = peek();
            emitted = true;
            switch (token.type()) {
                case INT -> {
                    pos++;
                    mv.visitLdcInsn(token.intValue());
                    return new Value(Kind.INT, token.intValue());
                }
                case LONG -> {
                    pos++;
                    mv.visitLdcInsn(token.longValue());
                    return new Value(Kind.LONG, token.longValue());
                }
                case DOUBLE -> {
                    pos++;
                    mv.visitLdcInsn(token.doubleValue());
                    return new Value(Kind.DOUBLE, token.doubleValue());
                }
                case STRING -> {
                    pos++;
                    mv.visitLdcInsn(token.text());
                    return new Value(Kind.REFERENCE, null);
                }
                case IDENT -> {
                    pos++;
                    return switch (token.text()) {
                        case "true" -> {
                            mv.visitInsn(Opcodes.ICONST_1);
                            yield new Value(Kind.BOOLEAN, null);
                        }
                        case "false" -> {
                            mv.visitInsn(Opcodes.ICONST_0);
                            yield new Value(Kind.BOOLEAN, null);
                        }
                        case "null" -> {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            yield new Value(Kind.REFERENCE, null);
                        }
                        // 关键字不能当表达式主体，给出可操作的提示而不是
                        // 含糊的「未声明的绑定」
                        case "and", "or", "not", "is", "like" -> throw error(
                                "运算符 '" + token.text() + "' 缺少左操作数");
                        default -> emitAccess(token);
                    };
                }
                case OP -> {
                    if ("(".equals(token.text())) {
                        pos++;
                        Value inner = parseOr();
                        expectOp(")");
                        return inner;
                    }
                    throw error("意外的符号 '" + token.text() + "'");
                }
                default -> throw error("表达式不完整");
            }
        }

        /**
         * 解析一个基本单元，并尝试接上 {@code is null} / {@code is not null}。
         *
         * @return 结果类型
         */
        private Value parsePrimaryWithIsNull() {
            Value value = parsePrimary();
            if (value.kind() != Kind.REFERENCE && isKeyword("is")) {
                throw error("is null 只能用于引用类型属性，当前属性类型为 " + value.kind()
                        + "；原始类型本身不可能为 null，无需判断");
            }
            if (tryParseIsNull()) {
                return new Value(Kind.BOOLEAN, null);
            }
            return value;
        }

        /**
         * 生成属性访问字节码。
         *
         * <p>首段为绑定名时：{@code ctx.get(name)} 后按注册类型 cast，
         * 逐段调用无参 getter；首段为 {@code g} 时读全局变量。
         * getter 在编译期解析，运行时无反射。</p>
         *
         * @param token 标识符词法单元
         * @return 结果类型
         */
        private Value emitAccess(Token token) {
            String[] parts = token.text().split("\\.");
            if (parts.length == 0 || parts[0].isEmpty()) {
                throw error("属性访问为空");
            }
            if (Names.GLOBAL_PREFIX.equals(parts[0])) {
                if (parts.length != 2) {
                    throw error("全局变量只支持 g.<name> 形式：" + token.text());
                }
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitLdcInsn(parts[1]);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Names.CONTEXT, "global",
                        "(Ljava/lang/String;)Ljava/lang/Object;", false);
                return new Value(Kind.REFERENCE, null);
            }

            Class<?> current = bindings.get(parts[0]);
            if (current == null) {
                throw error("未声明的绑定 '" + parts[0] + "'；请在规则里写 "
                        + "\"facts\": [{\"type\":\"order\",\"binding\":\"" + parts[0] + "\"}]，"
                        + "或把 " + parts[0] + " 注册为类型别名后直接使用；"
                        + "当前可用绑定：" + bindings.keySet());
            }
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitLdcInsn(parts[0]);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Names.CONTEXT, "get",
                    "(Ljava/lang/String;)Ljava/lang/Object;", false);
            if (parts.length == 1) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(current));
                emitted = true;
                return new Value(Kind.REFERENCE, null);
            }
            // 多段访问：每段都重新取一次绑定。
            // getter 可能返回原始类型，无法在栈上保留引用供下一段复用，
            // 因此这里宁可多一次查找，也要换取实现简单与栈图正确
            Kind kind = Kind.REFERENCE;
            for (int i = 1; i < parts.length; i++) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(current));
                Accessor accessor = resolveAccessor(current, parts[i], token);
                if (accessor.getter() != null) {
                    // getter 已确认为无参，描述符固定为 () 返回类型
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            Type.getInternalName(current), accessor.getter().getName(),
                            "()" + Type.getDescriptor(accessor.getter().getReturnType()), false);
                } else {
                    mv.visitFieldInsn(Opcodes.GETFIELD,
                            Type.getInternalName(current), accessor.fieldName(),
                            Type.getDescriptor(accessor.field().getType()));
                }
                kind = kindOf(accessor.type());
                current = accessor.type;
            }
            return new Value(kind, null);
        }

        /**
         * 把栈上较窄的数值提升为较宽的数值。
         *
         * <p>只允许提升<b>右操作数</b>（位于栈顶）。
         * 典型场景：{@code o.amount > 500} 中 {@code amount} 为 {@code long}，
         * 字面量 500 以 int 入栈，此处补 {@code I2L} 即可精确转换。</p>
         *
         * @param from 现有类型
         * @param to   目标类型
         * @param left 左操作数描述，用于错误信息
         */
        private void promoteRight(Kind from, Kind to, String left) {
            if (from == to) {
                return;
            }
            if (from == Kind.INT && to == Kind.LONG) {
                mv.visitInsn(Opcodes.I2L);
            } else if (from == Kind.INT && to == Kind.DOUBLE) {
                mv.visitInsn(Opcodes.I2D);
            } else if (from == Kind.LONG && to == Kind.DOUBLE) {
                mv.visitInsn(Opcodes.L2D);
            } else {
                throw error("不支持的类型组合：左值 " + left + "(" + from + ")"
                        + " 与右值 " + from + " → " + to
                        + "；请显式使用同类型字面量（如 500L / 1.5）");
            }
        }

        /**
         * 把栈顶的 {@code Object}（如全局变量）拆箱为指定数值类型。
         *
         * <p>全局变量从 JSON 读入，静态类型只能是 {@code Object}，
         * 因此与数值比较时必须在运行期做 {@code Number} 检查与拆箱。
         * 用 {@code checkcast Number} 而不是 {@code Integer}，
         * 是为了让 {@code 300} 与 {@code 300L} 两种写法都能工作。</p>
         *
         * @param target 目标数值类型
         * @param opTag  操作标签，用于错误信息
         */
        private void coerceReference(Kind target, String opTag) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
            String method = switch (target) {
                case LONG -> "longValue";
                case DOUBLE -> "doubleValue";
                case BOOLEAN -> "intValue";
                default -> throw error("运算符 '" + opTag + "' 无法把 Object 转为 " + target);
            };
            String descriptor = "()" + switch (target) {
                case LONG -> "J";
                case DOUBLE -> "D";
                default -> "I";
            };
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", method, descriptor, false);
        }

        /**
         * 求两个操作数的公共数值类型，必要时转换右操作数。
         *
         * <p>只允许转换<b>右操作数</b>（位于栈顶）。这覆盖了规则里
         * 全部自然写法（{@code o.amount > 500}、{@code o.amount > g.max}）；
         * 把全局变量写在左侧如 {@code g.max > 500} 会明确报错，
         * 而不是猜一个转换方向。</p>
         *
         * @param left  左操作数
         * @param right 右操作数
         * @param opTag 操作标签，用于错误信息
         * @return 公共类型
         */
        private Kind unify(Value left, Value right, String opTag) {
            if (left.kind() == right.kind()) {
                return left.kind();
            }
            if (left.kind() == Kind.REFERENCE && right.kind() == Kind.REFERENCE) {
                return Kind.REFERENCE;
            }
            if (isNumeric(left.kind()) && isNumeric(right.kind()) && right.widenable()) {
                promoteRight(right.kind(), left.kind(), opTag);
                return left.kind();
            }
            if (isNumeric(left.kind()) && right.kind() == Kind.REFERENCE) {
                coerceReference(left.kind(), opTag);
                return left.kind();
            }
            throw error("运算符 '" + opTag + "' 两侧类型不匹配：左值 " + left.kind()
                    + "，右值 " + right.kind() + "；请把事实或全局变量写在左侧，例如 "
                    + "o.amount > g.max");
        }

        /**
         * 判断是否为数值类型。
         *
         * @param kind 类型
         * @return 是返回 true
         */
        private static boolean isNumeric(Kind kind) {
            return kind == Kind.INT || kind == Kind.LONG || kind == Kind.DOUBLE
                    || kind == Kind.BOOLEAN;
        }

        /**
         * 生成正则匹配：{@code left =~ 'PATTERN'}。
         *
         * <p>用局部变量把两个操作数重排后再调用
         * {@code String.matches}，这样即使左值静态类型是 {@code Object}
         * 也能先 {@code checkcast} 再调用，字节码可通过校验。</p>
         *
         * <p><b>已知成本</b>：{@code String.matches} 每次求值都会重新编译正则。
         * 规则求值是热路径，若 profiling 显示此处是热点，
         * 可改为在生成类里用静态字段缓存 {@code java.util.regex.Pattern}。</p>
         *
         * @param left    左值
         * @param pattern 正则字面量
         */
        private void emitRegexMatch(Value left, String pattern) {
            requireReference(left, "正则匹配 =~");
            // 编译期就校验一次：配置写错必须在装载时报出来，而不是等到线上求值
            Pattern.compileRegex(pattern);
            emitStringMatches(pattern);
        }

        /**
         * 生成通配符匹配：{@code left like 'PAT*'}。
         *
         * <p>glob 在编译期翻译为正则并做一次 {@code Pattern.compile} 校验，
         * 因此 {@code *} / {@code ?} 之外的正则元字符会被转义，
         * 写 {@code a.b} 只会匹配字面量 {@code a.b}，不会意外匹配 {@code axb}。</p>
         *
         * @param left 左值
         * @param glob 通配符字面量
         */
        private void emitWildcardMatch(Value left, String glob) {
            requireReference(left, "通配符匹配 like");
            String regex = Pattern.wildcardToRegex(glob);
            Pattern.compileRegex(regex);
            emitStringMatches(regex);
        }

        /**
         * 生成 {@code String.matches(regex)} 调用。
         *
         * <p>模式是编译期字面量，因此直接 {@code ldc} 进常量池，
         * 不需要占用局部变量槽；左值先 {@code checkcast} 成 String，
         * 这样即使它的静态类型是 Object 也能通过字节码校验。</p>
         *
         * <p>被匹配的字符串走 {@code String.matches}，它内部每次调用都会
         * 编译一次正则；{@link Pattern#compileRegex(String)} 那层有界缓存
         * 只覆盖结构化比较路径。若 profiling 表明这里是热点，
         * 可改为在生成类里用静态字段缓存 {@code java.util.regex.Pattern}。</p>
         *
         * @param regex 正则
         */
        private void emitStringMatches(String regex) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
            mv.visitLdcInsn(regex);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "matches",
                    "(Ljava/lang/String;)Z", false);
        }

        /**
         * 把 glob 通配符翻译为正则。
         *
         * <p>实现已收敛到 {@link Pattern#wildcardToRegex(String)}，
         * 与结构化比较路径共用同一份翻译规则，避免两处实现漂移。</p>
         *
         * @param glob 通配符
         * @return 等价正则
         */
        private static String globToRegex(String glob) {
            return Pattern.wildcardToRegex(glob);
        }

        /**
         * 要求左值是引用类型（字符串匹配才有意义）。
         *
         * @param value 左值
         * @param usage 用于报错
         */
        private void requireReference(Value value, String usage) {
            if (value.kind() != Kind.REFERENCE) {
                throw error(usage + "的左侧必须是字符串属性，当前类型为 " + value.kind());
            }
        }

        /**
         * 生成相等性比较。
         *
         * @param op    运算符
         * @param left  左值
         * @param right 右值
         */
        private void emitEquality(String op, Value left, Value right) {
            if (left.kind() == Kind.REFERENCE && right.kind() == Kind.REFERENCE) {
                // 栈: [left, right]，正好是 receiver + 参数
                // 用 equals 而非引用比较：LDC 字符串是 interned 的，
                // 而 getter 返回的字符串未必 interned，引用比较会误判为不等
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals",
                        "(Ljava/lang/Object;)Z", false);
                if ("!=".equals(op)) {
                    emitLogicalNot();
                }
                return;
            }
            Kind kind = unify(left, right, op);
            int skip = switch (kind) {
                case INT, BOOLEAN -> "==".equals(op) ? Opcodes.IF_ICMPNE : Opcodes.IF_ICMPEQ;
                case LONG -> {
                    mv.visitInsn(Opcodes.LCMP);
                    yield "==".equals(op) ? Opcodes.IFNE : Opcodes.IFEQ;
                }
                case DOUBLE -> {
                    mv.visitInsn(Opcodes.DCMPL);
                    yield "==".equals(op) ? Opcodes.IFNE : Opcodes.IFEQ;
                }
                default -> throw error("不支持的相等比较类型：" + kind);
            };
            emitBranchBoolean(skip);
        }

        /**
         * 对栈顶的布尔值取反（栈: [v] -&gt; [!v]）。
         */
        private void emitLogicalNot() {
            Label trueBranch = new Label();
            Label end = new Label();
            mv.visitJumpInsn(Opcodes.IFNE, trueBranch);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(trueBranch);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitLabel(end);
        }

        /**
         * 生成关系比较。
         *
         * @param op    运算符
         * @param left  左值
         * @param right 右值
         */
        private void emitRelational(String op, Value left, Value right) {
            Kind kind = unify(left, right, op);
            if (kind == Kind.INT || kind == Kind.BOOLEAN) {
                int skip = switch (op) {
                    case ">" -> Opcodes.IF_ICMPLE;
                    case ">=" -> Opcodes.IF_ICMPLT;
                    case "<" -> Opcodes.IF_ICMPGE;
                    default -> Opcodes.IF_ICMPGT;
                };
                emitBranchBoolean(skip);
                return;
            }
            if (kind == Kind.LONG) {
                mv.visitInsn(Opcodes.LCMP);
            } else if (kind == Kind.DOUBLE) {
                mv.visitInsn(Opcodes.DCMPL);
            } else {
                throw error("不支持的关系比较类型：" + kind);
            }
            int skip = switch (op) {
                case ">" -> Opcodes.IFLE;
                case ">=" -> Opcodes.IFLT;
                case "<" -> Opcodes.IFGE;
                default -> Opcodes.IFGT;
            };
            emitBranchBoolean(skip);
        }

        /**
         * 以「跳转即不成立」的 inverted 分支生成布尔结果。
         *
         * <p>调用前置条件：栈顶为待判定的 int（0/1），且已消费完两个操作数。
         * 传入的指令在条件<b>不满足</b>时跳到 false 分支。</p>
         *
         * @param skipOpcode 跳转指令
         */
        private void emitBranchBoolean(int skipOpcode) {
            Label falseBranch = new Label();
            Label end = new Label();
            mv.visitJumpInsn(skipOpcode, falseBranch);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(falseBranch);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitLabel(end);
        }

        /**
         * 生成算术运算。
         *
         * @param op    运算符
         * @param left  左值
         * @param right 右值
         * @return 结果类型
         */
        private Value emitArithmetic(String op, Value left, Value right) {
            Kind kind = unify(left, right, op);
            if (kind == Kind.REFERENCE) {
                throw error("算术运算符 '" + op + "' 不能作用于对象类型");
            }
            int opcode;
            boolean isDouble = kind == Kind.DOUBLE;
            boolean isLong = kind == Kind.LONG;
            opcode = switch (op) {
                case "+" -> isDouble ? Opcodes.DADD : isLong ? Opcodes.LADD : Opcodes.IADD;
                case "-" -> isDouble ? Opcodes.DSUB : isLong ? Opcodes.LSUB : Opcodes.ISUB;
                case "*" -> isDouble ? Opcodes.DMUL : isLong ? Opcodes.LMUL : Opcodes.IMUL;
                case "/" -> isDouble ? Opcodes.DDIV : isLong ? Opcodes.LDIV : Opcodes.IDIV;
                default -> throw error("不支持的算术运算符：" + op);
            };
            mv.visitInsn(opcode);
            return new Value(kind, null);
        }

        /**
         * 消费一个运算符文本。
         *
         * @return 运算符
         */
        private String nextOp() {
            return tokens.get(pos++).text();
        }
    }

    /**
     * 属性访问的解析结果。
     *
     * @param getter    对应的无参 getter，非空表示走方法
     * @param type      取值后的静态类型
     * @param fieldName 字段名，非空表示走字段
     * @param field     对应字段
     */
    record Accessor(Method getter, Class<?> type, String fieldName, Field field) {
    }

    /**
     * 解析属性访问方式：优先无参 getter，其次 public 字段。
     *
     * @param owner    所属类型
     * @param property 属性名
     * @param token    词法单元，用于报错
     * @return 访问器
     */
    private static Accessor resolveAccessor(Class<?> owner, String property, Token token) {
        if (property.isEmpty()) {
            throw new RuleException("属性名不能为空，位置 " + token.pos());
        }
        String capitalized = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (String candidate : new String[]{"get" + capitalized, "is" + capitalized, property}) {
            // 查找统一走 ReflectUtils：本类只取方法「元数据」用于生成调用指令，
            // 运行期是直接 invokevirtual，不存在反射调用
            Method method = ReflectUtils.findMethod(owner, candidate);
            if (method != null && method.getReturnType() != void.class) {
                return new Accessor(method, method.getReturnType(), null, null);
            }
        }
        Field field = ReflectUtils.findField(owner, property);
        if (field != null && Modifier.isPublic(field.getModifiers())) {
            return new Accessor(null, field.getType(), field.getName(), field);
        }
        throw new RuleException("类型 " + owner.getName() + " 上找不到属性 '" + property
                + "' 的无参 getter（getX/isX）或 public 字段；"
                + "为避免规则文件成为任意代码入口，不支持带参数的方法调用");
    }

    /**
     * 由 Java 类型推断表达式值类型。
     *
     * @param type Java 类型
     * @return 值类型
     */
    private static Kind kindOf(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return Kind.BOOLEAN;
        }
        if (type == int.class || type == Integer.class
                || type == short.class || type == byte.class) {
            return Kind.INT;
        }
        if (type == long.class || type == Long.class) {
            return Kind.LONG;
        }
        if (type == double.class || type == Double.class || type == float.class) {
            return Kind.DOUBLE;
        }
        return Kind.REFERENCE;
    }

    /**
     * 词法单元类型。
     */
    enum TokenType {

        /**
         * 整数
         */
        INT,

        /**
         * 长整数
         */
        LONG,

        /**
         * 浮点
         */
        DOUBLE,

        /**
         * 字符串
         */
        STRING,

        /**
         * 标识符（含属性路径）
         */
        IDENT,

        /**
         * 运算符
         */
        OP,

        /**
         * 结束
         */
        EOF
    }

    /**
     * 词法单元。
     *
     * @param type     类型
     * @param text     文本
     * @param intVal   int 值
     * @param longVal  long 值
     * @param dblVal   double 值
     * @param pos      位置
     */
    record Token(TokenType type, String text, int intVal, long longVal, double dblVal, int pos) {

        /**
         * 取 int 值。
         *
         * @return 值
         */
        int intValue() {
            return intVal;
        }

        /**
         * 取 long 值。
         *
         * @return 值
         */
        long longValue() {
            return longVal;
        }

        /**
         * 取 double 值。
         *
         * @return 值
         */
        double doubleValue() {
            return dblVal;
        }
    }

    /**
     * 词法分析。
     *
     * @param source   表达式
     * @param ruleName 规则名
     * @return 词法单元列表
     */
    private static List<Token> tokenize(String source, String ruleName) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (Character.isDigit(c)
                    || (c == '.' && i + 1 < length && Character.isDigit(source.charAt(i + 1)))) {
                boolean fractional = false;
                while (i < length && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                    fractional |= source.charAt(i) == '.';
                    i++;
                }
                String text = source.substring(start, i);
                boolean isLong = false;
                boolean isDouble = fractional;
                if (i < length && "lL".indexOf(source.charAt(i)) >= 0) {
                    isLong = true;
                    i++;
                } else if (i < length && "dD".indexOf(source.charAt(i)) >= 0) {
                    isDouble = true;
                    i++;
                }
                if (isDouble) {
                    tokens.add(new Token(TokenType.DOUBLE, text, 0, 0,
                            Double.parseDouble(text), start));
                } else if (isLong) {
                    tokens.add(new Token(TokenType.LONG, text, 0, Long.parseLong(text), 0, start));
                } else {
                    tokens.add(new Token(TokenType.INT, text, Integer.parseInt(text), 0, 0, start));
                }
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                while (i < length && (Character.isJavaIdentifierPart(source.charAt(i))
                        || source.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(TokenType.IDENT, source.substring(start, i), 0, 0, 0, start));
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder text = new StringBuilder();
                while (i < length && source.charAt(i) != quote) {
                    char ch = source.charAt(i);
                    if (ch == '\\' && i + 1 < length) {
                        i++;
                        char esc = source.charAt(i);
                        text.append(switch (esc) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            default -> esc;
                        });
                    } else {
                        text.append(ch);
                    }
                    i++;
                }
                if (i >= length) {
                    throw new RuleException("规则[" + ruleName + "] 表达式位置 " + start
                            + " 处字符串未闭合");
                }
                i++;
                tokens.add(new Token(TokenType.STRING, text.toString(), 0, 0, 0, start));
                continue;
            }
            String two = i + 1 < length ? source.substring(i, i + 2) : "";
            if ("==".equals(two) || "!=".equals(two) || ">=".equals(two)
                    || "<=".equals(two) || "&&".equals(two) || "||".equals(two)) {
                tokens.add(new Token(TokenType.OP, two, 0, 0, 0, start));
                i += 2;
                continue;
            }
            // SQL 风格别名：<> 等价于 !=
            if ("<>".equals(two)) {
                tokens.add(new Token(TokenType.OP, "!=", 0, 0, 0, start));
                i += 2;
                continue;
            }
            // =~ 正则匹配；单独一个 = 是 SQL 风格的相等
            if ("=~".equals(two)) {
                tokens.add(new Token(TokenType.OP, "=~", 0, 0, 0, start));
                i += 2;
                continue;
            }
            if ('=' == c) {
                tokens.add(new Token(TokenType.OP, "==", 0, 0, 0, start));
                i++;
                continue;
            }
            if ("+-*/%<>!()".indexOf(c) >= 0) {
                tokens.add(new Token(TokenType.OP, String.valueOf(c), 0, 0, 0, start));
                i++;
                continue;
            }
            throw new RuleException("规则[" + ruleName + "] 表达式位置 " + start
                    + " 处出现非法字符 '" + c + "'");
        }
        tokens.add(new Token(TokenType.EOF, "<eof>", 0, 0, 0, length));
        return tokens;
    }
}
