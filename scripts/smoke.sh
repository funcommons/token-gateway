#!/usr/bin/env bash
# token-gateway 全链路冒烟 (M2.5 出口演练验收证据, 《05》§11 降级矩阵的正路径子集)
#
# 前置五进程:
#   redis                 docker run -d -p 6379:6379 redis:7-alpine
#   token-mock  :9999     docker run -d -p 9999:9999 ghcr.io/funcommons/token-mock
#   lotask4j    :8080     见 docs/开发文档/07_lotask4j租户开通手册.md
#   demo-control-plane :9400   java -jar demo-control-plane/target/demo-control-plane-*.jar
#   网关 + Worker          java -jar app/target/token-gateway-app-*.jar
#                       java -jar task-worker/target/task-worker-*.jar
#                        (网关/Worker 需配 LOTASK_URL/JWT 等环境变量, 见 07 手册冒烟节)
#
# 用法: bash scripts/smoke.sh
# 退出码: 0=全部 PASS, 1=有 FAIL (含预检失败)
set -uo pipefail

CP=${CP:-http://localhost:9400}          # demo-control-plane (控制层 demo + 回调靶)
GW=${GW:-http://localhost:9401}          # 网关
LOTASK=${LOTASK:-http://localhost:8080}  # lotask4j
MOCK=${MOCK:-http://localhost:9999}      # token-mock
DEMO_KEY=${DEMO_KEY:-sk-demo-ok123456}
NOTIFY_KEY_SET=${TGW_NOTIFY_SIGN_KEY:+yes}

PASS=0; FAIL=0
declare -a RESULTS

ok()   { PASS=$((PASS+1)); RESULTS+=("PASS  $1"); echo "  ✅ PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); RESULTS+=("FAIL  $1"); echo "  ❌ FAIL  $1"; }
step() { echo; echo "== $1 =="; }

# 断言 $1=描述 $2=实际 $3=期望
assert_eq() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (期望 $3, 实际 $2)"; fi; }
assert_contains() { if [[ "$2" == *"$3"* ]]; then ok "$1"; else bad "$1 (未包含: $3)"; fi; }

jsonfield() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    cur = d
    for k in '$1'.split('.'):
        if isinstance(cur, list): cur = cur[int(k)]
        else: cur = cur.get(k)
    print(json.dumps(cur) if isinstance(cur, (dict, list)) else ('' if cur is None else cur))
except Exception:
    print('')
" 2>/dev/null; }

echo "=============================================="
echo " token-gateway 全链路冒烟"
echo "=============================================="

step "0. 预检 (五进程可达性)"
check_url() {
  local name=$1 url=$2
  if curl -sf -o /dev/null --max-time 3 "$url"; then
    echo "  ✅ $name 可达 ($url)"
  else
    echo "  ❌ $name 不可达 ($url) — 见脚本头部启动说明"; MISSING=1
  fi
}
MISSING=0
check_url "token-mock        $MOCK"    "$MOCK/openai/v1/models"
check_url "lotask4j         $LOTASK"  "$LOTASK/actuator/health"
check_url "demo-control-plane $CP"    "$CP/demo/state"
check_url "网关             $GW"      "$GW/actuator/health"
check_url "Worker(dry-run)  :9411"    "http://localhost:9411/actuator/health"
if [ "$MISSING" = "1" ]; then
  echo; echo "预检失败, 终止。"; exit 1
fi
curl -sf "$CP/demo/reset" > /dev/null && echo "  ✅ 控制层 demo 已复位"

step "1. LLM 面 — 正路径 (chat 同步)"
RESP=$(curl -s "$GW/v1/chat/completions" \
  -H "Authorization: Bearer $DEMO_KEY" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"冒烟"}]}')
assert_contains "chat 同步返回 choices" "$(echo "$RESP" | jsonfield choices)" "content"

step "2. LLM 面 — 流式 (SSE)"
SSE=$(curl -s -N --max-time 30 "$GW/v1/chat/completions" \
  -H "Authorization: Bearer $DEMO_KEY" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"流式冒烟"}]}' \
  | head -c 2000)
assert_contains "SSE 首帧 data: 形状" "$SSE" "data:"

step "3. LLM 面 — 负路径四连"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$GW/v1/chat/completions" \
  -H "Authorization: Bearer sk-banned-xxx" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[]}')
assert_eq "sk-banned → HTTP 401" "$CODE" "401"
ENVELOPE=$(curl -s "$GW/v1/chat/completions" \
  -H "Authorization: Bearer sk-banned-xxx" -H "Content-Type: application/json" -d '{}')
assert_eq "sk-banned → 信封 10202" "$(echo "$ENVELOPE" | jsonfield code)" "10202"
ENVELOPE=$(curl -s "$GW/v1/chat/completions" \
  -H "Authorization: Bearer $DEMO_KEY" -H "Content-Type: application/json" \
  -d '{"model":"no-such-model","messages":[]}')
assert_eq "未知模型 → 信封 10400" "$(echo "$ENVELOPE" | jsonfield code)" "10400"
ENVELOPE=$(curl -s "$GW/v1/chat/completions" \
  -H "Authorization: Bearer $DEMO_KEY" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"这是一句违禁词测试"}]}')
