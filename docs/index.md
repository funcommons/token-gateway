---
layout: home

hero:
  name: token-gateway
  text: 通用模型能力网关
  tagline: LLM 同步面（协议归一 · 限流 · 幂等 · 审核 · 计费 saga）+ 任务四模态面（规划中 M2.5）· 能力面 SPI · 配置即接入
  actions:
    - theme: brand
      text: LLM 面快速接入
      link: /用户文档/01_LLM面接入手册
    - theme: alt
      text: 设计方案
      link: /开发文档/01_设计方案
    - theme: alt
      text: GitHub
      link: https://github.com/funcommons/token-gateway

features:
  - title: 协议归一
    details: OpenAI 形状入 → OpenAI 形状出，Anthropic 形状入 → Anthropic 形状出；调用方不感知上游差异，SSE 原生透传
  - title: 计费 saga
    details: 转发前预扣（余额不足 10617）→ 按实际 usage 结算 → 全失败自动全额退款；流式末帧 usage 真实结算
  - title: 配置即接入
    details: 七类能力面（路由/凭证/计费/审核/日志/审计/模型目录）各自配地址，可分离部署也可同指单体，改 yml 即生效
  - title: 三方可插拔
    details: 适配器矩阵 mmagix / tokenhub / tokengo / openapi；三方实现能力面 HTTP 契约即接入，不限语言
  - title: 生产级横切
    details: Redis 滑动窗口限流（四响应头）· Idempotency-Key 拒绝式去重 · 审核 fail-open · X-Trace-Id 全链路
  - title: 后端间灰度
    details: THMP 契约面影子双跑比对 + 确定性分桶切流 + 秒级回滚（[THMP-SHADOW] 埋点 + shadow-report.py 复用）
---
