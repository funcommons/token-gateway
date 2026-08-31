# framework4j-sensitive

> 字段脱敏 + AES-256-GCM 字段级加密 TypeHandler（mc-java-security 铁律 9）

## 简介

身份证 / 银行卡 / 手机号 / 邮箱 等敏感数据：
- **对外脱敏**：Jackson 序列化时自动脱敏（API 响应不泄漏）
- **对内加密**：MyBatis TypeHandler 写 DB 时加密，读 DB 时解密（DB 泄漏不泄漏）

## 双层防护

```
应用层（脱敏）
  @Sensitive(PHONE) → Jackson 序列化时 "138****1234"
                              ↓
存储层（加密）
  EncryptedFieldTypeHandler → DB 存储 AES-256-GCM 密文
```

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-sensitive</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置（仅加密需要密钥）

```yaml
framework4j:
  sensitive:
    enabled: true
    encryption-key: ${KMS_AES_KEY}    # 生产环境从 KMS 取，禁硬编码
    iv-length: 12
    tag-bits: 128
```

### 3. 对外脱敏（Jackson 序列化）

```java
public class UserVO {
    private String id;

    @Sensitive(SensitiveRule.PHONE)
    private String phone;

    @Sensitive(SensitiveRule.ID_CARD)
    private String idCard;

    @Sensitive(SensitiveRule.EMAIL)
    private String email;

    @Sensitive(SensitiveRule.NAME)
    private String realName;
}
```

序列化结果：
```json
{
  "id": "u-1",
  "phone": "138****5678",
  "id_card": "110101********1234",
  "email": "a***@example.com",
  "real_name": "张*"
}
```

### 4. 对内加密（MyBatis TypeHandler）

```java
@TableName(value = "user_info", autoResultMap = true)
public class UserDo {
    private String id;

    @TableField(typeHandler = EncryptedFieldTypeHandler.class)
    private String idCard;       // DB 存密文，Java 层是明文

    @TableField(typeHandler = EncryptedFieldTypeHandler.class)
    private String phone;
}
```

DB 实际存储：
```sql
SELECT id, id_card, phone FROM user_info WHERE id = 'u-1';
-- id   | id_card                         | phone
-- u-1  | HTZ9pP+3kB2N...base64密文...    | xrT4lQ==...base64密文...
```

## 脱敏规则（6 种）

| 规则 | 输入示例 | 输出 |
|---|---|---|
| `PHONE` | 13812345678 | 138****5678 |
| `ID_CARD` | 110101199001011234 | 110101********1234 |
| `BANK_CARD` | 6228123456785678 | 6228******5678 |
| `EMAIL` | alice@example.com | a***@example.com |
| `NAME` | 张三丰 | 张** |
| `ADDRESS` | 北京市朝阳区望京街1号 | 北京市朝阳区*** |
| `ALL` | 任意 | ****** |

## AES-256-GCM 加密特性

- **算法**：AES/GCM/NoPadding（认证加密，防篡改）
- **密钥派生**：SHA-256(任意字符串) → 32 字节 AES 密钥
- **IV 随机**：每次加密 12 字节随机 IV（同明文每次密文不同）
- **GCM Tag**：128 位（解密时校验，错误密钥/篡改 → 抛异常）
- **存储格式**：`Base64(IV || ciphertext || tag)`

## 关键设计

### Jackson 序列化器

`SensitiveJsonSerializer implements ContextualSerializer` 在字段初始化时拿到 `@Sensitive` 注解，序列化时调用 `SensitiveUtils.desensitize()`。

### TypeHandler 双向

```java
public class EncryptedFieldTypeHandler extends BaseTypeHandler<String> {
    @Override
    public void setNonNullParameter(...) {
        ps.setString(i, AesGcmCryptoUtil.encrypt(keyBytes, parameter));  // 写加密
    }

    @Override
    public String getNullableResult(...) {
        return value != null ? AesGcmCryptoUtil.decrypt(keyBytes, value) : null;  // 读解密
    }
}
```

## 自动装配

- `SensitiveAutoConfiguration` 注册 Jackson 模块（@Sensitive 字段脱敏）
- 通过 `framework4j.sensitive.enabled=false` 关闭
- **`EncryptedFieldTypeHandler` 不再作为 Spring Bean 注册**（v2.2 修复）
  - 原因：MyBatis-Plus 会通过 Spring 容器扫描 `BaseTypeHandler<T>` Bean 并按类型擦除注册为全局 handler，导致所有未指定 typeHandler 的 String 字段（JSONB / email / nickname 等）被默认加密
  - 正确用法：在实体字段显式 `@TableField(typeHandler = EncryptedFieldTypeHandler.class)`，由 MyBatis-Plus mapper 字段级 typeHandler 精确应用
  - 若需全局生效，可在自己项目的 `MybatisPlusConfig` 中显式注册 handler 实例（绕开 Spring 容器）

## 安全注意

1. **密钥管理**：`encryption-key` 生产环境必须从 KMS 取，禁硬编码入仓
2. **KMS 集成**：阿里云/腾讯云 KMS 可扩展 `AesGcmCryptoUtil.deriveKey()` 接入
3. **审计**：脱敏前的明文不应入日志，建议配合 `framework4j-audit` 只记录脱敏后值

## 相关文档

- `Java开发准则.md` 安全 P0 必查 6 项
- mc-java-security 铁律 9 敏感数据加密存储

## v2.1 功能增强

### 自定义脱敏规则

```java
public class OrderVO {
    // 保留前 2 + 后 2，中间 4 个星号 → Ab********cd
    @Sensitive(value = SensitiveRule.CUSTOM, pattern = "2,2,4")
    private String orderNo;

    // 保留前 3 + 后 4，中间 6 个星号
    @Sensitive(value = SensitiveRule.CUSTOM, pattern = "3,4,6")
    private String serialNumber;
}
```

Pattern 格式：`前保留,后保留,星号数`

### safeDecrypt 行为说明

字段解密失败时（密钥轮换 / 数据篡改 / GCM Tag 校验失败）：
- 返回 `null`（**不返回明文**，遵循 mc-java-security 铁律）
- 记录 `WARN` 日志：`[Sensitive] 字段解密失败（密钥轮换/数据篡改）`
- 不冒泡 RuntimeException（不影响 MyBatis ORM 契约）

业务方应检查 null 并决定降级策略（如返回脱敏默认值）。
