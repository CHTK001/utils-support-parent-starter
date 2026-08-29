#!/bin/bash
# ============================================================
#  向量存储测试启动脚本（JDK 25 incubator vector 模块）
#  用法: ./start-vector-tests.sh [测试类]
#       ./start-vector-tests.sh VectorStarterTest
#       ./start-vector-tests.sh VectorPerfBench
# ============================================================

# Java 路径
JAVA="${JAVA_HOME:-/usr/local/java}/bin/java"
if [ ! -x "$JAVA" ]; then
    JAVA=$(which java 2>/dev/null)
fi
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
    echo "[ERROR] Java not found" >&2
    exit 1
fi

# Maven repo
MVN_REPO="${MVN_REPO:-$HOME/.m2/repository}"
JVECTOR_JAR="$MVN_REPO/io/github/jbellis/jvector/4.0.0-rc.9/jvector-4.0.0-rc.9.jar"
AGRONA_JAR="$MVN_REPO/org/agrona/agrona/1.20.0/agrona-1.20.0.jar"

# 构建 classpath
CP="$MVN_REPO/com/chua/utils-support-common-starter/4.0.0.42/utils-support-common-starter-4.0.0.42.jar"
CP="$CP:$MVN_REPO/com/chua/utils-support-datasource-starter/4.0.0.42/utils-support-datasource-starter-4.0.0.42.jar"
CP="$CP:$MVN_REPO/com/chua/utils-support-jvector-starter/4.0.0.42/utils-support-jvector-starter-4.0.0.42.jar"
CP="$CP:$JVECTOR_JAR"
CP="$CP:$MVN_REPO/com/google/guava/guava/33.2.1-jre/guava-33.2.1-jre.jar"
CP="$CP:$MVN_REPO/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"
CP="$CP:$AGRONA_JAR"
CP="$CP:$(dirname "$0")/target/classes"

# 主类
MAIN="${1:-VectorStarterTest}"

echo "============================================================"
echo " 向量存储测试: $MAIN"
echo " Java: $JAVA"
echo " Module: jdk.incubator.vector (enabled)"
echo "============================================================"

exec "$JAVA" --add-modules jdk.incubator.vector -cp "$CP" com.chua.vector.support."$MAIN"
