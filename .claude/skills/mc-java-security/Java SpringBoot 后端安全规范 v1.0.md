# Java SpringBoot 后端安全规范 v1.0

> 版本：v1.0
> 修订日期：2026-06-24
> 配套：`./SKILL.md`（精简入口，自动激活）；本文件为详细规则文档
> 关联：mc-java-spec v1.2 §7（通用安全）、mc-api-spec v1.6 §7（错误码）、mc-database-spec（审计日志表）

---

## 1. 概述

### 1.1 适用范围

Java 17 + Spring Boot 3.2 + Spring Security 6 后端的所有安全相关代码：

- 认证（Authentication）：JWT / OAuth2 / Session
- 授权（Authorization）：RBAC / ABAC / 数据权限
- 密码安全：强度 / 哈希 / 锁定 / 重置
- 接口安全：签名 / 防重放 / 限流
- 文件上传安全
- 敏感数据加密
- 审计日志
- 防常见攻击：SQL 注入 / XSS / CSRF / SSRF

### 1.2 核心原则

1. **纵深防御**：每层都校验，不依赖单点
2. **最小权限**：默认拒绝，按需授权
3. **服务端强制**：前端所有控制都可绕过，后端必须独立校验
4. **不可篡改审计**：append-only + hash chain
5. **零信任**：不信任任何外部输入

---

## 2. JWT 认证

### 2.1 密钥管理

**强制**：

- 密钥长度 ≥ 256 位（HS256）
- 生产环境推荐 RS256 / ES256（非对称）
- 密钥**必须**环境变量注入，禁硬编码
- 推荐密钥管理：HashiCorp Vault / K8s Secret / 云 KMS
- JWKS 轮换：每 90 天轮换签名密钥

```yaml
# application.yml
app:
  security:
    jwt:
      secret: ${JWT_SECRET}                    # 必须环境变量
      access-token-ttl-seconds: 7200           # 2h
      refresh-token-ttl-seconds: 2592000       # 30d
      issuer: https://auth.example.com
```

### 2.2 Token 结构

**必填 Claim**：

| Claim | 含义 | 示例 |
|---|---|---|
| `sub` | 用户 ID（String） | `"892310293123123"` |
| `iss` | 签发方 | `"https://auth.example.com"` |
| `aud` | 接收方（校验时必须匹配） | `"order-service"` |
| `exp` | 过期时间戳（秒） | `1718667600` |
| `iat` | 签发时间戳（秒） | `1718660400` |
| `jti` | 唯一 ID（撤销 / 防重放） | `"a1b2c3d4"` |
| `type` | token 类型 | `"access"` / `"refresh"` |

**自定义 Claim**：

- `roles`：角色列表（用于 RBAC）
- `perms`：权限点列表（用于细粒度授权）
- `dept_id`：部门 ID（用于数据权限）

### 2.3 签发

```java
@Service
@RequiredArgsConstructor
public class JwtService {

    private final SecurityProperties props;
    private SecretKey secretKey;

    @PostConstruct
    void init() {
        secretKey = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes());
    }

    public TokenPair issue(User user) {
        String accessJti = UUID.randomUUID().toString();
        String access = Jwts.builder()
            .subject(user.getId())
            .claim("roles", user.getRoles())
            .claim("perms", user.getPermissions())
            .claim("type", "access")
            .issuedAt(new Date())
            .expiration(Date.from(Instant.now().plusSeconds(props.getJwt().getAccessTokenTtlSeconds())))
            .issuer(props.getJwt().getIssuer())
            .id(accessJti)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact();

        String refresh = issueRefresh(user);
        return new TokenPair(access, refresh, accessJti);
    }

    private String issueRefresh(User user) {
        String jti = UUID.randomUUID().toString();
        // refresh_token 一次性：jti 存 Redis，刷新时校验
        redisTemplate.opsForValue().set(
            "refresh:valid:" + jti,
            user.getId(),
            props.getJwt().getRefreshTokenTtlSeconds(),
            TimeUnit.SECONDS
        );
        return Jwts.builder()
            .subject(user.getId())
            .claim("type", "refresh")
            .issuedAt(new Date())
            .expiration(Date.from(Instant.now().plusSeconds(props.getJwt().getRefreshTokenTtlSeconds())))
            .issuer(props.getJwt().getIssuer())
            .id(jti)
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
```

