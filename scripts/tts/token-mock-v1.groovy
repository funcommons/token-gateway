// task_type: tts — token-mock 上游适配 (speech synthesis; ML-3-TTS / demo-tts-1, 2 元/轮)
// 上游契约 (token-mock 1.5.0 AudioImageHandler.handleAudioSpeech, 同步单跳, 无作业 ID):
//   POST {base}/v1/audio/speech   body {model, input, voice, response_format}
//        → 200 audio/mpeg 二进制 (mock=427B 静音 mp3, 头 3 字节 ID3 魔数 49 44 33)
// ⚠ 二进制经 ScriptHttpClient String 通道有损转码 (bodyToMono UTF-8 解码; 无二进制 POST binding,
//   实测第 6 字节起=ef bf bd replacement char) — 本脚本按 String.getBytes("UTF-8") 重组字节后
//   base64 内联成 data: URI 作资源 URL: 网关 ResourceProxyController 原生解码 data:<mediatype>;base64
//   直发 (Content-Type=URI 自带 audio/mpeg; ID3 头 3 字节 ASCII 存活 → ML-3-TTS 判据「只断 ID3 魔数,
//   不断 0xFF 0xFB 帧同步」即由此定性; 真渠道恒回 b64_json 时无损, 已知留裁项)
// 同步上游表达: create 零调用 (无作业 ID, 引擎 null 容忍), poll 首跳=真实合成调用 → SUCCEEDED;
//   !ok 判 FAILED (同 audio 脚本语义: 同步 API 无作业可重查, 调用即工作本体)
// usage: voice=合成音色留痕 (params.voice 回显) / outputType=params.format (网关 ResourceUrlConverter
//   据此发 .mp3 代理后缀; 后缀仅可读性, ≤5 位字母数字)
// 重启重领: 整体重跑 create+poll → 重打一次同步上游 (mock 零损; 真渠道=上游侧重复计费一次, 平台账单不受影响)
// 故障演练: 无 payload 造败位 (mock 合成恒 200) — FAILED/退款语义由 video 负例代表 (ML-3-TTS 注释先例)
// Binding 注入: ctx(Map), http(ScriptHttpClient), log, json

def create(Map ctx) {
    // 同步上游无作业 ID (不设 upstreamTaskId); 真实调用在 poll 首跳
    return [progressHint: 0]
}

def poll(Map ctx) {
    def snap = ctx.routeSnapshot
    def p = ctx.payload
    def voice = p.params?.voice ?: "alloy"
    def format = p.params?.format ?: "mp3"
    def resp = http.post("${snap.baseUrl}/v1/audio/speech",
            [Authorization: "Bearer ${snap.apiKey}", "Content-Type": "application/json"],
            [model: p.model, input: p.input ?: "", voice: voice, response_format: format])
    if (!resp.ok) {
        return [state: "FAILED", raw: [httpStatus: resp.status],
                error: "上游合成失败: HTTP ${resp.status}"]
    }
    // 二进制 mp3 → String (有损) → UTF-8 字节 → base64 data: URI
    // (引擎契约: poll 的 raw 经 ctx.raw 流转 resultMapping; data URI KB 级, 入 lotask 终态条目无压力)
    // ⚠ 显式 .toString(): GString 直接入结果会被 fastjson2 按 bean 序列化成 {values:[...]} 对象
    //   (非字符串), 资源 URL 即废 —— 钩子返回 Map 里所有拼接值一律 String 化
    def mp3Base64 = java.util.Base64.getEncoder().encodeToString(
            String.valueOf(resp.body).getBytes("UTF-8"))
    def mediaType = format == "mp3" ? "audio/mpeg" : "audio/${format}".toString()
    def dataUri = "data:${mediaType};base64,${mp3Base64}".toString()
    return [state: "SUCCEEDED", raw: [dataUri: dataUri,
            voice: voice, format: format, byteLength: dataUri.length()]]
}

def resultMapping(Map ctx) {
    def raw = ctx.raw
    return [resources: raw.dataUri ? [raw.dataUri] : [],
            usage: [voice: raw.voice, outputType: raw.format, bytes: raw.byteLength]]
}
