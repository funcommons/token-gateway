# Quickstart

First call in 5 minutes. Before you start:

- You have a credential (`sk-*` key, issued by the platform operator)
- The gateway is reachable (default `http://localhost:9401`; production address per your operator)

## Step 1: Verify your credential

```bash
curl -s http://<gateway-host>:9401/v1/models \
  -H "Authorization: Bearer <your-credential>"
```

`{"object":"list","data":[...]}` means the credential works; an envelope with `code=10202` means it doesn't.

## Step 2: First chat completion (LLM face)

::: code-group

```python [OpenAI SDK (recommended)]
from openai import OpenAI

client = OpenAI(
    api_key="<your-credential>",
    base_url="http://<gateway-host>:9401/v1",
)
resp = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "Hello"}],
)
print(resp.choices[0].message.content)
```

```bash [curl]
curl -s http://<gateway-host>:9401/v1/chat/completions \
  -H "Authorization: Bearer <your-credential>" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}'
```

```python [Anthropic SDK]
import anthropic

client = anthropic.Anthropic(
    api_key="<your-credential>",
    base_url="http://<gateway-host>:9401",   # SDK appends /v1/messages
)
msg = client.messages.create(
    model="claude-sonnet-4-5", max_tokens=1024,
    messages=[{"role": "user", "content": "Hello"}],
)
print(msg.content[0].text)
```

:::

> A successful response is the **upstream business shape passed through** (no envelope) — see [Conventions §2](./conventions.md) for the success/failure rule.

## Step 3: Create your first async task (task face)

```bash
# ① Create (returns task_no synchronously; full pre-charge at creation,
#    insufficient balance → 10617 and no task is created)
curl -s http://<gateway-host>:9401/v1/videos \
  -H "Authorization: Bearer <your-credential>" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"model":"vid-1.5","params":{"duration":5,"resolution":"720p"},
       "notify_url":"https://you/callback"}'
# → {"task_no":"T20260902...","status":"PENDING","poll_url":"/v1/videos/T20260902..."}

# ② Poll (drive every 3–5s; terminal states are idempotent — repeated polling
#    neither touches upstream nor refunds twice)
curl -s http://<gateway-host>:9401/v1/videos/T20260902... \
  -H "Authorization: Bearer <your-credential>"
# → {"task_no":"...","status":"SUCCEEDED","result":{"resources":["<proxy URL>"],"usage":{...}}}

# ③ Fetch the artifact (proxy URL carries a 24h exp+sig — fetch directly, no credential)
curl -sL "http://<gateway-host>:9401<proxy URL>" -o out.mp4
```

## Embedded Mode (starter)

Skip the standalone fat-jar: reference the starter inside your own **WebFlux** application and the gateway endpoints assemble into the host process (published via JitPack, tag = version):

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.funcommons.token-gateway</groupId>
    <artifactId>token-gateway-spring-boot-starter</artifactId>
    <version>v0.2.0</version>
</dependency>
```

Configuration matches the standalone deployment:

```yaml
token-gateway:
  face: llm        # llm | task | all (same grouping semantics; illegal value fails fast at startup)
  enabled: true    # false turns it off (on by default)
  worker:
    enabled: false # true also assembles the task-executing Worker into the host (effective with face=task|all) —
                   # a full embedded task-face loop with no separate Worker process; off by default
gateway:
  backend:
    url: http://localhost:9400
```

Constraints and limitations:

- **The host must be on the WebFlux stack** (`spring-boot-starter-webflux`): the gateway pipeline is driven by WebFilters — in an MVC (servlet) host the starter stays silently inactive
- Rate limiting/idempotency depend on Redis: the host's `spring.data.redis.*` connection config is reused as-is
- `face=task` requires a resource cache volume + a reachable lotask4j platform (see the [Task Face Guide](./task-guide.md)); set `worker.enabled=true` to pull and execute tasks inside the host (Worker script dir / concurrency follow the `worker.*` config)
- Gateway-internal beans are registered via component scanning and cannot be overridden with `@Bean` in the host; open an issue for customization needs
- Operational endpoints (health checks) require the host to include `spring-boot-starter-actuator`

## What's next

| You want to | Go to |
|---|---|
| See all LLM endpoints and parameters | [LLM Face Guide](./llm-guide.md) |
| Integrate the task face (notify / resource proxy details) | [Task Face Guide](./task-guide.md) |
| Understand error codes / rate limits / idempotency | [Conventions](./conventions.md) |
| Something's broken | [FAQ](./faq.md) |
