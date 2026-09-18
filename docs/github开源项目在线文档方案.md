# GitHub 开源项目在线文档方案

以 token-gateway 已验证实践为准，够用且零成本。

## 选型

| 项 | 选择 | 理由 |
|---|---|---|
| 站点生成 | VitePress | md 即站点，内置死链检查、代码组、多语言 |
| 托管 | GitHub Pages | 免费公有仓，`https://<org>.github.io/<repo>/` |
| 部署 | Actions + deploy-pages | push 即发，无需本地构建 |
| 包管理 | npm（根 package.json） | 仅 vitepress 一个 devDep |

## 目录结构

```
repo/
├─ docs/                      # 站点根（vitepress root）
│  ├─ .vitepress/config.mts   # base='/<repo>/'；多语言 locales；勿开 ignoreDeadLinks
│  ├─ index.md                # 首页 hero + 导航
│  ├─ 用户文档/               # 编号 01-09（调用方视角）
│  ├─ 开发文档/               # 编号编号连续，缺口=契约 yaml 占位
│  ├─ en/{user,dev}/          # 英文镜像（可选）
│  └─ archive/                # 历史时点文档，不再更新
├─ README.md / README_EN.md   # 门户：badge、快速开始、文档索引（指向在线站）
├─ CHANGELOG.md               # Keep a Changelog
├─ CONTRIBUTING.md / SECURITY.md
└─ .github/workflows/docs.yml # 部署流水线
```

## 部署流水线（docs.yml 要点）

- 触发：`push: branches:[main] paths:['docs/**','package*.json']` + `workflow_dispatch`
- 步骤：`checkout → setup-node 22 (cache: npm) → npm ci → npm run docs:build → upload-pages-artifact(docs/.vitepress/dist) → deploy-pages`
- 仓库 Settings → Pages → Source 选 **GitHub Actions**

## 命令

```bash
npm install               # 首次
npm run docs:dev          # 本地预览
npm run docs:build        # 构建（死链即报错，推前必跑）
```

## 治理约定

1. **代码是唯一事实源**：端点/错误码/配置/测试数写进文档前先对代码；过期内容立即修，不挂「待更新」
2. **死链零容忍**：不开 `ignoreDeadLinks`，构建即检查
3. **编号 + 归档**：正式文档连续编号；时点快照（如历史测试报告）进 `archive/` 并写明时点
4. **中英同更**：改中文必查英文镜像，不同步就在 PR 里声明裁剪
5. **用户可感知变更**同步 CHANGELOG `[Unreleased]`；行为变更同轮补文档与测试

## 关键教训（本项目实踩）

- 不用 `Date`/随机数生成文档示例数据，URL 里中文目录可用（自动 encode）但外链站内引用统一走相对路径
- 大重命名目录 = 断 vitepress 配置 + 内链 + GitHub 深链，收益小于风险，慎做
- README 的版本引用（jar 名/依赖 tag/测试数）随发版收口，否则滞后两个版本起
