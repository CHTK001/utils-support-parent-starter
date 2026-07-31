#!/bin/bash
set -e

# This script runs inside a privileged container that has Maven installed
# It builds the project from /app/source (mounted) and runs benchmarks

cd /app

# Copy sources from mounted volume
cp -r /source/utils-support-extra-parent/utils-support-example-starter .
cp -r /source/utils-support-core-parent/utils-support-common-starter ./utils-support-core-parent/ 2>/dev/null || true
cp -r /source/utils-support-core-parent/utils-support-network-starter ./utils-support-core-parent/ 2>/dev/null || true
cp -r /source/utils-support-network-parent/utils-support-netty-starter ./utils-support-network-parent/ 2>/dev/null || true
cp -r /source/utils-support-network-parent/utils-support-vertx-starter ./utils-support-network-parent/ 2>/dev/null || true
cp /source/pom.xml . 2>/dev/null || true

cp -r /source/utils-support-core-parent/pom.xml ./utils-support-core-parent/ 2>/dev/null || true
cp -r /source/utils-support-extra-parent/pom.xml ./utils-support-extra-parent/ 2>/dev/null || true
cp -r /source/utils-support-network-parent/pom.xml ./utils-support-network-parent/ 2>/dev/null || true

# Set up build environment
export JAVA_HOME=/opt/java/openjdk
export PATH=$JAVA_HOME/bin:$PATH

# Build dependencies
echo "===== Building common-starter ====="
mvn install -pl utils-support-core-parent/utils-support-common-starter -DskipTests -Dmaven.compiler.failOnError=false -q 2>&1 | tail -5

echo "===== Building network-starter ====="
mvn install -pl utils-support-core-parent/utils-support-network-starter -DskipTests -q 2>&1 | tail -5

echo "===== Building netty-starter ====="
mvn install -pl utils-support-network-parent/utils-support-netty-starter -DskipTests -q 2>&1 | tail -5

echo "===== Building vertx-starter ====="
mvn install -pl utils-support-network-parent/utils-support-vertx-starter -DskipTests -q 2>&1 | tail -5

echo "===== Compiling example-starter ====="
mvn compile -pl utils-support-extra-parent/utils-support-example-starter -DskipTests -q -Dmaven.compiler.failOnError=false 2>&1 | tail -5

# Build classpath
mvn dependency:build-classpath -pl utils-support-extra-parent/utils-support-example-starter -Dmdep.outputFile=/tmp/cp.txt -q 2>/dev/null
CP="utils-support-extra-parent/utils-support-example-starter/target/classes:$(cat /tmp/cp.txt)"

mkdir -p /app/benchmark-results

BENCH_CONNS="${BENCH_CONNS:-100 1000 2000 5000}"
BENCH_DURATION="${BENCH_DURATION:-30}"

run_benchmark() {
    local name="$1"
    local cmd="$2"

    echo ""
    echo "===== Starting $name ====="

    eval "$cmd" > "/app/benchmark-results/${name}-server.log" 2>&1 &
    local SERVER_PID=$!

    sleep 5

    local result_file="/app/benchmark-results/${name}.txt"
    echo "Benchmark: $name" > "$result_file"
    echo "Date: $(date)" >> "$result_file"
    echo "" >> "$result_file"

    for conns in $BENCH_CONNS; do
        echo "--- $name: connections=$conns ---"
        echo "" >> "$result_file"
        echo "=== connections=$conns, duration=${BENCH_DURATION}s ===" >> "$result_file"
        java -cp "$CP" \
            com.chua.example.network.benchmark.HttpBenchmark \
            "http://127.0.0.1:$3/" \
            "$conns" \
            4 \
            "$BENCH_DURATION" 2>&1 | tee -a "$result_file"
        echo "" >> "$result_file"
    done

    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
    sleep 2
}

# 1. HTTP Server Benchmarks
run_benchmark "http-jdk" "java -cp '$CP' com.chua.example.network.server.HttpServerExample jdk 8100 --benchmark" 8100
run_benchmark "http-netty" "java -cp '$CP' com.chua.example.network.server.HttpServerExample netty-http 8102 --benchmark" 8102
run_benchmark "http-vertx" "java -cp '$CP' com.chua.example.network.server.HttpServerExample vertx-http 8103 --benchmark" 8103

# 2. HTTP Proxy Benchmarks
run_benchmark "http-proxy-reverse" "java -cp '$CP' com.chua.example.network.proxy.HttpProxyServerExample reverse-proxy 8200 8201 --benchmark" 8200
run_benchmark "http-proxy-netty" "java -cp '$CP' com.chua.example.network.proxy.HttpProxyServerExample netty-proxy 8210 8211 --benchmark" 8210

# 3. TCP Proxy Benchmarks
run_benchmark "tcp-proxy" "java -cp '$CP' com.chua.example.network.proxy.tcp.TcpProxyExample tcp-proxy 8300 8301 --benchmark" 8300

echo ""
echo "===== ALL BENCHMARKS COMPLETE ====="
echo "Results in /app/benchmark-results/"

# Print summary
echo ""
echo "===== BENCHMARK SUMMARY ====="
for f in /app/benchmark-results/*.txt; do
    if [ -f "$f" ]; then
        echo ""
        echo "=== $(basename "$f") ==="
        grep -E "^(RPS|Total reqs|P50|P99|Errors)" "$f" 2>/dev/null | head -20
    fi
done