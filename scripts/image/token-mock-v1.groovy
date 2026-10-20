// task_type: image — token-mock 上游适配 (联调/冒烟默认上游; ML-3-IMAGE / flux-2-pro, 30 元/张)
// 上游契约 (token-mock 1.5.0 OpenAIProtocolHandler→ImageJobHandler, OpenAI background job 形态;
//   本环境壳=mmagix-token mock-service :9606, 渠道 baseUrl=http://localhost:9606/openai):
//   POST {base}/v1/images/generations   body {model, prompt, background:true}
//                                       → {id:"T<24HEX>", object:"image_generation", status:"queued",
//                                          created_at}   (submit 即自推进 in_progress, ≈150ms 后 completed)
//   GET  {base}/v1/images/generations/{id} → status 推进 queued → in_progress → completed / failed;
//                                       completed 带 output[].content[].image_url.url —— 相对路径
//                                       "/v1/resources/{jobId}/{i}", 本脚本 absolutize 成 {base}+url
//   GET  {base}/v1/resources/{jobId}/{i} → 1×1 占位 png (壳侧 MockImageResourceController 补路由;
//                                       token-mock#1: 1.5.0 无该 serve 路由, 上游修复后本壳可撤)
// resultMapping 约定: resources=[绝对 url] + usage.outputType="png" → 网关 ResourceUrlConverter 据
//   outputType 发 /v1/resources/{taskNo}/{i}.png 代理 URL (后缀仅可读性; 上游 URL 永不透传)
// 同步性说明: image 上游=真异步作业 (有 id 有查询端点), create/poll 与 video 同构
// 故障演练: 无 payload 造败位 (mock 恒成功) — FAILED/退款语义由 video 负例代表 (ML-3-IMAGE 注释先例)
// 重启重领: lotask 语义=整体重跑 create → 重新 submit 一个新 mock 作业 (旧作业遗弃, mock 内存态零损)
// Binding 注入: ctx(Map), http(ScriptHttpClient), log, json

def create(Map ctx) {
    def snap = ctx.routeSnapshot
    def p = ctx.payload
    def resp = http.post("${snap.baseUrl}/v1/images/generations",
            [Authorization: "Bearer ${snap.apiKey}", "Content-Type": "application/json"],
            [model: p.model, prompt: p.input ?: p.params?.prompt ?: "", background: true])
    if (!resp.ok) {
        throw new RuntimeException("上游创建失败: HTTP ${resp.status}")
    }
    return [upstreamTaskId: resp.body.id, progressHint: 0]
}

def poll(Map ctx) {
    def snap = ctx.routeSnapshot
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

def resultMapping(Map ctx) {
    def raw = ctx.raw
    return [resources: raw.urls ?: [],
            usage: [outputType: "png", pieces: (raw.urls ?: []).size(), model: ctx.payload.model]]
}
