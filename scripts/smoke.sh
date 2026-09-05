#!/usr/bin/env bash
# token-gateway 全链路冒烟 (M2.5 出口演练验收证据, 《05》§11 降级矩阵的正路径子集)
#
# 前置五进程:
#   redis                 docker run -d -p 6379:6379 redis:7-alpine
#   token-mock  :9999     docker run -d -p 9999:9999 ghcr.io/funcommons/token-mock
#   lotask4j    :8080     见 docs/开发文档/07_lotask4j租户开通手册.md
#   demo-control-plane :9400   java -jar demo-control-plane/target/demo-control-plane-*.jar
#   网关 + Worker          java -jar app/target/token-gateway-app-*.jar
#                       java -jar task-worker/target/task-worker-*-exec.jar
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

# Redis 访问 (负路径 deadline 注入 / meta 反查用): 原生 redis-cli, 回退 docker exec
if command -v redis-cli >/dev/null 2>&1; then
  REDIS_CLI="redis-cli"
else
  REDIS_CLI="docker exec ${REDIS_CONTAINER:-redis} redis-cli"
fi

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
  local name=$1 url=$2 hdr=${3:-}
  local args=(-sf -o /dev/null --max-time 3)
  [ -n "$hdr" ] && args+=(-H "$hdr")
  if curl "${args[@]}" "$url"; then
    echo "  ✅ $name 可达 ($url)"
  else
    echo "  ❌ $name 不可达 ($url) — 见脚本头部启动说明"; MISSING=1
  fi
}
MISSING=0
# token-mock 全端点验 vendor key (含 models), 预检带 openai vendor key
check_url "token-mock        $MOCK"    "$MOCK/openai/v1/models"  "Authorization: Bearer sk-mock-openai-1234567890abcdef"
check_url "lotask4j         $LOTASK"  "$LOTASK/actuator/health"
check_url "demo-control-plane $CP"    "$CP/demo/state"
check_url "网关             $GW"      "$GW/actuator/health"
check_url "Worker(dry-run)  :9411"    "http://localhost:9411/actuator/health"
if $REDIS_CLI PING >/dev/null 2>&1; then
  echo "  ✅ redis 可达 ($REDIS_CLI)"
else
  echo "  ❌ redis 不可达 (REDIS_CONTAINER=${REDIS_CONTAINER:-redis})"; MISSING=1
fi
if [ "$MISSING" = "1" ]; then
  echo; echo "预检失败, 终止。"; exit 1
fi
curl -sf -X POST "$CP/demo/reset" > /dev/null && echo "  ✅ 控制层 demo 已复位"

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
  -H "Idempotency-Key: smoke-poor-$(date +%s)" \
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
# token-mock 产物为最小合法 MP4 (ftyp box 头, ~28B): 校验非空 + MP4 魔数而非字节数
if [ "$FILESIZE" -ge 20 ] && head -c 12 /tmp/smoke-resource.bin | grep -q "ftyp"; then
  ok "资源非空且为 MP4 头 ($FILESIZE bytes)"
else
  bad "资源异常 ($FILESIZE bytes, 缺 ftyp 魔数)"
fi
TAMPER=$(echo "$PROXY_URL" | sed 's/sig=./sig=0/')
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$GW$TAMPER")
assert_eq "篡改 sig → 400" "$CODE" "400"

step "6. 任务面 — 未知任务 poll (404 + 业务码 10400)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$GW/v1/audios/T0000000000000000000" \
  -H "Authorization: Bearer $DEMO_KEY")
assert_eq "未知 task_no poll → HTTP 404" "$CODE" "404"
ENVELOPE=$(curl -s "$GW/v1/audios/T0000000000000000000" -H "Authorization: Bearer $DEMO_KEY")
assert_eq "未知 task_no poll → 信封 10400" "$(echo "$ENVELOPE" | jsonfield code)" "10400"

