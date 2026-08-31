---
name: mc-database-spec
description: 数据库设计与 SQL 开发激活（PostgreSQL）。覆盖建表命名、SQL 编写、标签系统、动态属性 EAV。触发词：数据库、建表、SQL、命名规范、标签系统、动态属性、EAV、字典表、索引、分页、数据模型、database、table、query、schema、naming、tag、PostgreSQL。
version: 1.1.0
enabled: true
metadata:
  type: domain-spec
  category: database
  tags: [postgresql, sql, schema, naming, jsonb, eav, intarray, tag-system, indexing]
  language: zh-CN
  spec-versions:
    开发规范: v1.2
    表分类与命名: v1.0
    标签系统: v1.3
    EAV 动态属性: v1.0
  related-specs:
    - PostgreSQL 1.开发规范v1.2.md
    - PostgreSQL 2.数据库表分类与命名规范.md
    - PostgreSQL 3.系统标签能力开发规范v1.3.md
    - PostgreSQL 4.动态属性 (EAV) 设计规范.md
  related-skills: [mc-java-spec, mc-api-spec]
  author: architecture-team
  last-reviewed: 2026-06-23
  examples:
    - "PostgreSQL 加个索引"                  # 自动激活：索引设计
    - "设计用户表，包含扩展字段"            # 自动激活：建表 + EAV
    - "JSONB 字段怎么范围查询"               # 自动激活：JSONB EAV
    - "实现文章多标签筛选"                   # 自动激活：标签系统
    - "SQL 慢查询怎么优化"                   # 自动激活：SQL 优化
    - "大表精确 count 太慢"                  # 自动激活：性能
    - "逻辑删除字段怎么建唯一索引"           # 自动激活：部分唯一索引
---

# PostgreSQL 数据库开发规范

根据不同开发场景，采取不同的规范应用策略。

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 设计新表 / 数据模型 | 场景一：建表与命名 |
| 写 SQL 查询 | 场景二：SQL 开发 |
| 实现多对多筛选 / 标签 | 场景三：标签系统（int[] + intarray） |
| 实现动态属性 / 键值对扩展 | 场景四：动态属性（JSONB EAV） |
| 检查 DB 合规 | 场景五：P0 必查 5 项 |
| 退出本规范 | 「退出 mc-database-spec」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 数据库 | PostgreSQL（推荐 16+） |
| 连接池 | Druid（应用层） |
| ORM | MyBatis Plus（应用层） |
| **适用** | 表设计 / 命名 / SQL / 标签 / EAV / 索引 / 性能优化 |
| **不适用** | 应用层 SQL 编排（→ mc-java-spec 场景五）、API 设计（→ mc-api-spec） |
| 退出 | 「退出 mc-database-spec」 |

## 2. 全局铁律

1. **表必须有主键 id** — bigint / bigserial / serial（仅字典表用 serial）
2. **所有表必备 `created_at`** — timestamptz，默认 `CURRENT_TIMESTAMP`
3. **禁止 `SELECT *`** — 必须明确列出字段
4. **禁止外键约束** — 数据一致性在应用层保证
5. **字段尽量 NOT NULL + DEFAULT** — 避免 NULL 导致索引失效和 count() 异常
6. **JSONB 必带 GIN 索引** — `USING GIN (jsonb_col)`
7. **金额用 `numeric(M,D)`** — 禁 `double` / `float` / `integer` 分
8. **标签系统必须 `int[]` + `intarray`** — 禁 JSONB / varchar[] / bigint[] 存标签

## 3. 场景判定

```
当前任务？
├── 设计新表 / 数据模型        → 场景一：建表与命名
├── 编写或优化 SQL            → 场景二：SQL 开发
├── 实现标签 / 多对多筛选     → 场景三：标签系统
├── 实现动态属性 / 键值扩展   → 场景四：动态属性（EAV）
├── 检查 DB 合规              → 场景五：规范检查
└── 不涉及 DB 设计/SQL         → 不需要此规范
```

### 场景一：建表与命名

**表类型判定**：

