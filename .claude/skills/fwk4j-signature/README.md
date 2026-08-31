# framework4j-signature

> HMAC-SHA256 接口签名防重放：`X-Access-Key` + `X-Timestamp` + `X-Nonce` + `X-Signature`

## 简介

填补 mc-java-security 铁律 6 强制要求。适用于开放 API / 三方对接场景（AWS Signature、阿里云 API 网关、微信支付均采用此模式）。

## 签名算法

```
签名串 = HTTP_METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + BODY_MD5
签名值 = BASE64(HMAC_SHA256(secret, 签名串))
```

## 服务端五步校验

| 步骤 | 校验 | 失败错误码 |
|---|---|---|
| 1 | 4 个 Header 齐全 | 10101 PARAM_MISSING |
| 2 | timestamp ±5min | 10102 PARAM_FORMAT_ERROR |
| 3 | nonce 一次性（Redis SETNX 10min） | 10302 SIGNATURE_ERROR（重放） |
| 4 | 查 secret（AccessKey → Secret） | 10200 UNAUTHORIZED |
| 5 | HMAC 常量时间比较（`MessageDigest.isEqual`） | 10302 SIGNATURE_ERROR（不匹配） |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-signature</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置

```yaml
framework4j:
  signature:
    enabled: true
    timestamp-tolerance-ms: 300000       # ±5min
    nonce-ttl-seconds: 600                # 10min
    path-patterns: ["/v1/api/**"]
    exclude-path-patterns: ["/v1/auth/**"]
    nonce-key-prefix: "signature:nonce"
    redis-name: "default"
```

### 3. 注册 SecretProvider

默认 `InMemorySecretProvider`（开发/测试用）。生产环境应实现：

```java
@Component
public class DbSecretProvider implements SecretProvider {
    @Autowired
    private AppSecretMapper mapper;

    @Override
    public String getSecret(String accessKey) {
        return mapper.findByAccessKey(accessKey);
    }
}
```

### 4. 客户端调用示例

```python
import hmac, hashlib, base64, time, uuid

def sign_request(method, path, body, secret):
    timestamp = str(int(time.time() * 1000))
    nonce = str(uuid.uuid4())
    body_md5 = hashlib.md5(body or b'').hexdigest()
    string_to_sign = f"{method}\n{path}\n{timestamp}\n{nonce}\n{body_md5}"
    signature = base64.b64encode(
        hmac.new(secret.encode(), string_to_sign.encode(), hashlib.sha256).digest()
    ).decode()

    return {
        "X-Access-Key": "app-001",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
    }
```

## 关键设计

### ThreadLocal<Mac>（遵循 §5.1）

`Mac` 非线程安全 + `getInstance` 有 JCA 查找开销，用 `ThreadLocal` 缓存 + `reset()` 防残留：

```java
private static final ThreadLocal<Mac> MAC_CACHE = ThreadLocal.withInitial(() -> {
    try { return Mac.getInstance("HmacSHA256"); }
    catch (Exception e) { throw new IllegalStateException(e); }
});

public static byte[] hmacSha256(byte[] key, byte[] data) {
    Mac mac = MAC_CACHE.get();
    try {
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    } finally {
        mac.reset();
    }
}
```

### Lua 原子化 nonce 防重放（遵循 §3.1）

```lua
-- GET + SET NX EX 原子化，消除 TOCTOU 竞态
if redis.call('GET', KEYS[1]) then return 0 end
redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1])
return 1
```

### 常量时间比较（防 timing attack）

```java
// 禁用 String.equals（时序攻击）
MessageDigest.isEqual(clientSig.getBytes(), serverSig.getBytes())
```

## 自动装配

- `SignatureAutoConfiguration` 注册 `SignatureService` + `SignatureInterceptor` + `SecretProvider`
- 通过 `framework4j.signature.enabled=false` 关闭

## Header 名称可定制

```yaml
framework4j:
  signature:
    header-names:
      access-key: "X-Access-Key"
      timestamp: "X-Timestamp"
      nonce: "X-Nonce"
      signature: "X-Signature"
```

## 相关文档

- `Java开发准则.md` §16 HMAC 签名规范
- `mc-java-security` 铁律 6 接口签名防重放
