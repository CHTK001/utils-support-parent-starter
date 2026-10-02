#!/bin/bash
# verify-all.sh 的敏感性对照：用受控输出的 mvn 替身，验证它能否识破各种「假绿」。
#
# 为什么必须这样验：verify-all.sh 的全部价值在于「BUILD SUCCESS 但测试没跑」
# 这种输出能被判为失败。而这段判定逻辑如果写错了，它自己就会变成一个
# 永远说 ALL_GREEN 的假绿工具 —— 那比没有这个脚本更糟。
#
# 替身按 -pl 的模块名返回不同的 Maven 输出，覆盖五种判定分支。
set -u
ROOT=/home/kali/verifytest
REPO=$ROOT/fakerepo
BIN=$ROOT/bin

rm -rf "$REPO" "$BIN"
mkdir -p "$REPO" "$BIN"

# ---- 构造一个最小仓库：根 pom + 5 个模块，每个模块带一个测试文件 ----
cat > "$REPO/pom.xml" <<'EOF'
<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging></project>
EOF
for m in mod-ok mod-skipped mod-zero mod-fail mod-nosummary utils-support-test-app; do
  mkdir -p "$REPO/$m/src/test/java"
  cat > "$REPO/$m/pom.xml" <<EOF
<project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>$m</artifactId></project>
EOF
  echo "class T_${m}Test {}" > "$REPO/$m/src/test/java/${m}Test.java"
done

# ---- mvn 替身 ----
cat > "$BIN/mvn" <<'EOF'
#!/bin/bash
mod=""
prev=""
for a in "$@"; do
  if [ "$prev" = "-pl" ]; then mod="$a"; fi
  prev="$a"
done
case "$mod" in
  mod-ok)
    echo "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0"
    echo "[INFO] BUILD SUCCESS" ;;
  mod-skipped)
    # 这就是本仓父 POM 的真实形态：绿灯但一个测试没跑
    echo "[INFO] Tests are skipped."
    echo "[INFO] BUILD SUCCESS" ;;
  mod-zero)
    echo "[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0"
    echo "[INFO] BUILD SUCCESS" ;;
  mod-fail)
    echo "[INFO] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0"
    echo "[INFO] BUILD FAILURE" ;;
  mod-nosummary)
    echo "[INFO] BUILD SUCCESS" ;;
  utils-support-test-app)
    echo "[INFO] Tests run: 4, Failures: 2, Errors: 1, Skipped: 0"
    echo "[INFO] BUILD FAILURE" ;;
  *)
    echo "[INFO] BUILD SUCCESS" ;;
esac
exit 0
EOF
chmod +x "$BIN/mvn"

echo "===== ① 严格模式：应当判 BLOCKED，并把三种假绿全部点出来 ====="
PATH="$BIN:$PATH" bash "$ROOT/verify-all.sh" --repo "$REPO"
rc1=$?
echo "strict exit=$rc1"

echo ""
echo "===== ② 放宽模式：mod-envdep 应降级为 WARN，但三种假绿仍必须阻断 ====="
PATH="$BIN:$PATH" bash "$ROOT/verify-all.sh" --repo "$REPO" --allow-env-dependent
rc2=$?

echo ""
echo "===== 判定 ====="
fail=0
strict_txt=$(PATH="$BIN:$PATH" bash "$ROOT/verify-all.sh" --repo "$REPO" 2>&1)
relaxed_txt=$(PATH="$BIN:$PATH" bash "$ROOT/verify-all.sh" --repo "$REPO" --allow-env-dependent 2>&1)
grep_txt="$strict_txt"

check() {
  if printf '%s' "$strict_txt" | grep -q "$1"; then
    echo "  OK       检出 $1"
  else
    echo "  MISSING  未检出 $1"
    fail=1
  fi
}
check "SKIPPED .* mod-skipped"
check "ZERO_TESTS .* mod-zero"
check "FAILED .* mod-fail"
check "NO_SUMMARY .* mod-nosummary"
check "OK run=12 F=0 E=0 .* mod-ok"

if [ "$rc1" -ne 1 ]; then
  echo "  MISSING  严格模式应以 exit 1 结束，实际 $rc1"
  fail=1
else
  echo "  OK       严格模式 exit=1"
fi

if printf '%s' "$relaxed_txt" | grep -qE "WARN +utils-support-test-app"; then
  echo "  OK       放宽模式把 utils-support-test-app 降级为 WARN"
else
  echo "  MISSING  放宽模式未把 utils-support-test-app 降级为 WARN"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "RESULT=CONTROL_FAILED 判定逻辑没能识破全部假绿形态"
  exit 1
fi
echo "RESULT=CONTROL_OK verify-all.sh 能识破全部假绿形态"
exit 0