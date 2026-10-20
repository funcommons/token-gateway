// task_type: image — 双上游适配: mock(联调/冒烟默认) + scapi(真渠道, P2-36 用户裁决 A 2026-10-20)
// 分流判据: routeSnapshot.baseUrl —— 含 ":9606"/"token-mock" 走 mock 腿, 其余走 scapi 真上游腿
//
// ── mock 腿契约 (token-mock 1.5.0 OpenAIProtocolHandler→ImageJobHandler, OpenAI background job 形态;
//    本环境壳=mmagix-token mock-service :9606, 渠道 baseUrl=http://localhost:9606/openai):
//   POST {base}/v1/images/generations   body {model, prompt, background:true}
//                                       → {id:"T<24HEX>", object:"image_generation", status:"queued",
//                                          created_at}   (submit 即自推进 in_progress, ≈150ms 后 completed)
//   GET  {base}/v1/images/generations/{id} → status 推进 queued → in_progress → completed / failed;
//                                       completed 带 output[].content[].image_url.url —— 相对路径
//                                       "/v1/resources/{jobId}/{i}", 本脚本 absolutize 成 {base}+url
//   GET  {base}/v1/resources/{jobId}/{i} → 1×1 占位 png (壳侧 MockImageResourceController 补路由;
//                                       token-mock#1: 1.5.0 无该 serve 路由, 上游修复后本壳可撤)
//
// ── scapi 腿契约 (与壳内 ScapiImageAdapter 同源, 「外仓 Worker 脚本契约为准」注释互证;
//    scapi.net 异步作业, 慢生成 → 任务面 24h 过期钟语义, 同步面 150s deadline 不适合):
//   POST {base}/api/async/image_gpt?key={apiKey}   头 Authorization: {apiKey} (原始值, 无 Bearer!)
//                                       body {prompt, size} (size=比例串/auto; urls[] ≤5 参考图透传)
//                                       → {code, data:{id}}   code!=200=提交失败
//   GET  {base}/api/async/detail?key={apiKey}&id={taskId} 同头
//                                       → {code, data:{status, result[]}} —— status 2=成功
//                                       (result[0]=URL 字符串; "data:image" 开头=内联失败不可透出)、
//                                       3=失败(msg)、0/1=进行中; HTTP 非 2xx 不判死继续轮询
//
// resultMapping 约定: resources=[绝对 url] + usage.outputType="png" → 网关 ResourceUrlConverter 据
//   outputType 发 /v1/resources/{taskNo}/{i}.png 代理 URL (后缀仅可读性; 上游 URL 永不透传)
// 同步性说明: image 上游=真异步作业 (有 id 有查询端点), create/poll 与 video 同构
// 故障演练: 无 payload 造败位 (mock 恒成功) — FAILED/退款语义由 video 负例代表 (ML-3-IMAGE 注释先例)
// 重启重领: lotask 语义=整体重跑 create → 重新 submit 一个新作业 (旧作业遗弃, mock 内存态零损;
//   scapi 腿重领同语义=新上游作业, 旧作业在上游自然过期, 不重复计费——计费在网关侧任务终态结算)
// Binding 注入: ctx(Map), http(ScriptHttpClient), log, json

private boolean isMock(String baseUrl) {
    return baseUrl != null && (baseUrl.contains(":9606") || baseUrl.contains("token-mock"))
}

