# framework4j SDK Skill

Base directory for this skill: `skills/fwk4j-sdk`

## 用户速查

| 你想 | 入口 |
|---|---|
| 引入依赖 | SKILL.md §1 |
| 接口需要登录 | SKILL.md §2 AccessToken |
| 开放 API 签名 | SKILL.md §3 Signature |
| 接口限流 | SKILL.md §4 RateLimit |
| 缓存 | SKILL.md §5 Cache |
| 审计日志 | SKILL.md §6 Audit |
| 手机号/身份证脱敏 | SKILL.md §7 Sensitive |
| DB 字段加密 | SKILL.md §8 Encryption |
| 防重复提交 | SKILL.md §9 Idempotency |
| 分布式 ID | SKILL.md §10 Snowflake |
| 多 Redis | SKILL.md §11 @RedisOn |
| 多 DataSource | SKILL.md §12 @DataSourceOn |
| 统一响应格式 | SKILL.md §13 ApiResponse |

## 触发方式

用户在 Claude Code 中提到以下关键词时自动激活：
- `framework4j` / `fwk4j`
- `@RequiresToken` / `@RequiresSignature` / `@RateLimit` / `@CacheableGet` / `@Auditable` / `@Sensitive`
- `AccessToken` / `RefreshToken` / `HMAC 签名` / `限流` / `脱敏` / `AES 加密`
- `Snowflake` / `OpenID` / `@RedisOn` / `@DataSourceOn` / `Idempotency-Key`

## 依赖信息

- **groupId**: `com.github.funcommons.framework4j`
- **version**: `v1.0.0`（JitPack）
- **仓库**: `https://jitpack.io`
- **GitHub**: `https://github.com/funcommons/framework4j`
