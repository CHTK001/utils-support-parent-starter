#!/bin/bash
set -e

BENCH_CONNS="${BENCH_CONNS:-100 1000 2000 5000}"
BENCH_DURATION="${BENCH_DURATION:-30}"
BENCH_THREADS="${BENCH_THREADS:-4}"
RESULTS_DIR="${RESULTS_DIR:-/app/benchmark-results}"
mkdir -p "$RESULTS_DIR"

benchmark_http_server() {
    local type="$1"
    local port="$2"
    local name="http-$type"

    echo ""
    echo "===== HTTP Server Benchmark: $type (port=$port) ====="

    mvn exec:java -pl utils-support-extra-parent/utils-support-example-starter \
        -Dexec.mainClass=com.chua.example.network.server.HttpServerExample \
        -Dexec.args="$type $port --benchmark" > "$RESULTS_DIR/${name}-server.log" 2>&1 &
    local SERVER_PID=$!

    sleep 5

    local result_file="$RESULTS_DIR/${name}.txt"
    echo "Benchmark: HTTP Server $type" > "$result_file"
    echo "Date: $(date)" >> "$result_file"
    echo "URL: http://127.0.0.1:$port/" >> "$result_file"
    echo "" >> "$result_file"

    for conns in $BENCH_CONNS; do
        echo "--- $name: connections=$conns, duration=${BENCH_DURATION}s ---"
        echo "" >> "$result_file"
        echo "=== connections=$conns, threads=$BENCH_THREADS, duration=${BENCH_DURATION}s ===" >> "$result_file"
        java -cp /app/target/utils-support-example-starter-*.jar:/root/.m2/repository -cp target/classes:$(mvn dependency:build-classpath -pl utils-support-extra-parent/utils-support-example-starter -q -Dmdep.outputFile=/tmp/cp.txt 2>/dev/null && cat /tmp/cp.txt) \
            com.chua.example.network.benchmark.HttpBenchmark \
            "http://127.0.0.1:$port/" \
            "$conns" \
            "$BENCH_THREADS" \
            "$BENCH_DURATION" 2>&1 | tee -a "$result_file"
        echo "" >> "$result_file"
    done

    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
    sleep 2
}

benchmark_http_proxy() {
    local proxy_type="$1"
    local proxy_port="$2"
    local backend_port="$3"
    local name="http-proxy-$proxy_type"

    echo ""
    echo "===== HTTP Proxy Benchmark: $proxy_type (port=$proxy_port -> $backend_port) ====="

    mvn exec:java -pl utils-support-extra-parent/utils-support-example-starter \
        -Dexec.mainClass=com.chua.example.network.proxy.HttpProxyServerExample \
        -Dexec.args="$proxy_type $proxy_port $backend_port --benchmark" > "$RESULTS_DIR/${name}-server.log" 2>&1 &
    local PROXY_PID=$!

    sleep 6

    local result_file="$RESULTS_DIR/${name}.txt"
    echo "Benchmark: HTTP Proxy $proxy_type" > "$result_file"
    echo "Date: $(date)" >> "$result_file"
    echo "URL: http://127.0.0.1:$proxy_port/ -> backend=$backend_port" >> "$result_file"
    echo "" >> "$result_file"

    for conns in $BENCH_CONNS; do
        echo "--- $name: connections=$conns, duration=${BENCH_DURATION}s ---"
        echo "" >> "$result_file"
        echo "=== connections=$conns, threads=$BENCH_THREADS, duration=${BENCH_DURATION}s ===" >> "$result_file"
        java -cp target/classes:$(mvn dependency:build-classpath -pl utils-support-extra-parent/utils-support-example-starter -q -Dmdep.outputFile=/tmp/cp.txt 2>/dev/null && cat /tmp/cp.txt) \
            com.chua.example.network.benchmark.HttpBenchmark \
            "http://127.0.0.1:$proxy_port/" \
            "$conns" \
            "$BENCH_THREADS" \
            "$BENCH_DURATION" 2>&1 | tee -a "$result_file"
        echo "" >> "$result_file"
    done

    kill $PROXY_PID 2>/dev/null || true
    wait $PROXY_PID 2>/dev/null || true
    sleep 2
}

benchmark_tcp_proxy() {
    local proxy_type="$1"
    local listen_port="$2"
    local backend_port="$3"
    local name="tcp-proxy-$proxy_type"

    echo ""
    echo "===== TCP Proxy Benchmark: $proxy_type (port=$listen_port -> $backend_port) ====="

    mvn exec:java -pl utils-support-extra-parent/utils-support-example-starter \
        -Dexec.mainClass=com.chua.example.network.proxy.tcp.TcpProxyExample \
        -Dexec.args="$proxy_type $listen_port $backend_port --benchmark" > "$RESULTS_DIR/${name}-server.log" 2>&1 &
    local PROXY_PID=$!

    sleep 4

    local result_file="$RESULTS_DIR/${name}.txt"
    echo "Benchmark: TCP Proxy $proxy_type" > "$result_file"
    echo "Date: $(date)" >> "$result_file"
    echo "" >> "$result_file"

    for conns in $BENCH_CONNS; do
        echo "--- $name: connections=$conns, duration=${BENCH_DURATION}s ---"
        echo "" >> "$result_file"
        echo "=== connections=$conns, threads=$BENCH_THREADS, duration=${BENCH_DURATION}s ===" >> "$result_file"
        java -cp target/classes:$(mvn dependency:build-classpath -pl utils-support-extra-parent/utils-support-example-starter -q -Dmdep.outputFile=/tmp/cp.txt 2>/dev/null && cat /tmp/cp.txt) \
            com.chua.example.network.benchmark.HttpBenchmark \
            "http://127.0.0.1:$listen_port/" \
            "$conns" \
            "$BENCH_THREADS" \
            "$BENCH_DURATION" 2>&1 | tee -a "$result_file"
        echo "" >> "$result_file"
    done

    kill $PROXY_PID 2>/dev/null || true
    wait $PROXY_PID 2>/dev/null || true
    sleep 2
}

echo "=========================================="
echo "  Starting All Benchmarks (Java HTTP Client)"
echo "  Connections: $BENCH_CONNS"
echo "  Duration: ${BENCH_DURATION}s"
echo "=========================================="

# 1. HTTP Server Benchmarks
benchmark_http_server "jdk" 8100 || true
benchmark_http_server "netty-http" 8102 || true
benchmark_http_server "vertx-http" 8103 || true

# 2. HTTP Proxy Benchmarks
benchmark_http_proxy "reverse-proxy" 8200 8201 || true
benchmark_http_proxy "netty-proxy" 8210 8211 || true

# 3. TCP Proxy Benchmarks
benchmark_tcp_proxy "tcp-proxy" 8300 8301 || true

echo ""
echo "=========================================="
echo "  All Benchmarks Complete"
echo "  Results directory: $RESULTS_DIR"
echo "=========================================="

# Print summary
echo ""
echo "===== BENCHMARK SUMMARY ====="
for f in "$RESULTS_DIR"/*.txt; do
    if [ -f "$f" ]; then
        echo ""
        echo "=== $(basename "$f") ==="
        grep -E "^(RPS|Total reqs|Errors|P50|P99)" "$f" 2>/dev/null || true
    fi
done