def create(Map ctx) {
    def snap = ctx.routeSnapshot
    def p = ctx.payload
    if (isMock(snap.baseUrl)) {
        def resp = http.post("${snap.baseUrl}/v1/images/generations",
                [Authorization: "Bearer ${snap.apiKey}", "Content-Type": "application/json"],
                [model: p.model, prompt: p.input ?: p.params?.prompt ?: "", background: true])
        if (!resp.ok) {
            throw new RuntimeException("上游创建失败: HTTP ${resp.status}")
        }
        return [upstreamTaskId: resp.body.id, progressHint: 0]
    }
    // scapi 真上游: Authorization 原始值无 Bearer; size 缺省 auto; urls 参考图透传
    def body = [prompt: p.input ?: p.params?.prompt ?: "", size: p.params?.size ?: "auto"]
    if (p.params?.urls) {
        body.urls = p.params.urls
    }
    // apiKey 走 query 必须 URL encode (base64 形 key 的 +/= 在 query 里变形→上游 400; 适配器同款 encode)
    def encKey = java.net.URLEncoder.encode(snap.apiKey?.toString() ?: "", "UTF-8")
    def resp = http.post("${snap.baseUrl}/api/async/image_gpt?key=${encKey}".toString(),
            ["Authorization": snap.apiKey?.toString(), "Content-Type": "application/json"], body)
    if (!resp.ok) {
        throw new RuntimeException("scapi 提交失败: HTTP ${resp.status}")
    }
    if (resp.body?.code != 200) {
        throw new RuntimeException("scapi 提交失败: code=${resp.body?.code}")
    }
    def id = resp.body?.data?.id
    if (id == null) {
        throw new RuntimeException("scapi 提交失败: 响应缺 data.id")
    }
    return [upstreamTaskId: id.toString(), progressHint: 0]
}

def poll(Map ctx) {
    def snap = ctx.routeSnapshot
    if (isMock(snap.baseUrl)) {
        def resp = http.get("${snap.baseUrl}/v1/images/generations/${ctx.upstreamTaskId}",
                [Authorization: "Bearer ${snap.apiKey}"])
        if (!resp.ok) {
            return [state: "RUNNING", raw: [httpStatus: resp.status]]  // 查询异常不判死, 等下一轮
        }
        def data = resp.body
        def state = [completed: "SUCCEEDED", failed: "FAILED"].get(data.status, "RUNNING")
        // completed 体 output[].content[].image_url.url 为相对路径 — 绝对化后随 raw 传递 (resultMapping 消费)
        // ⚠ 拼接值显式 .toString(): GString 入钩子结果会被 fastjson2 按 bean 序列化成 {values:[...]}
        //   对象 (非字符串), resources 即废
        def urls = []
        (data.output ?: []).each { o ->
            (o?.content ?: []).each { c ->
                def u = c?.image_url?.url
                if (u != null) {
                    urls << (u.startsWith("http") ? u : "${snap.baseUrl}${u}".toString())
                }
            }
        }
        def raw = new LinkedHashMap(data)
        raw.urls = urls
        return [state: state, raw: raw,
                progressHint: data.status == "in_progress" ? 50 : 0,
                error: data.error?.message]
    }
    // scapi 真上游: 非 2xx/异常不判死 (三方抖动不应终止任务——与适配器同契约); status 2/3 终态
    def encKey2 = java.net.URLEncoder.encode(snap.apiKey?.toString() ?: "", "UTF-8")
    def encId = java.net.URLEncoder.encode(ctx.upstreamTaskId?.toString() ?: "", "UTF-8")
    def resp = http.get("${snap.baseUrl}/api/async/detail?key=${encKey2}&id=${encId}".toString(),
            ["Authorization": snap.apiKey?.toString()])
    if (!resp.ok) {
        return [state: "RUNNING", raw: [httpStatus: resp.status]]
    }
    def data = resp.body?.data
    if (resp.body?.code != 200 || data == null) {
        return [state: "RUNNING", raw: [code: resp.body?.code]]  // 业务码非 200 视作瞬态
    }
    def status = data.status
    if (status == 2) {
        def first = (data.result ?: [])[0]
        if (first == null) {
            return [state: "FAILED", error: "scapi 成功应答缺 result[0]"]
        }
        if (first.toString().startsWith("data:image")) {
            // base64 内联不可透出: 诚实终态失败 (与适配器同口径, 禁止把内联体当资源 URL 下发)
            return [state: "FAILED", error: "scapi 返回 base64 内联图, 不可透出"]
        }
        return [state: "SUCCEEDED", raw: [urls: [first.toString()]], progressHint: 100]
    }
    if (status == 3) {
        return [state: "FAILED", error: (data.msg ?: data.message ?: "scapi 上游失败 (status=3)").toString()]
    }
    return [state: "RUNNING", raw: [status: status], progressHint: status == 1 ? 50 : 0]
}

def resultMapping(Map ctx) {
    def raw = ctx.raw
    return [resources: raw.urls ?: [],
            usage: [outputType: "png", pieces: (raw.urls ?: []).size(), model: ctx.payload.model]]
}