| 性质 | 类型 | 后缀 | 示例 |
|---|---|---|---|
| 核心业务对象（用户/商品/客户） | 实体表 | 无 | `ump_user`、`trade_product` |
| 业务事件 / 单据（订单/支付） | 事务表 | 无 | `trade_order`、`payment_record` |
| 多对多映射（用户-角色） | 关系表 | `_rel`/`_map` | `ump_user_role_rel` |
| 主实体冷数据 / 大字段 | 扩展表 | `_profile`/`_detail`/`_ext` | `ump_user_profile` |
| 子类继承（1:0..1 父子） | 扩展表 | 无（前缀 `[子]_[父]`） | `ump_company_user` |
| 动态键值对 | 扩展表 EAV | `_attr`/`_meta` | `trade_goods_attr` |
| 静态配置 / 枚举 / 字典 | 配置/字典表 | `_config`/`_dict` | `sys_global_config` |
| 操作日志 / 变更历史 | 日志表 | `_log`/`_history`/`_journal` | `ump_user_op_log` |
| 预计算统计 / 聚合 | 汇总表 | `_stats`/`_summary`/`_report` | `trade_daily_sales_report` |

**命名规则**：小写+下划线、表名 ≤ 32 字符、字段名 ≤ 32 字符、varchar 必须指定长度、禁 ENUM 类型、状态/类型字段用 varchar + 大写英文值（`SUCCESS`/`FAIL`/`PENDING`）。

**必备字段速查**：

| 表类型 | id | created_at | updated_at | is_deleted |
|---|---|---|---|---|
| 实体 / 事务表 | bigint（雪花） | ✅ | ✅ | ✅ |
| 关系表 | bigserial | ✅ | - | - |
| 扩展表（垂直/继承） | bigint | ✅ | ✅ | 按需 |
| 配置 / 字典表 | serial/bigserial + `code UNIQUE` | ✅ | ✅ | - |
| 日志表 | bigserial | ✅ | - | - |
| 汇统表 | bigserial | ✅ | ✅ | - |

**索引命名**：普通 `idx_字段1_字段2`，唯一 `uk_字段1_字段2`；逻辑删除字段配套部分唯一索引 `WHERE (is_deleted = 0)`。

详见 `./PostgreSQL 2.数据库表分类与命名规范.md` + `./PostgreSQL 1.开发规范v1.2.md`「表设计规范」。

### 场景二：SQL 开发

**强制规则**：

| # | 规则 |
|---|---|
| 1 | SQL 必须带追踪注释 `/*traceid=abc,topic=X*/` |
| 2 | 查询必须 LIMIT，≤ 1000 行 |
| 3 | 禁 `SELECT *`，明确列出字段 |
| 4 | WHERE 至少命中一个索引（用 EXPLAIN 验证） |
| 5 | JOIN ≤ 3 个表 |
| 6 | 禁 JOIN 子查询（用 CTE `WITH` 替代） |
| 7 | 禁左/全模糊（`LIKE '%xx'`） |
| 8 | 业务唯一字段建 UNIQUE INDEX |
| 9 | 逻辑删除用部分唯一索引 `WHERE (is_deleted = 0)` |
| 10 | 大表禁精确 count(*)，用 `pg_class.reltuples` 估算 |
| 11 | 禁外键 / 级联 / 存储过程 / 函数 / 触发器 / TRUNCATE |
| 12 | UPDATE/DELETE 必须指定主键/唯一键 |
| 13 | SQL 参数必须用占位符 `?`，禁字符串拼接 |
| 14 | 必须用 Druid 连接池 |
| 15 | JSONB 必须配 GIN 索引 |

**推荐优化**：ORDER BY 字段放索引末尾；覆盖索引 `INCLUDE`；深分页用游标法；`COALESCE(SUM, 0)`；IN 列表 ≤ 1000（推荐 `= ANY(?)`）；复杂查询用 CTE。

详见 `./PostgreSQL 1.开发规范v1.2.md`「SQL 开发规范」。

### 场景三：标签系统（int[] + intarray）

**核心架构**：标签字典表（`*_tag_dict`，SERIAL int4） + 内容主表（`tag_ids INT[]`） + `intarray` 扩展。

**关键约束**：
- **禁** JSONB / varchar[] 方案
- **严厉禁** bigint[]（intarray 不支持）
- 字典表 id 必须 SERIAL（int4）
- 字典表必须 `UNIQUE(category, tag_value)`
- `tag_ids` 字段配 GIN 索引 + `gin__int_ops` 操作符类

