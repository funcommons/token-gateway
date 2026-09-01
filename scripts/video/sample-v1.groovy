// task_type: video — 样例脚本 (《05》§9.2 三钩子契约的参考实现; 真实上游脚本按此形状写)
// Binding 注入: ctx(Map), http(ScriptHttpClient), log, json
// ctx: payload(Map, 含 model/params/input/notifyUrl), routeSnapshot(Map, 已解密), upstreamTaskId, raw, progress

def create(Map ctx) {
    def snap = ctx.routeSnapshot
    def resp = http.post("${snap.baseUrl}/v1/tasks",
            [Authorization: "Bearer ${snap.apiKey}"],
            [model: ctx.payload.model, params: ctx.payload.params, input: ctx.payload.input])
    if (!resp.ok) {
        throw new RuntimeException("上游创建失败: HTTP ${resp.status}")
    }
    return [upstreamTaskId: resp.body.data.taskId, progressHint: 0]
}

def poll(Map ctx) {
    def snap = ctx.routeSnapshot
    def resp = http.get("${snap.baseUrl}/v1/tasks/${ctx.upstreamTaskId}",
            [Authorization: "Bearer ${snap.apiKey}"])
    if (!resp.ok) {
        return [state: "RUNNING", raw: [httpStatus: resp.status]]  // 查询异常不判死, 等下一轮
    }
    def data = resp.body.data
    def state = [succeeded: "SUCCEEDED", failed: "FAILED"].get(data.status, "RUNNING")
    return [state: state, raw: data,
            progressHint: data.progress ?: 0,
            error: data.error]
}

def resultMapping(Map ctx) {
    def raw = ctx.raw
    return [resources: raw.outputs?.collect { it.url } ?: [],
            usage: [seconds: raw.usageSeconds ?: 0, resolution: ctx.payload.params?.resolution]]
}
