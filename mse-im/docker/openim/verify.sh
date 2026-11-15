#!/usr/bin/env bash
# OpenIM 待验证项 curl 定型脚本（docs/tech/openim-integration.md 风险表 #1/#3 与 TODO P0-1 配套）。
# 用法: ./verify.sh [API_BASE] [SECRET]
#   API_BASE 默认 http://127.0.0.1:10002；SECRET 默认取同目录 .env 的 OPENIM_SECRET。
# 输出的原始响应即定型依据：重复注册错误码 / get_users_info 存在性 / get_user_token 结构 /
# force_logout 存在性——结论回写方案文档与 mse-modules/mse-im/docs/TODO.md。
set -euo pipefail

cd "$(dirname "$0")"
API="${1:-http://127.0.0.1:10002}"
SECRET="${2:-$(grep -E '^OPENIM_SECRET=' .env | cut -d= -f2)}"
opid() { echo "mseim-$(date +%s%3N)-$RANDOM"; }
step() { printf '\n=== %s ===\n' "$1"; }

step "1. get_admin_token（白名单接口，凭 secret 换 admin token）"
ADMIN_JSON=$(curl -sSf -X POST "$API/auth/get_admin_token" \
  -H 'Content-Type: application/json' -H "operationID: $(opid)" \
  -d "{\"secret\":\"$SECRET\",\"userID\":\"imAdmin\"}")
echo "$ADMIN_JSON"
ADMIN_TOKEN=$(printf '%s' "$ADMIN_JSON" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$ADMIN_TOKEN" ] || { echo "FAIL: 未取得 admin token（检查 secret 与 .env 是否一致）"; exit 1; }

step "2. user_register 首次注册（userID=10001，应 errCode=0）"
curl -s -X POST "$API/user/user_register" \
  -H 'Content-Type: application/json' -H "operationID: $(opid)" -H "token: $ADMIN_TOKEN" \
  -d '{"users":[{"userID":"10001","nickname":"PoC 用户一"}]}'
echo

step "3. user_register 重复注册（错误码/语义 → 懒供给实现定型依据）"
curl -s -X POST "$API/user/user_register" \
  -H 'Content-Type: application/json' -H "operationID: $(opid)" -H "token: $ADMIN_TOKEN" \
  -d '{"users":[{"userID":"10001","nickname":"PoC 用户一"}]}'
echo

step "4. get_users_info 存在性查询（data.users 数组语义）"
curl -s -X POST "$API/user/get_users_info" \
  -H 'Content-Type: application/json' -H "operationID: $(opid)" -H "token: $ADMIN_TOKEN" \
  -d '{"userIDs":["10001"]}'
echo

step "5. get_user_token 签发 IM token（platformID=5 Web）"
curl -s -X POST "$API/auth/get_user_token" \
  -H 'Content-Type: application/json' -H "operationID: $(opid)" -H "token: $ADMIN_TOKEN" \
  -d '{"userID":"10001","platformID":5}'
echo

step "6. auth/force_logout 管理端强制下线（body 须带 platformID，2026-09-27 定型）"
curl -s -X POST "$API/auth/force_logout" \
  -H 'Content-Type: application/json' -H "operationID: $(opid)" -H "token: $ADMIN_TOKEN" \
  -d '{"userID":"10001","platformID":5}'
echo

step "7. user/update_user_info 管理端更新资料（body {userInfo:{userID,...}}，2026-09-27 定型）"
curl -s -X POST "$API/user/update_user_info" \
  -H 'Content-Type: application/json' -H "operationID: $(opid)" -H "token: $ADMIN_TOKEN" \
  -d '{"userInfo":{"userID":"10001","nickname":"PoC-Renamed","faceURL":""}}'
echo

printf '\n完成。请对照第 3/4/6 步响应回写：\n  docs/tech/openim-integration.md（5.1/5.4/风险表 #1/#3）\n  mse-modules/mse-im/docs/TODO.md（P0-1 勾选）\n'
