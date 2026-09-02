// task_type: video — token-mock 上游适配 (联调/冒烟默认上游, 《05》§9.2 三钩子)
// 上游契约 (token-mock VideoJobHandler):
//   POST {base}/v1/videos           → {id, status:"queued", model, prompt, created_at, url}
//   GET  {base}/v1/videos/{id}      → 状态推进 queued(0-30s) → in_progress(30-60s) → completed
//                                     completed 时带 url(内容下载), duration_seconds, resolution
//   GET  {base}/v1/videos/{id}/content → video/mp4 二进制
// 故障演练: token-mock 管理面可 forceStatus(jobId, "failed") / failureRate 注入
// Binding 注入: ctx(Map), http(ScriptHttpClient), log, json

def create(Map ctx) {
    def snap = ctx.routeSnapshot
    def p = ctx.payload
    def resp = http.post("${snap.baseUrl}/v1/videos",
            [Authorization: "Bearer ${snap.apiKey}", "Content-Type": "application/json"],
            [model: p.model, prompt: p.input ?: p.params?.prompt ?: ""])
    if (!resp.ok) {
        throw new RuntimeException("上游创建失败: HTTP ${resp.status}")
    }
    return [upstreamTaskId: resp.body.id, progressHint: 0]
}

def poll(Map ctx) {
    def snap = ctx.routeSnapshot
    def resp = http.get("${snap.baseUrl}/v1/videos/${ctx.upstreamTaskId}",
            [Authorization: "Bearer ${snap.apiKey}"])
    if (!resp.ok) {
        return [state: "RUNNING", raw: [httpStatus: resp.status]]  // 查询异常不判死, 等下一轮
    }
    def data = resp.body
    def state = [completed: "SUCCEEDED", failed: "FAILED"].get(data.status, "RUNNING")
    return [state: state, raw: data,
            progressHint: data.status == "in_progress" ? 50 : 0,
            error: data.error?.message]
}

def resultMapping(Map ctx) {
    def raw = ctx.raw
    return [resources: raw.url ? [raw.url] : [],
            usage: [seconds: raw.duration_seconds ?: 0, resolution: raw.resolution]]
}
