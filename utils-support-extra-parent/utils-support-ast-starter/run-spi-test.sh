#!/bin/bash
# 功能性验证：@SpiExtension 编译期生成 META-INF/extensions 索引文件
cd "$(dirname "$0")" || exit 1

JAR=target/utils-support-ast-starter-4.0.0.42.jar
rm -rf target/test-spi-out
mkdir -p target/test-spi-out

javac -proc:only -cp "$JAR" -processorpath "$JAR" -d target/test-spi-out $(find test-fixture -name '*.java') 2>&1
echo "COMPILE_EXIT:$?"

echo "=== generated files ==="
find target/test-spi-out -path '*META-INF*' -type f | sort
echo "=== content ==="
for f in $(find target/test-spi-out -path '*META-INF*' -type f | sort); do
  echo "--- $f ---"
  cat "$f"
done
