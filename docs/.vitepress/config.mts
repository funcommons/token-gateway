import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'token-gateway',
  description: '通用模型能力网关 · LLM 同步面 + 任务四模态面',
  lang: 'zh-CN',
  // GitHub Pages 项目站点子路径
  base: '/token-gateway/',
  ignoreDeadLinks: true,
  themeConfig: {
    nav: [
      { text: '首页', link: '/' },
      { text: 'LLM 面接入', link: '/用户文档/01_LLM面接入手册' },
      { text: '任务面接入', link: '/用户文档/02_任务面接入手册' },
      { text: '设计方案', link: '/开发文档/01_设计方案' },
      { text: 'GitHub', link: 'https://github.com/funcommons/token-gateway' },
    ],
    sidebar: [
      {
        text: '用户文档（调用方）',
        items: [
          { text: 'LLM 面接入手册', link: '/用户文档/01_LLM面接入手册' },
          { text: '任务面接入手册（规划中 M2.5）', link: '/用户文档/02_任务面接入手册' },
          { text: 'LLM 面 API 契约（YAML）', link: 'https://github.com/funcommons/token-gateway/blob/main/docs/用户文档/03_LLM面API契约.yaml' },
          { text: '任务面 API 契约（规划中）', link: 'https://github.com/funcommons/token-gateway/blob/main/docs/用户文档/04_任务面API契约.yaml' },
        ]
      },
      {
        text: '开发文档（网关/后端接入方）',
        items: [
          { text: '设计方案', link: '/开发文档/01_设计方案' },
          { text: '后端接入开发手册', link: '/开发文档/02_后端接入开发手册' },
          { text: '能力面接口契约（YAML）', link: 'https://github.com/funcommons/token-gateway/blob/main/docs/开发文档/03_能力面接口契约.yaml' },
        ]
      }
    ],
    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一页', next: '下一页' },
    lastUpdated: { text: '最后更新' },
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '菜单',
    darkModeSwitchLabel: '主题',
    socialLinks: [
      { icon: 'github', link: 'https://github.com/funcommons/token-gateway' }
    ],
    footer: {
      message: 'Released under the Apache-2.0 License.',
      copyright: 'Copyright © 2026 funcommons'
    }
  }
})
