#!/bin/sh
# =============================================================================
# 模块区业务工程构建：逐工程 mvn install（本仓无聚合 pom，各工程独立 Maven 工程）
# 工程：mse-ai / mse-data / mse-fs / mse-im / mse-monitor / mse-open / mse-search
#（mse-mes / mse-oa / mse-mp 已拆分至各自仓库）
#
# 前置：mse-framework 仓已 sh build.sh（mse-starter-parent / mse-dependencies 经仓库坐标解析）
# 跨仓消费：mse-oa / mse-mp / mse-mes 仓依赖本仓的 ai-api / open-api / search-api（1.0.0-SNAPSHOT）
#
# 用法：
#   sh build.sh                                # 全部工程，缺省跳过测试
#   MSE_MODULES="mse-fs mse-search" sh build.sh # 只构建子集
#   MSE_RUN_ALL_TESTS=1 sh build.sh            # 放开单元测试
#   MAVEN_ARGS="-s /path/settings.xml" sh build.sh
# =============================================================================
set -eu
cd "$(dirname "$0")"

MVN="mvn -B -ntp ${MAVEN_ARGS:-}"
TEST_FLAG="-DskipTests"
[ "${MSE_RUN_ALL_TESTS:-0}" = "1" ] && TEST_FLAG=""

if [ -n "${MSE_MODULES:-}" ]; then
  PROJECTS=${MSE_MODULES}
else
  PROJECTS="mse-ai mse-data mse-fs mse-im mse-monitor mse-open mse-search"
fi

fail=""
total=0
for p in $PROJECTS; do
  [ -f "$p/pom.xml" ] || { echo "!! 跳过 $p（无 pom.xml）"; continue; }
  total=$((total + 1))
  echo "===== $p ====="
  if (cd "$p" && $MVN install $TEST_FLAG); then
    echo "--- $p OK"
  else
    echo "--- $p FAILED"
    fail="$fail $p"
  fi
done

echo ""
if [ -n "$fail" ]; then
  echo "构建失败（$fail），共 $total 个工程" >&2
  exit 1
fi
echo "模块区全部构建通过（$total 个工程）"