### 2.4 校验（Filter）

```java
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = extractBearer(req);
        if (token == null) {
            chain.doFilter(req, res);
            return;
        }

        try {
            Claims claims = jwtService.parse(token);
            if (!"access".equals(claims.get("type"))) {
                throw new BizException(ErrorCode.TOKEN_INVALID);
            }
            // 黑名单检查（撤销）
            if (Boolean.TRUE.equals(redisTemplate.hasKey("access:revoked:" + claims.getId()))) {
                throw new BizException(ErrorCode.TOKEN_INVALID);
            }
            // 注入 SecurityContext
            User user = toUser(claims);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user, null, user.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (ExpiredJwtException e) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        chain.doFilter(req, res);
    }

    private String extractBearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7) : null;
    }
}
```

### 2.5 刷新机制

```java
@PostMapping("/v1/auth/refresh")
public ApiResponse<TokenPair> refresh(@RequestBody RefreshRequest req) {
    Claims claims;
    try {
        claims = jwtService.parse(req.getRefreshToken());
    } catch (ExpiredJwtException e) {
        throw new BizException(ErrorCode.TOKEN_EXPIRED);
    } catch (JwtException e) {
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }

    if (!"refresh".equals(claims.get("type"))) {
        throw new BizException(ErrorCode.TOKEN_INVALID);
    }

    // 一次性：校验 Redis 中是否仍有效
    String key = "refresh:valid:" + claims.getId();
    String userId = (String) redisTemplate.opsForValue().get(key);
    if (userId == null) {
        throw new BizException(ErrorCode.TOKEN_INVALID);  // 已用过
    }
    redisTemplate.delete(key);  // 立即作废

    User user = userService.getById(userId);
    return ApiResponse.success(jwtService.issue(user), TraceContext.current());
}
```

### 2.6 撤销

access_token 撤销走 Redis 黑名单：

```java
public void revoke(String accessToken) {
    Claims claims = jwtService.parse(accessToken);
    long ttl = claims.getExpiration().getTime() - System.currentTimeMillis();
    if (ttl > 0) {
        redisTemplate.opsForValue().set(
            "access:revoked:" + claims.getId(),
            "1",
            ttl,
            TimeUnit.MILLISECONDS
        );
    }
}
```

---

## 3. RBAC 授权（Spring Security 6）

### 3.1 三层模型

```
用户 (User)
  ↓ 多对多
角色 (Role)
  ↓ 多对多
权限点 (Permission)
  ↓ 对应
资源 (Resource) = URL / 方法签名 / 数据行
```

