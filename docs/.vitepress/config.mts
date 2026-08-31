import { defineConfig } from 'vitepress'

const githubBlob = 'https://github.com/funcommons/token-gateway/blob/main/docs'

export default defineConfig({
  title: 'token-gateway',
  description: '通用模型能力网关 · Universal Model Capability Gateway',
  base: '/token-gateway/',
  ignoreDeadLinks: true,

  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
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
              { text: 'LLM 面 API 契约（YAML）', link: `${githubBlob}/用户文档/03_LLM面API契约.yaml` },
              { text: '任务面 API 契约（规划中）', link: `${githubBlob}/用户文档/04_任务面API契约.yaml` },
            ]
          },
          {
            text: '开发文档（网关/后端接入方）',
            items: [
              { text: '设计方案', link: '/开发文档/01_设计方案' },
              { text: '后端接入开发手册', link: '/开发文档/02_后端接入开发手册' },
              { text: '后端服务对接安全契约方案', link: '/开发文档/04_后端服务对接安全契约方案' },
              { text: '能力面接口契约（YAML）', link: `${githubBlob}/开发文档/03_能力面接口契约.yaml` },
            ]
          }
        ],
        outline: { level: [2, 3], label: '本页目录' },
        docFooter: { prev: '上一页', next: '下一页' },
        lastUpdated: { text: '最后更新' },
        footer: {
          message: 'Released under the Apache-2.0 License.',
          copyright: 'Copyright © 2026 funcommons'
        }
      }
    },

    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      themeConfig: {
        nav: [
          { text: 'Home', link: '/en/' },
          { text: 'LLM Onboarding', link: '/en/user/llm-guide' },
          { text: 'Task Onboarding', link: '/en/user/task-guide' },
          { text: 'Design', link: '/en/dev/design' },
          { text: 'GitHub', link: 'https://github.com/funcommons/token-gateway' },
        ],
        sidebar: [
          {
            text: 'User Docs (Callers)',
            items: [
              { text: 'LLM Face Onboarding', link: '/en/user/llm-guide' },
              { text: 'Task Face Onboarding (planned M2.5)', link: '/en/user/task-guide' },
              { text: 'LLM API Contract (YAML)', link: `${githubBlob}/用户文档/03_LLM面API契约.yaml` },
              { text: 'Task API Contract (planned)', link: `${githubBlob}/用户文档/04_任务面API契约.yaml` },
            ]
          },
          {
            text: 'Developer Docs (Gateway/Backend)',
            items: [
              { text: 'Design Proposal', link: '/en/dev/design' },
              { text: 'Backend Onboarding Manual', link: '/en/dev/backend-onboarding' },
              { text: 'Backend Security Contract', link: '/en/dev/backend-security-contract' },
              { text: 'Capability-Face Contract (YAML)', link: `${githubBlob}/开发文档/03_能力面接口契约.yaml` },
            ]
          }
        ],
        outline: { level: [2, 3], label: 'On this page' },
        footer: {
          message: 'Released under the Apache-2.0 License.',
          copyright: 'Copyright © 2026 funcommons'
        }
      }
    }
  }
})
