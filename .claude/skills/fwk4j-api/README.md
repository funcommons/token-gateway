# framework4j-api

> API 契约层 — 仅含 `ApiCode` 错误码枚举

## v2.1 变更说明

v2.1 web 拆分后，本模块**仅保留 `ApiCode` 错误码枚举**（契约层）。

以下类已迁移到 `framework4j-web`：
- `ApiResponse<T>` → `fun.commons.framework4j.web.ApiResponse`
- `ApiException` → `fun.commons.framework4j.web.ApiException`
- `ApiAssert` → `fun.commons.framework4j.web.ApiAssert`
- `ApiError` → `fun.commons.framework4j.web.ApiError`
- `TraceContext` → `fun.commons.framework4j.web.TraceContext`
- `GlobalExceptionHandler` → `fun.commons.framework4j.web.exception.GlobalExceptionHandler`
- `TraceConfig` / `WebConfig` → `fun.commons.framework4j.web.config.*`

## ApiCode 错误码段位

| 段位 | 含义 | 示例 |
|---|---|---|
| `0` | 成功 | `SUCCESS(0, "操作成功")` |
| `10xxx` | 系统与基础设施 | `SYSTEM_BUSY(10001)` / `SERVICE_TIMEOUT(10003)` |
| `101xx` | 请求与参数校验 | `PARAM_ERROR(10100)` / `PARAM_MISSING(10101)` / `PARAM_FORMAT_ERROR(10102)` |
| `102xx` | 认证与账号 | `UNAUTHORIZED(10200)` / `TOKEN_EXPIRED(10201)` / `REFRESH_INVALID(10211)` |
| `103xx` | 权限与授权 | `FORBIDDEN(10300)` / `SIGNATURE_ERROR(10302)` |
| `104xx` | 资源与数据 | `NOT_FOUND(10400)` / `UNIQUE_CONFLICT(10401)` / `STATE_CONFLICT(10402)` |
| `105xx` | 流量控制 + 文件上传 | `TOO_MANY_REQUESTS(10500)` / `DUPLICATE_SUBMIT(10501)` / `UPLOAD_FAILED(10503)` |
| `106xx` | 业务自定义（由业务线登记认领） | — |
| `10700` | 部分成功 | `PARTIAL_SUCCESS(10700)` |

## 使用方式

```java
import fun.commons.framework4j.api.ApiCode;

// 返回失败响应
return ApiResponse.fail(ApiCode.NOT_FOUND, "订单不存在: " + id);

// 抛业务异常
throw new ApiException(ApiCode.PARAM_MISSING, "items 不能为空");

// 查找错误码
ApiCode code = ApiCode.fromCode(10500);  // TOO_MANY_REQUESTS
```

## 扩展错误码

业务自定义错误码落在 `106xx` 段，直接在业务代码中使用：

```java
// 业务层定义
public static final int ORDER_OUT_OF_STOCK = 10601;
public static final int COUPON_EXPIRED = 10602;

return ApiResponse.fail(ORDER_OUT_OF_STOCK, "商品库存不足");
```

不建议在 SDK 的 `ApiCode` 枚举中新增业务错误码（保持 SDK 通用性）。

## 相关文档

- `framework4j-web/README.md` — ApiResponse 信封 + GlobalExceptionHandler
- `Java开发准则.md` §8 响应与错误处理
- `mc-api-spec/API 响应结构与错误码规范 v1.6.md` §7
