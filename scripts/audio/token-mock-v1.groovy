// task_type: audio — token-mock 上游适配 (speech-to-text 转写; ML-3-AUDIO / demo-audio-1, 5 元/轮)
// 上游契约 (token-mock 1.5.0 AudioImageHandler.handleAudioTranscription, 同步单跳, 无作业 ID):
//   POST {base}/v1/audio/transcriptions   multipart: file(音频字节, 脚本自备 64B 占位帧——客户端
//        submit 为纯 JSON 无真音频, mock 回显 file 字节数) + model + language(可选, 缺省 english)
//        → 200 {language, duration:1.0, text:"[mock] 识别到一段 N 字节的音频,语言 english"}
// 转写产物=文本: result.resources 恒空数组 (ML-3-AUDIO 判据=usage 文本断言, 非资源取回):
//   usage.text 含 "[mock]" 回显子串 / usage.language 在位 / usage.outputType="txt"
// 同步上游表达 (引擎契约, WorkerLoop.runTask: create 只认 upstreamTaskId, 终态恒由 poll 判定):
//   create 零上游调用只回占位 (无作业 ID 可设, 引擎 null 容忍), poll 首跳即真实转写调用 → SUCCEEDED;
//   !ok 判 FAILED —— 同步 API 无作业可重查, 每次调用即工作本体 (与 video 异步作业「查询异常不判死」相反)
// 重启重领: 整体重跑 create+poll → 重打一次同步上游 (mock 零损; 真渠道=上游侧重复计费一次, 平台账单不受影响)
// 故障演练: 无 payload 造败位 (mock 转写恒 200) — FAILED/退款语义由 video 负例代表 (ML-3-AUDIO 注释先例)
// Binding 注入: ctx(Map), http(ScriptHttpClient), log, json

def create(Map ctx) {
    // 同步上游无作业 ID (不设 upstreamTaskId); 真实调用在 poll 首跳
    return [progressHint: 0]
}

def poll(Map ctx) {
    def snap = ctx.routeSnapshot
    def p = ctx.payload
    // 64B 占位音频帧 (mock 回显字节数 N=64, e2e 判据只断 text 含 [mock] 子串)
    def frame = ("RIFFxxxxWAVEfmt " + "x" * 48).getBytes("UTF-8")
    def parts = [[name: "file", bytes: frame, filename: "audio.wav", contentType: "audio/wav"],
                 [name: "model", value: String.valueOf(p.model)]]
    if (p.params?.language) {
        parts << [name: "language", value: String.valueOf(p.params.language)]
    }
    def resp = http.postMultipart("${snap.baseUrl}/v1/audio/transcriptions",
            [Authorization: "Bearer ${snap.apiKey}"], parts)
    if (!resp.ok) {
        return [state: "FAILED", raw: [httpStatus: resp.status],
                error: "上游转写失败: HTTP ${resp.status}"]
    }
    return [state: "SUCCEEDED", raw: resp.body]
}

def resultMapping(Map ctx) {
    def raw = ctx.raw
    return [resources: [],
            usage: [text: raw.text, language: raw.language, duration: raw.duration,
                    outputType: "txt", model: ctx.payload.model]]
}
