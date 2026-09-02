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

## What's next

| You want to | Go to |
|---|---|
| See all LLM endpoints and parameters | [LLM Face Guide](./llm-guide.md) |
| Integrate the task face (notify / resource proxy details) | [Task Face Guide](./task-guide.md) |
| Understand error codes / rate limits / idempotency | [Conventions](./conventions.md) |
| Something's broken | [FAQ](./faq.md) |