### 3.2 SecurityFilterChain 配置

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // 启用 @PreAuthorize
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())  // JWT 模式不需要 CSRF
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v1/auth/login", "/v1/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/public/**").permitAll()
                .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/v1/orders/**").hasAnyRole("USER", "ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) ->
                    writeJson(res, ApiResponse.fail(ErrorCode.UNAUTHORIZED, "用户未登录", null, TraceContext.current())))
                .accessDeniedHandler((req, res, ex) ->
                    writeJson(res, ApiResponse.fail(ErrorCode.FORBIDDEN, "无权限访问", null, TraceContext.current()))))
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of("https://example.com"));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(true);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}
```

### 3.3 方法级授权

```java
@PreAuthorize("hasRole('ADMIN')")
public void delete(String userId) { ... }

@PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
@GetMapping("/v1/users/{userId}/orders")
public ApiResponse<...> getOrders(@PathVariable String userId) { ... }

@PostAuthorize("returnObject.ownerId == authentication.principal.id or hasRole('ADMIN')")
public OrderDO getById(String id) { ... }  // 返回后校验所有权
```

### 3.4 权限点命名规范

格式：`<resource>:<action>`

| 权限点 | 含义 |
|---|---|
| `order:read` | 查看订单 |
| `order:create` | 创建订单 |
| `order:delete` | 删除订单 |
| `user:reset-password` | 重置用户密码 |
| `report:export` | 导出报表 |

---

## 4. 数据权限

### 4.1 行级权限（MyBatis Plus 拦截器）

```java
@Intercepts(@Signature(
    type = StatementHandler.class,
    method = "prepare",
    args = {Connection.class, Integer.class}
))
@Component
@RequiredArgsConstructor
public class DataScopeInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        BoundSql boundSql = handler.getBoundSql();
        String sql = boundSql.getSql();

        // 仅对标注了 @DataScope 的方法生效
        MetaObject metaObj = SystemMetaObject.forObject(boundSql);
        Method method = currentMethod();
        if (method == null || !method.isAnnotationPresent(DataScope.class)) {
            return invocation.proceed();
        }

        DataScope scope = method.getAnnotation(DataScope.class);
        User currentUser = SecurityContext.current();
        if (currentUser == null) return invocation.proceed();

        String condition = buildCondition(scope, currentUser);
        if (condition == null) return invocation.proceed();  // 超管不限制

        // 注入 WHERE
        String newSql = injectWhere(sql, condition);
        metaObj.setValue("sql", newSql);
        return invocation.proceed();
    }

    private String buildCondition(DataScope scope, User user) {
        if (user.getRoles().contains("ADMIN")) return null;  // 全量
        StringBuilder sb = new StringBuilder();
        if (scope.userField().length > 0) {
            sb.append(scope.userField()[0]).append(" = ").append(user.getId());
        }
        if (scope.deptField().length > 0 && user.getDeptTree() != null) {
            if (sb.length() > 0) sb.append(" AND ");
            sb.append(scope.deptField()[0]).append(" IN (")
              .append(user.getDeptTree().stream().map(String::valueOf).collect(joining(",")))
              .append(")");
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
```

**注解使用**：

```java
@DataScope(userField = "creator_id")
List<OrderDO> selectByPage(...);

@DataScope(deptField = "dept_id")
List<UserDO> selectAll();
```

### 4.2 列级权限（DTO 过滤）

```java
public UserDetailVO toVO(UserDO user) {
    User u = SecurityContext.current();
    Set<String> roles = u.getRoles();

    UserDetailVO vo = new UserDetailVO(
        user.getId().toString(),
        user.getName(),
        user.getEmail()
    );

    if (roles.contains("ADMIN")) {
        vo.setPhone(user.getPhone());
        vo.setIdCard(user.getIdCard());
        vo.setSalary(user.getSalary());
    } else if (roles.contains("HR")) {
        vo.setPhone(MaskUtils.phone(user.getPhone()));
        vo.setIdCard(MaskUtils.idCard(user.getIdCard()));
    } else {
        vo.setPhone(MaskUtils.phone(user.getPhone()));
        // idCard 不返回
    }
    return vo;
}
```

> ⚠️ **禁信任前端传 `creator_id` / `dept_id` / `user_id`**，必须从 SecurityContext 取当前用户。

---

## 5. 密码安全

### 5.1 强度校验

```java
public class PasswordValidator {
    private static final Set<String> WEAK_PASSWORDS = Set.of(
        "password", "123456", "qwerty", "abc123", "admin"
        // ... top 1000 黑名单
    );

    public static void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new BizException(ErrorCode.PASSWORD_TOO_SHORT);
        }
        if (password.length() > 64) {
            throw new BizException(ErrorCode.PASSWORD_TOO_LONG);
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new BizException(ErrorCode.PASSWORD_NO_UPPER);
        }
        if (!password.matches(".*[a-z].*")) {
            throw new BizException(ErrorCode.PASSWORD_NO_LOWER);
        }
        if (!password.matches(".*\\d.*")) {
            throw new BizException(ErrorCode.PASSWORD_NO_DIGIT);
        }
        if (!password.matches(".*[!@#$%^&*].*")) {
            throw new BizException(ErrorCode.PASSWORD_NO_SPECIAL);
        }
        if (WEAK_PASSWORDS.contains(password.toLowerCase())) {
            throw new BizException(ErrorCode.PASSWORD_TOO_COMMON);
        }
    }
}
```

### 5.2 哈希存储

```java
private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

// 注册
String hashed = ENCODER.encode(rawPassword);

// 登录
if (!ENCODER.matches(rawPassword, user.getPasswordHash())) {
    throw new BizException(ErrorCode.PASSWORD_WRONG);
}
```

**禁用**：MD5 / SHA1 / SHA256（无盐或弱盐）。

### 5.3 登录锁定

```java
public LoginResult login(String username, String rawPassword) {
    String failKey = "login:fail:" + username;
    Long fails = redisTemplate.opsForValue().increment(failKey);
    if (fails == 1) redisTemplate.expire(failKey, 15, TimeUnit.MINUTES);

    if (fails > 5) {
        long ttl = redisTemplate.getExpire(failKey);
        throw new BizException(ErrorCode.ACCOUNT_LOCKED, "账号已锁定，" + ttl + " 秒后重试");
    }

    User user = userMapper.findByUsername(username);
    if (user == null || !ENCODER.matches(rawPassword, user.getPasswordHash())) {
        throw new BizException(ErrorCode.PASSWORD_WRONG);
    }

    redisTemplate.delete(failKey);  // 成功，清空计数
    return new LoginResult(jwtService.issue(user));
}
```

### 5.4 重置流程

| 步骤 | 操作 |
|---|---|
| 1. 发起 | 邮箱 / 手机接收**重置链接**（含一次性 token，TTL 10min） |
| 2. 点击 | token 校验 → 跳转改密页 |
| 3. 改密 | 旧密码（如已登录）+ 新密码；token 用过即删（Redis） |

**禁止**：邮件直接发送新密码。

---

## 6. 接口签名（HMAC 防重放）

### 6.1 签名规则

适用于开放 API / 第三方对接（非用户态 JWT）。

```
signature = HMAC_SHA256(secret, method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body_md5)
```

### 6.2 客户端约定

| Header | 说明 |
|---|---|
| `X-Access-Key` | 客户端 ID |
| `X-Timestamp` | Unix 毫秒 |
| `X-Nonce` | UUID，一次性 |
| `X-Signature` | HMAC-SHA256 hex |

### 6.3 服务端校验

```java
@Component
@RequiredArgsConstructor
public class SignatureInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String accessKey = req.getHeader("X-Access-Key");
        String timestamp = req.getHeader("X-Timestamp");
        String nonce = req.getHeader("X-Nonce");
        String signature = req.getHeader("X-Signature");

        if (anyNull(accessKey, timestamp, nonce, signature)) {
            throw new BizException(ErrorCode.SIGN_MISSING);
        }

        // 1. 时间戳 ±5min
        long ts = Long.parseLong(timestamp);
        if (Math.abs(System.currentTimeMillis() - ts) > 5 * 60 * 1000L) {
            throw new BizException(ErrorCode.SIGN_EXPIRED);
        }

        // 2. nonce 一次性（Redis SETNX）
        if (!redisTemplate.opsForValue().setIfAbsent(
                "sign:nonce:" + nonce, "1", 10, TimeUnit.MINUTES)) {
            throw new BizException(ErrorCode.SIGN_REPLAY);
        }

        // 3. 取密钥
        String secret = clientService.getSecret(accessKey);
        if (secret == null) {
            throw new BizException(ErrorCode.SIGN_INVALID);
        }

        // 4. 计算签名
        String bodyMd5 = md5(readBody(req));
        String toSign = req.getMethod() + "\n" + req.getRequestURI() + "\n"
                      + timestamp + "\n" + nonce + "\n" + bodyMd5;
        String expected = HmacUtils.hmacSha256Hex(secret, toSign);

        // 5. 常量时间比较（防时序攻击）
        if (!MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
            throw new BizException(ErrorCode.SIGN_INVALID);
        }

        return true;
    }
}
```

> ⚠️ **禁** `String.equals()` 比较签名（时序攻击可逐字节爆破）。

---

## 7. 文件上传安全

### 7.1 多层防御

| 层 | 控制 |
|---|---|
| 网关 | 请求体大小 413 兜底 |
| 应用 | 业务上限（如 10MB） |
| 后缀 | 白名单（jpg/png/pdf） |
| Magic Number | Tika 探测真实类型 |
| 文件名 | UUID 重命名 |
| 病毒 | ClamAV 扫描（重要业务） |
| 存储 | OSS 独立域名，禁带主站 Cookie |
| 静态服务 | `Content-Disposition: attachment`，禁脚本执行 |

### 7.2 完整实现

```java
@Service
@RequiredArgsConstructor
public class FileUploadService {

    private static final Set<String> ALLOWED_MIME = Set.of(
        "image/jpeg", "image/png", "image/gif",
        "application/pdf",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );
    private static final long MAX_SIZE = 10 * 1024 * 1024;
    private static final Map<String, String> MIME_EXT = Map.of(
        "image/jpeg", ".jpg",
        "image/png", ".png",
        "application/pdf", ".pdf"
    );

    private final Tika tika = new Tika();

    public UploadResult upload(MultipartFile file) {
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }

        // Magic Number 探测
        String realMime;
        try {
            realMime = tika.detect(file.getInputStream());
        } catch (IOException e) {
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        if (!ALLOWED_MIME.contains(realMime)) {
            throw new BizException(ErrorCode.FILE_TYPE_FORBIDDEN);
        }

        // UUID 重命名（禁保留原文件名）
        String newName = UUID.randomUUID() + MIME_EXT.getOrDefault(realMime, "");

        // 上传 OSS
        String url = ossClient.upload(file, newName);

        // 审计
        auditLogService.record("FILE_UPLOAD", newName, file.getSize());

        return new UploadResult(newName, url, file.getSize(), realMime);
    }
}
```

---

## 8. 敏感数据加密

### 8.1 字段级加密（AES-256-GCM）

```java
public class AesEncryptor {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private final SecretKey secretKey;  // 从 KMS 获取

    public String encrypt(String plain) {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(CIPHER_ENCRYPT, secretKey, new GCMParameterSpec(128, iv));
        byte[] cipherText = cipher.doFinal(plain.getBytes(UTF_8));
        return Base64.getEncoder().encodeToString(iv) + ":" +
               Base64.getEncoder().encodeToString(cipherText);
    }

    public String decrypt(String encrypted) {
        String[] parts = encrypted.split(":");
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] cipherText = Base64.getDecoder().decode(parts[1]);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(CIPHER_DECRYPT, secretKey, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(cipherText), UTF_8);
    }
}
```

### 8.2 MyBatis TypeHandler

```java
@MappedTypes(EncryptedString.class)
public class EncryptedStringTypeHandler extends BaseTypeHandler<String> {
    private final AesEncryptor encryptor;

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String param, JdbcType jdbcType) {
        ps.setString(i, encryptor.encrypt(param));
    }

    @Override
    public String getNullableResult(ResultSet rs, String column) {
        return encryptor.decrypt(rs.getString(column));
    }
}
```

### 8.3 字段分类

| 字段 | 处理 |
|---|---|
| 手机号 | 明文存储（查询需求）+ 展示脱敏 |
| 身份证号 | AES 加密 + 索引列存 HMAC（用于等值查询） |
| 银行卡号 | AES 加密 + HMAC 索引 |
| 密码 | bcrypt（不可逆） |
| 地址 | AES 加密 |
| 健康 / 医疗数据 | AES 加密 + 严格访问控制 |

---

## 9. 审计日志

### 9.1 表设计

```sql
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(32) NOT NULL,
    user_id BIGINT,
    username VARCHAR(64),
    action VARCHAR(64) NOT NULL,              -- LOGIN/LOGOUT/UPDATE_ORDER/DELETE_USER
    target_type VARCHAR(32),                  -- order/user/payment
    target_id VARCHAR(64),
    before_value JSONB,                       -- 修改前
    after_value JSONB,                        -- 修改后
    result VARCHAR(16) NOT NULL,              -- SUCCESS/FAILED
    error_code INT,
    ip INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_log_user ON audit_log (user_id, created_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log (action, created_at DESC);
CREATE INDEX idx_audit_log_target ON audit_log (target_type, target_id);

-- 不可篡改：禁 UPDATE / DELETE（通过 DB 用户权限控制）
REVOKE UPDATE, DELETE ON audit_log FROM app_user;
```

### 9.2 注解切面

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String action();
    String targetType() default "";
    String targetIdSpel() default "";        // SpEL 表达式取 ID
}

@Aspect
@Component
public class AuditAspect {

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        User user = SecurityContext.current();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            auditLogService.record(AuditLog.builder()
                .traceId(TraceContext.current())
                .userId(user != null ? user.getId() : null)
                .action(auditable.action())
                .targetType(auditable.targetType())
                .targetId(evalSpel(auditable.targetIdSpel(), pjp))
                .result(error == null ? "SUCCESS" : "FAILED")
                .errorCode(error instanceof BizException b ? b.getCode() : null)
                .ip(RequestContext.currentIp())
                .userAgent(RequestContext.currentUserAgent())
                .build());
        }
    }
}
```

使用：

```java
@Auditable(action = "DELETE_ORDER", targetType = "order", targetIdSpel = "#orderId")
public void deleteOrder(String orderId) { ... }
```

### 9.3 Hash Chain（防篡改）

每日凌晨定时任务：

```sql
WITH last AS (
    SELECT hash FROM audit_log_chain WHERE date = CURRENT_DATE - 1
), today AS (
    SELECT string_agg(
        id || '|' || trace_id || '|' || user_id || '|' || action || '|' || target_id
        || '|' || result || '|' || extract(epoch from created_at),
        ','
        ORDER BY id
    ) AS batch FROM audit_log WHERE created_at::date = CURRENT_DATE
)
INSERT INTO audit_log_chain (date, hash)
SELECT CURRENT_DATE, encode(digest(
    COALESCE((SELECT hash FROM last), '') || '|' || COALESCE((SELECT batch FROM today), ''),
    'sha256'
), 'hex');
```

---

## 10. OAuth2 第三方登录

### 10.1 通用流程

```
1. 前端跳转 /v1/auth/oauth/{provider}?redirect_uri=xxx
2. 后端生成 state，存 Redis（key=state, value=redirect_uri+timestamp, TTL=10min）
3. 302 → 第三方授权页
4. 用户授权，第三方回调 /v1/auth/oauth/{provider}/callback?code=xxx&state=xxx
5. 服务端校验 state（Redis 取出，校验通过即删）
6. 用 code 换 access_token
7. 用 access_token 拉用户信息（openid / nickname / avatar）
8. 关联本地用户（首次登录自动注册）
9. 签发本系统 JWT
10. 302 → 前端 redirect_uri?token=xxx
```

### 10.2 Controller

```java
@RestController
@RequestMapping("/v1/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oauthService;

    @GetMapping("/{provider}")
    public ResponseEntity<Void> redirectToProvider(
            @PathVariable String provider,
            @RequestParam String redirectUri,
            HttpServletRequest req) {
        String state = oauthService.generateState(redirectUri);
        String authUrl = oauthService.buildAuthUrl(provider, state);
        return ResponseEntity.status(302).header("Location", authUrl).build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam String code,
            @RequestParam String state) {
        CallbackResult result = oauthService.handleCallback(provider, code, state);
        String redirect = result.redirectUri() + "?token=" + result.accessToken();
        return ResponseEntity.status(302).header("Location", redirect).build();
    }
}
```

### 10.3 state 校验（防 CSRF）

```java
@Service
public class OAuthService {

    public String generateState(String redirectUri) {
        String state = UUID.randomUUID().toString();
        // state → redirect_uri 绑定，10min 过期
        redisTemplate.opsForValue().set(
            "oauth:state:" + state,
            redirectUri,
            10, TimeUnit.MINUTES
        );
        return state;
    }

    public CallbackResult handleCallback(String provider, String code, String state) {
        String redirectUri = redisTemplate.opsForValue().get("oauth:state:" + state);
        if (redirectUri == null) {
            throw new BizException(ErrorCode.OAUTH_STATE_INVALID);
        }
        redisTemplate.delete("oauth:state:" + state);  // 一次性

        // 用 code 换 token
        OAuthAccessToken token = oauthClient.exchange(provider, code);
        OAuthUserInfo userInfo = oauthClient.getUserInfo(provider, token);

        // 关联本地用户
        User user = userService.bindOAuth(provider, userInfo);
        TokenPair jwt = jwtService.issue(user);

        return new CallbackResult(redirectUri, jwt.accessToken());
    }
}
```

---

## 11. 防常见攻击

### 11.1 SQL 注入

- MyBatis Plus Wrapper 天然参数化
- 自定义 XML 必须 `#{}`，禁 `${}`
- 排序字段动态化用白名单 `<choose>`

详见 mc-java-spec §7.2。

### 11.2 XSS（后端输入净化）

```java
public String sanitize(String input) {
    if (input == null) return null;
    return Jsoup.clean(input, Safelist.none());  // 移除所有 HTML
}
```

存储前净化，展示由前端负责。

### 11.3 CSRF

- JWT 模式（Authorization Header）：无 CSRF 风险
- Cookie 鉴权：必须 `SameSite=Lax` + 双提交 Token

### 11.4 SSRF

服务端发起外部请求时校验：

```java
public HttpResponse safeRequest(String url) {
    URI uri = URI.create(url);
    String host = uri.getHost();

    // 禁止内网地址
    InetAddress addr = InetAddress.getByName(host);
    if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
            || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
        throw new BizException(ErrorCode.SSRF_BLOCKED);
    }

    // 协议白名单
    if (!Set.of("http", "https").contains(uri.getScheme())) {
        throw new BizException(ErrorCode.SSRF_BLOCKED);
    }

    return httpClient.execute(url);
}
```

### 11.5 反序列化

- 禁用 fastjson2 autotype（详见 mc-java-spec §4.6.3）
- Jackson 配置 `failOnUnknownProperties(false)` + 禁用 `enableDefaultTyping`
- 反序列化目标类必须显式声明，禁 `Object.class`

---

## 12. 安全响应头

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .headers(h -> h
            .contentNegotiation(c -> c.sniff().disable())           // X-Content-Type-Options: nosniff
            .frameOptions(fo -> fo.deny())                          // X-Frame-Options: DENY
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true).maxAgeInSeconds(31536000)) // HSTS
            .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
            .addHeaderWriter((req, res) -> {
                res.setHeader("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; object-src 'none'");
                res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                res.setHeader("Permissions-Policy",
                    "geolocation=(), microphone=(), camera=()");
            }))
        .build();
}
```

---

## 13. 错误码补充

补充 mc-api-spec v1.6 §7 的安全相关错误码：

| 错误码 | 描述 |
|---|---|
| 10203 | 账号密码错误 |
| 10204 | 账号已被冻结 / 锁定 |
| 10207 | 密码强度不足 |
| 10208 | 密码与历史重复 |
| 10304 | OAuth state 校验失败 |
| 10405 | 接口签名缺失 |
| 10406 | 接口签名过期 |
| 10407 | 接口签名重复（nonce） |
| 10408 | 接口签名无效 |
| 10409 | SSRF 拦截 |

> 实施时先在 mc-api-spec v1.6 §7 登记新增错误码，再同步本规范的 ErrorCode enum。

---

## 附录 A：检查清单

### A.1 上线前必查

| # | 项 |
|---|---|
| 1 | 所有接口在 SecurityFilterChain 配置了权限 |
| 2 | 密码用 bcrypt（cost ≥ 12） |
| 3 | JWT 密钥环境变量注入 |
| 4 | 越权检查从 SecurityContext 取用户 ID |
| 5 | 数据权限通过拦截器强制 |
| 6 | 文件上传有 Magic Number 校验 |
| 7 | SQL 全部参数化 |
| 8 | 敏感字段加密存储 |
| 9 | 审计日志覆盖关键操作 |
| 10 | 安全响应头配置完整 |

### A.2 季度审计

- 依赖漏洞扫描（OWASP Dependency-Check）
- 密钥轮换（JWT 签名密钥 / 数据库加密密钥）
- 审计日志 hash chain 校验
- 权限清单 review（清理离职用户 / 多余权限）
- 渗透测试（重要业务）
