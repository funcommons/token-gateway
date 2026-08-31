# SKILL 校验脚本

校验 `mc-skills/*/SKILL.md` 的完整性、规范性、引用真实性。用于本地开发与 CI 流水线。

## 用法

```bash
# 校验所有 SKILL，仅打印结果
node scripts/validate-skills.js

# CI 模式：发现错误时 exit 1（用于 GitHub Actions / GitLab CI）
node scripts/validate-skills.js --ci

# 输出修复建议
node scripts/validate-skills.js --fix
```

## 检查项

| # | 类别 | 检查内容 |
|---|---|---|
| 1 | frontmatter | 必填字段：`name` / `description` / `version` / `enabled` |
| 2 | metadata | 必填子字段：`type` / `category` / `tags` / `language` / `related-specs` / `related-skills` / `author` / `last-reviewed` / `examples` |
| 3 | metadata | 必须有 `spec-version` 或 `spec-versions`（至少一个） |
| 4 | metadata | `type` 取值合法：`meta-skill` / `domain-spec` / `workflow` / `tool` |
| 5 | metadata | `category` 取值合法：`backend` / `frontend` / `database` / `workflow` / `tooling` / `documentation` |
| 6 | metadata | `tags` 是非空数组（建议 ≥ 5） |
| 7 | metadata | `examples` 是数组且至少 3 项 |
| 8 | metadata | `related-skills` 引用的 name 在所有 SKILL 中存在 |
| 9 | metadata | `related-specs` 引用的文件实际存在 |
| 10 | metadata | `last-reviewed` 格式为 `YYYY-MM-DD` |
| 11 | 内容 | `description` 长度 ≤ 500 字符 |
| 12 | 内容 | 总行数 ≤ 300（`mc-cli` 例外，上限 400） |
| 13 | 章节 | 必填：§0 用户速查 / §1 元信息（所有 SKILL） |
| 14 | 章节 | domain-spec 额外必填：§2 全局铁律 / §4 关键文件索引（meta-skill 豁免） |

## CI 集成

### GitHub Actions

`.github/workflows/lint-skills.yml`：

```yaml
name: Lint Skills
on:
  push:
    paths:
      - 'mc-skills/**/SKILL.md'
      - 'mc-skills/scripts/validate-skills.js'
  pull_request:
    paths:
      - 'mc-skills/**/SKILL.md'

jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - name: Validate SKILLs
        run: node mc-skills/scripts/validate-skills.js --ci
```

### GitLab CI

`.gitlab-ci.yml`：

```yaml
lint-skills:
  image: node:20-alpine
  script:
    - node mc-skills/scripts/validate-skills.js --ci
  only:
    changes:
      - mc-skills/**/SKILL.md
      - mc-skills/scripts/validate-skills.js
```

### pre-commit hook

`.pre-commit-hooks.yaml`：

```yaml
- id: validate-skills
  name: Validate SKILL.md files
  entry: node mc-skills/scripts/validate-skills.js --ci
  language: system
  files: ^mc-skills/.*/SKILL\.md$
  pass_filenames: false
```

或直接 `.git/hooks/pre-commit`：

```bash
#!/bin/sh
node mc-skills/scripts/validate-skills.js --ci || exit 1
```

## 输出示例

```
📋 校验 5 个 SKILL
────────────────────────────────────────────────────────────
✅ PASS  mc-api-spec
✅ PASS  mc-cli
✅ PASS  mc-database-spec
✅ PASS  mc-java-spec
✅ PASS  mc-webui-spec
────────────────────────────────────────────────────────────
总计: 5 个 SKILL / 0 错误 / 0 警告
```

失败示例：

```
❌ FAIL  mc-api-spec
       ERROR: metadata 缺少必填字段: examples
       ERROR: related-specs 引用了不存在的文件: API 响应结构与错误码规范 v1.7.md
       WARN:  metadata.tags 仅 2 项，建议 ≥ 5
```

## 实现说明

- **零依赖**：仅用 Node.js 内置 `fs` / `path`，无需 `npm install`
- **简易 YAML 解析**：仅支持 SKILL frontmatter 用到的字段结构（top-level / metadata 子字段 / list / map）
- **中文 key 支持**：`spec-versions` 下的中文 key（如「开发规范」）可正确解析
- **按 type 区分**：`meta-skill` 不要求「全局铁律」「关键文件索引」章节；`domain-spec` 等必须

## 维护

- 检查项变更：修改 `validate-skills.js` 顶部的常量（`REQUIRED_FRONTMATTER` / `REQUIRED_METADATA` / `REQUIRED_SECTIONS` 等）
- 新增 SKILL 类型：扩展 `VALID_TYPES` 集合
- 新增业务域：扩展 `VALID_CATEGORIES` 集合
- 行数上限调整：修改 `MAX_LINES_DEFAULT` 或 `MAX_LINES_OVERRIDES`
