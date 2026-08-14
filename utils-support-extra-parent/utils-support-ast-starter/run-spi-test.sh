#!/bin/bash
# 功能性验证：@AutoSpi 编译期生成 / 追加去重 / 注解共存去冗余 / 历史行清理 / name 冲突告警
cd "$(dirname "$0")" || exit 1

JAR=target/utils-support-ast-starter-4.0.0.42.jar
SRC=$(find test-fixture -name '*.java')

echo "========== T1: fresh generation (文件不存在 -> 新建) =========="
rm -rf target/test-spi-out
mkdir -p target/test-spi-out

javac -proc:only -cp "$JAR" -processorpath "$JAR" -d target/test-spi-out $SRC > target/t1.log 2>&1
echo "COMPILE_EXIT:$?"
echo "--- generated files ---"
find target/test-spi-out -path '*META-INF*' -type f | sort
for f in $(find target/test-spi-out -path '*META-INF*' -type f | sort); do
  echo "--- $f ---"
  cat "$f"
done

echo
echo "--- T1 assertions ---"
echo -n "name-conflict warning in compile log (expect 1): "
# 使用 ASCII 模式匹配：Windows 下 javac 输出为 GBK 编码，中文模式会乱码无法匹配
grep -c '\[conflict\]' target/t1.log
echo -n "annotation-derived alias line json= (expect 0): "
grep -c '^json=com.chua.test.impl.JsonEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient || true
echo -n "annotation-derived alias line application/json= (expect 0): "
grep -c '^application/json=com.chua.test.impl.JsonEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient || true
echo -n "bare discovery line JsonEmbeddingClient (expect 1): "
grep -c '^com.chua.test.impl.JsonEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "bare discovery line ExtensionAnnotatedClient (expect 1): "
grep -c '^com.chua.test.impl.ExtensionAnnotatedClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "bare discovery line NameConflictClient (expect 1): "
grep -c '^com.chua.test.impl.NameConflictClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "conflict= alias line (expect 0): "
grep -c '^conflict=com.chua.test.impl.NameConflictClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient || true
echo -n "explicit name minilm (expect 1): "
grep -c '^minilm=com.chua.test.impl.MiniLMEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "derived alias Bge (expect 1): "
grep -c '^Bge=com.chua.test.impl.BgeEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "nested binary-name file entry (expect 1): "
grep -c '^nested=com.chua.test.impl.NestedHandlerImpl$' 'target/test-spi-out/META-INF/extensions/com.chua.test.spi.Factory$Handler'

echo
echo "========== T2: append to existing file (文件已存在 -> 追加并去重) =========="
rm -rf target/test-spi-out
mkdir -p target/test-spi-out/META-INF/extensions
# 预置手动维护的既有配置：一条保留条目 + 一条与本次生成重复的条目（应去重）
cat > target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient <<'EOF'
manual=com.chua.test.impl.ManualClient
minilm=com.chua.test.impl.MiniLMEmbeddingClient
EOF

javac -proc:only -cp "$JAR" -processorpath "$JAR" -d target/test-spi-out $SRC 2>&1
echo "COMPILE_EXIT:$?"
echo "--- merged EmbeddingClient file ---"
cat target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "duplicate line count (expect 0): "
sort target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient | uniq -d | wc -l
echo -n "manual line retained (expect 1): "
grep -c '^manual=com.chua.test.impl.ManualClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "explicit name minilm deduped (expect 1): "
grep -c '^minilm=com.chua.test.impl.MiniLMEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient

echo
echo "========== T3: stale annotation-derived alias lines pruned (历史冗余行清理) =========="
rm -rf target/test-spi-out
mkdir -p target/test-spi-out/META-INF/extensions
# 预置旧版处理器生成的冗余行：json=/application/json= 指向 @Spi 类（应被清理为发现行），manual= 手动行（应保留）
cat > target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient <<'EOF'
json=com.chua.test.impl.JsonEmbeddingClient
application/json=com.chua.test.impl.JsonEmbeddingClient
manual=com.chua.test.impl.ManualClient
EOF

javac -proc:only -cp "$JAR" -processorpath "$JAR" -d target/test-spi-out $SRC 2>&1
echo "COMPILE_EXIT:$?"
echo "--- pruned EmbeddingClient file ---"
cat target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "stale json= line pruned (expect 0): "
grep -c '^json=com.chua.test.impl.JsonEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient || true
echo -n "stale application/json= line pruned (expect 0): "
grep -c '^application/json=com.chua.test.impl.JsonEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient || true
echo -n "bare discovery line present (expect 1): "
grep -c '^com.chua.test.impl.JsonEmbeddingClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
echo -n "manual line retained (expect 1): "
grep -c '^manual=com.chua.test.impl.ManualClient$' target/test-spi-out/META-INF/extensions/com.chua.test.spi.EmbeddingClient
