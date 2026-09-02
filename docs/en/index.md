---
layout: home

hero:
  name: token-gateway
  text: Universal Model Capability Gateway
  tagline: LLM sync face (protocol normalization · rate limiting · idempotency · moderation · billing saga) + task face (async tasks · full pre-charge · terminal refund · resource proxy) · capability-face SPI · configuration-as-onboarding
  actions:
    - theme: brand
      text: 5-Minute Quickstart
      link: /en/user/quickstart
    - theme: alt
      text: Design Proposal
      link: /en/dev/design
    - theme: alt
      text: GitHub
      link: https://github.com/funcommons/token-gateway

features:
  - title: Protocol Normalization
    details: OpenAI-shape in → OpenAI-shape out; Anthropic-shape in → Anthropic-shape out. Callers never see upstream differences; native SSE passthrough.
  - title: Billing Saga
    details: Pre-consume before forwarding (insufficient balance → 10617), settle on actual usage, automatic full refund on total upstream failure; streaming tail-frame usage settlement.
  - title: Configuration-as-Onboarding
    details: Seven capability faces (route / token-validate / billing / moderation / access-log / audit / model-catalog), each with its own URL — separately deployable or all-in-one monolith. Edit yml and restart.
  - title: Pluggable Third Parties
    details: Adapter matrix mmagix / tokenhub / tokengo / openapi. Third parties implement the capability-face HTTP contract in any language — no Java required.
  - title: Task Face (4 Modalities)
    details: videos/images/audios/tts async tasks — task_no on create, poll/notify, resource proxy (exp+sig 24h, upstream URLs never exposed), timeout clock + reconciliation; lotask4j-hosted with Groovy-script upstream onboarding.
  - title: Production-grade Cross-cutting
    details: Redis sliding-window rate limiting (4 response headers) · Idempotency-Key rejection dedup · moderation fail-open · X-Trace-Id end-to-end.
  - title: Inter-backend Gradual Rollout
    details: THMP contract-face shadow comparison + deterministic bucketed cutover + instant rollback ([THMP-SHADOW] markers, shadow-report.py reusable).
---
