---
name: fwk4j-signature
description: framework4j HMAC-SHA256 接口签名防重放（X-Access-Key + X-Timestamp + X-Nonce + X-Signature + Redis nonce 一次性 + MessageDigest.isEqual 常量时间比较）。触发词：@RequiresSignature、HMAC 签名、接口签名、防重放、X-Signature、nonce、timestamp、SecretProvider、开放 API、三方对接。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-security
  tags: [hmac, signature, anti-replay, open-api]
  language: zh-CN
  artifactId: framework4j-signature
  config-prefix: framework4j.signature
  examples:
    - "开放 API 怎么签名防重放"     # → @RequiresSignature
    - "三方对接签名怎么验"          # → SecretProvider
    - "X-Signature 怎么算"         # → HMAC_SHA256(METHOD\nPATH\nTS\nNONCE\nBODY_MD5)
---

# framework4j-signature 接口签名

## 签名算法

```
签名串 = METHOD\nPATH\nTIMESTAMP\nNONCE\nBODY_MD5
签名值 = BASE64(HMAC_SHA256(secret, 签名串))
```

## 请求 Header 四件套

| Header | 必填 | 说明 |
|---|---|---|
| `X-Access-Key` | 是 | 应用标识 |
| `X-Timestamp` | 是 | Unix 毫秒（±5min） |
| `X-Nonce` | 是 | UUID v4（10min 一次性） |
| `X-Signature` | 是 | BASE64 签名值 |

## 保护接口

```java
@RequiresSignature
@PostMapping("/v1/api/orders")
public ApiResponse<?> createOrder(@RequestBody OrderRequest req) { ... }
```

## SecretProvider（自定义密钥查询）

```java
@Component
public class DbSecretProvider implements SecretProvider {
    @Override
    public String getSecret(String accessKey) {
        return appMapper.findSecretByAccessKey(accessKey);
    }
}
```

## 配置

```yaml
framework4j:
  signature:
    enabled: true
    path-patterns: ["/v1/api/**"]
    timestamp-tolerance-ms: 300000
    nonce-ttl-seconds: 600
    redis-name: default
```

## Python 客户端签名示例

```python
import hmac, hashlib, base64, time, uuid
def sign(method, path, body, secret):
    ts = str(int(time.time()*1000))
    nonce = str(uuid.uuid4())
    body_md5 = hashlib.md5(body or b'').hexdigest()
    sts = f"{method}\n{path}\n{ts}\n{nonce}\n{body_md5}"
    sig = base64.b64encode(hmac.new(secret.encode(), sts.encode(), hashlib.sha256).digest()).decode()
    return {"X-Access-Key": "app-1", "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": sig}
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-signature</artifactId>
    <version>v1.1.1</version>
</dependency>
```