**核心操作**：

| 操作 | 方法 |
|---|---|
| 写入标签 | 应用层先查/建 tag_dict 获取 id，再存入 tag_ids |
| 筛选 WHERE | `tag_ids && target_ids`（GIN 索引） |
| 计分 SELECT | `array_length(tag_ids & target_ids, 1) AS match_score` |
| 显示标签名 | `LATERAL unnest(tag_ids)` + `LEFT JOIN *_tag_dict` |
| 重命名 | `UPDATE *_tag_dict SET tag_value=... WHERE id=?`（不扫主表） |
| 删除 | 事务：DELETE dict + `array_remove(tag_ids, id)` |

详见 `./PostgreSQL 3.系统标签能力开发规范v1.3.md`。

### 场景四：动态属性（JSONB EAV）

**核心方案**：实体主表增加 `attributes JSONB NOT NULL DEFAULT '{}'::jsonb`（禁经典 EAV 三表模型）。

**关键约束**：
- JSONB 列统一命名 `attributes`
- NOT NULL + DEFAULT `'{}'::jsonb`
- **必须** GIN 索引 `USING GIN (attributes)`
- 内部必须是 Object `{}`，禁裸数组
- 数值必须存为 JSON 数字（`0.25` 而非 `"0.25"`）

**CRUD 速查**：

| 操作 | 示例 |
|---|---|
| 精确键值 | `WHERE attributes @> '{"color":"red"}'` |
| 键存在 | `WHERE attributes ? 'storage_gb'` |
| 数组元素 | `WHERE attributes @> '{"tags":["sale"]}'` |
| 数值范围 | `WHERE (attributes->>'weight')::numeric > 0.18` |
| 新增/覆盖键 | `SET attributes = attributes || '{"color":"black"}'` |
| 删除键 | `SET attributes = attributes - 'tags'` |
| 更新嵌套键 | `jsonb_set(attributes, '{specs,ram}', '16'::jsonb)` |

**可选**：高频范围查询/排序建 B-Tree 表达式索引 `(((attributes->>'weight_kg')::numeric))`。

**属性定义表**：`attribute_definitions`（key_name UNIQUE / label / data_type / validation_rules JSONB），应用层 C/U 前校验。

详见 `./PostgreSQL 4.动态属性 (EAV) 设计规范.md`。

### 场景五：规范检查（P0 必查 5 项）

| # | 检查项 | 检查方式 |
|---|---|---|
| 1 | 表有主键 id | 查 DDL |
| 2 | 禁 `SELECT *` | grep SQL |
| 3 | SQL 带追踪注释 | grep `/*traceid=` |
| 4 | JSONB 列带 GIN 索引 | 查索引定义 |
| 5 | 无外键约束 | 查 DDL `FOREIGN KEY` |

**P1/P2/P3**：命名规范（小写下划线 / 模块前缀 / 表类型后缀）/ 必备字段（created_at / updated_at / is_deleted）/ 状态字段大写英文值 / varchar 长度 / 部分唯一索引（逻辑删除）/ 标签字典表 int4 / intarray 扩展启用 / EAV JSONB 非经典三表 / 数值存数字而非字符串。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./PostgreSQL 1.开发规范v1.2.md` | 开发规范（表设计 + SQL） | v1.2 |
| `./PostgreSQL 2.数据库表分类与命名规范.md` | 表分类与命名 | - |
| `./PostgreSQL 3.系统标签能力开发规范v1.3.md` | 标签系统（int[] + intarray） | v1.3 |
| `./PostgreSQL 4.动态属性 (EAV) 设计规范.md` | 动态属性（JSONB EAV） | - |

## 5. 与其他规范协作

| 涉及 | 同时参考 |
|---|---|
| 应用层 SQL 编排（MyBatis Plus / Mapper） | `../mc-java-spec/SKILL.md`（场景五） |
| API 设计 / 字段命名 | `../mc-api-spec/SKILL.md`（v1.6 §5） |
| 前端调用 / 类型定义 | `../mc-webui-spec/SKILL.md`（场景三） |

**字段命名链**：DB `snake_case` ↔ Java 实体 `lowerCamelCase`（MyBatis Plus 自动转换）↔ API 响应 `snake_case`（Jackson 全局策略）。三层命名天然一致。