assert_eq "内容含违禁词 → 信封 10106" "$(echo "$ENVELOPE" | jsonfield code)" "10106"
ENVELOPE=$(curl -s "$GW/v1/videos" \
  -H "Authorization: Bearer sk-poor-xxx" -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-poor-1" \
  -d '{"model":"vid-mock-1"}')
assert_eq "sk-poor 任务创建 → 信封 10617 (不产生任务)" "$(echo "$ENVELOPE" | jsonfield code)" "10617"

step "4. 任务面 — create → 执行 → SUCCEEDED (token-mock 自然节奏 ~60s)"
IDEM="smoke-$(date +%s)"
CREATE=$(curl -s "$GW/v1/videos" \
  -H "Authorization: Bearer $DEMO_KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM" \
  -d "{\"model\":\"vid-mock-1\",\"params\":{\"resolution\":\"720p\"},\"input\":\"冒烟视频\",\"notify_url\":\"$CP/callback\"}")
TASK_NO=$(echo "$CREATE" | jsonfield task_no)
assert_contains "create 返回 task_no (T 前缀)" "$TASK_NO" "T"
assert_eq "create 初始 PENDING" "$(echo "$CREATE" | jsonfield status)" "PENDING"

STATUS=""
for i in $(seq 1 30); do   # 30 × 5s = 150s 上限
  sleep 5
  POLL=$(curl -s "$GW/v1/videos/$TASK_NO" -H "Authorization: Bearer $DEMO_KEY")
  STATUS=$(echo "$POLL" | jsonfield status)
  echo "  ... poll[$i] status=$STATUS"
  [ "$STATUS" = "SUCCEEDED" ] || [ "$STATUS" = "FAILED" ] || [ "$STATUS" = "EXPIRED" ] && break
done
assert_eq "任务终态 SUCCEEDED" "$STATUS" "SUCCEEDED"
RESOURCES=$(echo "$POLL" | jsonfield result.resources)
assert_contains "resources 为代理 URL (含 exp+sig)" "$RESOURCES" "/v1/resources/"
[[ "$RESOURCES" == *"https://"* ]] && bad "上游原始 URL 泄漏 (出现 https:// 原文)" || ok "上游原始 URL 未透传"

step "5. 任务面 — 资源代理 (免凭证 + sig 校验)"
PROXY_URL=$(echo "$POLL" | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['resources'][0])" 2>/dev/null)
HTTP_CODE=$(curl -s -o /tmp/smoke-resource.bin -w '%{http_code}' "$GW$PROXY_URL")
assert_eq "代理 URL 免凭证拉取 200" "$HTTP_CODE" "200"
FILESIZE=$(wc -c < /tmp/smoke-resource.bin | tr -d ' ')
[ "$FILESIZE" -gt 1000 ] && ok "资源非空 ($FILESIZE bytes)" || bad "资源过小 ($FILESIZE bytes)"
TAMPER=$(echo "$PROXY_URL" | sed 's/sig=./sig=0/')
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$GW$TAMPER")
assert_eq "篡改 sig → 400" "$CODE" "400"

step "6. 任务面 — notify 回调 (验签 + 终态一致)"
NOTIFY_HIT=""
for i in $(seq 1 10); do
  NOTIFY=$(curl -s "$CP/demo/notifications")
  NOTIFY_HIT=$(echo "$NOTIFY" | python3 -c "
import sys, json
for r in json.load(sys.stdin):
    if r.get('task_no') == '$TASK_NO' and r.get('status') == 'SUCCEEDED':
        print('verified=' + str(r.get('verified')).lower()); break
else: print('')
" 2>/dev/null)
  [ -n "$NOTIFY_HIT" ] && break
  sleep 3
done
if [ -n "$NOTIFY_HIT" ]; then
  ok "notify 已收到且终态 SUCCEEDED"
  if [ "$NOTIFY_KEY_SET" = "yes" ]; then
    assert_eq "notify 验签通过 (X-THMP-Signature)" "$NOTIFY_HIT" "verified=true"
  else
    echo "  ℹ️  TGW_NOTIFY_SIGN_KEY 未设 (网关/控制层两侧), 验签断言跳过"
  fi
else
  bad "notify 未收到 (60s 内)"
fi

step "7. 对账零差异 (预扣-终态闭环)"
OPEN=$(curl -s "$CP/demo/state" | jsonfield openHolds)
assert_eq "控制层账本无未闭环预扣 (openHolds 空)" "$OPEN" "{}"

step "8. 幂等 — 同 Idempotency-Key 重复 create 被拒绝式去重"
DUP=$(curl -s -o /dev/null -w '%{http_code}' "$GW/v1/videos" \
  -H "Authorization: Bearer $DEMO_KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM" \
  -d '{"model":"vid-mock-1"}')
assert_eq "重复 create → 409/10501" "$DUP" "409"

echo
echo "=============================================="
echo " 结果矩阵"
echo "=============================================="
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo "----------------------------------------------"
echo " PASS=$PASS FAIL=$FAIL"
echo "=============================================="
[ "$FAIL" = "0" ] && exit 0 || exit 1
