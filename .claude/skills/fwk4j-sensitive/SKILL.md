---
name: fwk4j-sensitive
description: framework4j 字段脱敏（6 规则 + CUSTOM pattern）+ AES-256-GCM 字段加密 TypeHandler（ThreadLocal Cipher + GCM Tag 校验 + 密钥缺失 ERROR）。触发词：@Sensitive、脱敏、SensitiveRule、PHONE、ID_CARD、BANK_CARD、EMAIL、AES 加密、EncryptedFieldTypeHandler、字段加密、CUSTOM pattern、safeDecrypt。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-security
  tags: [desensitize, encrypt, aes-gcm, jackson]
  language: zh-CN
  artifactId: framework4j-sensitive
  config-prefix: framework4j.sensitive
  examples:
    - "手机号脱敏"                    # → @Sensitive(PHONE) → 138****5678
    - "身份证脱敏"                    # → @Sensitive(ID_CARD) → 110101********1234
    - "自定义脱敏规则"                # → @Sensitive(CUSTOM, pattern="2,2,4")
    - "DB 字段加密存储"               # → EncryptedFieldTypeHandler
    - "encryption-key 没配怎么办"     # → ERROR 日志，TypeHandler 不注册
---

# framework4j-sensitive 脱敏与加密

## 脱敏（Jackson 自动）

```java
public class UserVO {
    @Sensitive(SensitiveRule.PHONE)    private String phone;    // 138****5678
    @Sensitive(SensitiveRule.ID_CARD)  private String idCard;   // 110101********1234
    @Sensitive(SensitiveRule.BANK_CARD) private String bankCard; // 6228******5678
    @Sensitive(SensitiveRule.EMAIL)    private String email;    // a***@example.com
    @Sensitive(SensitiveRule.NAME)     private String name;     // 张**
    @Sensitive(SensitiveRule.ADDRESS)  private String address;  // 北京市朝阳区***
    @Sensitive(value = SensitiveRule.CUSTOM, pattern = "2,2,4")
    private String orderNo;                                     // AB****GH
}
```

## 加密（MyBatis TypeHandler）

```java
@TableName(autoResultMap = true)
public class UserDO {
    @TableField(typeHandler = EncryptedFieldTypeHandler.class)
    private String idCard;  // DB 存 AES-256-GCM 密文，Java 层明文
}
```

- 每次加密随机 IV（同明文不同密文）
- GCM Tag 校验（错误密钥/篡改 → null + warn，不返回明文）
- `ThreadLocal<Cipher>` 复用

## 配置

```yaml
framework4j:
  sensitive:
    enabled: true
    encryption-key: ${AES_KEY}  # 生产从 KMS 取，缺失则 ERROR 日志
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-sensitive</artifactId>
    <version>v1.1.1</version>
</dependency>
```