step "7. 负路径 — webhook 篡改 (伪造 FAILED 不得生效; 无验签走 verify-then-act 回查)"
# audio 模态无 Worker 脚本 → 平台任务恒 QUEUED (确定性零竞态), 同时承载 7/8 两步
EXP_T=$(curl -s "$GW/v1/audios" \
  -H "Authorization: Bearer $DEMO_KEY" -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-exp-$(date +%s)" \
  -d "{\"model\":\"vid-mock-1\",\"input\":\"篡改/超时演练\",\"notify_url\":\"$CP/callback\"}" \
  | jsonfield task_no)
assert_contains "audio create 返回 task_no" "$EXP_T" "T"
LOTASK_ID=$($REDIS_CLI GET "tgw:task:meta:$EXP_T" | jsonfield lotaskId)
[ -n "$LOTASK_ID" ] && ok "meta 可读 (lotaskId 反查锚点)" || bad "meta 不可读: $EXP_T"
FORGE=$(curl -s -X POST "$GW/internal/lotask/webhook" \
  -H "X-ASTS-Event-Id: smoke-forge-$(date +%s)" \
  -H "X-ASTS-Timestamp: 1788329615000" \
  -H "X-ASTS-Signature: $(printf '0%.0s' $(seq 1 64))" \
  -H "Content-Type: application/json" \
  -d "{\"task_id\":\"$LOTASK_ID\",\"status\":\"FAILED\",\"result\":{\"error\":\"forged\"}}")
assert_contains "篡改签名 → 拒载荷 + 回查平台 (mode=reconciled)" "$FORGE" "reconciled"
sleep 3
FORGE_STATUS=$(curl -s "$GW/v1/audios/$EXP_T" -H "Authorization: Bearer $DEMO_KEY" | jsonfield status)
if [ "$FORGE_STATUS" = "FAILED" ]; then
  bad "伪造 FAILED 生效 (状态污染!)"
else
  ok "伪造 FAILED 不生效 (状态仍 $FORGE_STATUS)"
fi
FORGE_NOTIFY=$(curl -s "$CP/demo/notifications" | python3 -c "
import sys, json
hits = [r for r in json.load(sys.stdin)
        if r.get('task_no') == '$EXP_T' and r.get('status') == 'FAILED']
print('yes' if hits else 'no')
" 2>/dev/null)
assert_eq "伪造 FAILED 未产生 notify" "$FORGE_NOTIFY" "no"

step "8. 超时钟 — deadline 注入 → EXPIRED 判定 + 全额退款闭环"
$REDIS_CLI ZADD tgw:task:deadlines $(( $(date +%s) * 1000 - 1000 )) "$EXP_T" > /dev/null
EXP_POLL=""; EXP_STATUS=""
for i in $(seq 1 24); do   # 24 × 5s = 120s 上限 (超时钟扫描周期 60s)
  sleep 5
  EXP_POLL=$(curl -s "$GW/v1/audios/$EXP_T" -H "Authorization: Bearer $DEMO_KEY")
  EXP_STATUS=$(echo "$EXP_POLL" | jsonfield status)
  echo "  ... poll[$i] status=$EXP_STATUS"
  [ "$EXP_STATUS" = "EXPIRED" ] && break
done
assert_eq "超时钟判定 EXPIRED" "$EXP_STATUS" "EXPIRED"
assert_eq "EXPIRED 错误码 TIMEOUT" "$(echo "$EXP_POLL" | jsonfield error.code)" "TIMEOUT"
EXP_NOTIFY=$(curl -s "$CP/demo/notifications" | python3 -c "
import sys, json
hits = [r for r in json.load(sys.stdin)
        if r.get('task_no') == '$EXP_T' and r.get('status') == 'EXPIRED']
print('yes' if hits else 'no')
" 2>/dev/null)
assert_eq "EXPIRED notify 已发" "$EXP_NOTIFY" "yes"

step "9. 任务面 — notify 回调 (验签 + 终态一致)"
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

step "10. 对账零差异 (SUCCEEDED settle + EXPIRED refund 双路径闭环)"
OPEN=$(curl -s "$CP/demo/state" | jsonfield openHolds)
assert_eq "控制层账本无未闭环预扣 (openHolds 空)" "$OPEN" "{}"

step "11. 幂等 — 同 Idempotency-Key 重复 create 被拒绝式去重"
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
