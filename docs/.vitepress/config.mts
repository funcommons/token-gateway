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
          { text: '产品简介', link: '/用户文档/01_产品简介' },
          { text: '快速开始', link: '/用户文档/02_快速开始' },
          { text: 'LLM 面', link: '/用户文档/04_LLM面接入手册' },
          { text: '任务面', link: '/用户文档/05_任务面接入手册' },
          { text: '开发文档', link: '/开发文档/01_设计方案' },
          { text: 'GitHub', link: 'https://github.com/funcommons/token-gateway' },
        ],
        sidebar: [
          {
            text: '用户文档（调用方）',
            items: [
              { text: '产品简介', link: '/用户文档/01_产品简介' },
              { text: '快速开始', link: '/用户文档/02_快速开始' },
              { text: '通用约定（认证/错误码/限流/幂等）', link: '/用户文档/03_通用约定' },
              { text: 'LLM 面接入手册', link: '/用户文档/04_LLM面接入手册' },
              { text: '任务面接入手册', link: '/用户文档/05_任务面接入手册' },
              { text: '常见问题 FAQ', link: '/用户文档/06_FAQ' },
            ]
          },
          {
            text: 'API 契约',
            items: [
              { text: 'LLM 面 API 契约（YAML）', link: `${githubBlob}/用户文档/07_LLM面API契约.yaml` },
              { text: '任务面 API 契约（YAML）', link: `${githubBlob}/用户文档/08_任务面API契约.yaml` },
            ]
          },
          {
            text: '开发文档（网关/后端接入方）',
            items: [
              { text: '设计方案', link: '/开发文档/01_设计方案' },
              { text: '后端接入开发手册', link: '/开发文档/02_后端接入开发手册' },
              { text: '后端服务对接安全契约方案', link: '/开发文档/04_后端服务对接安全契约方案' },
              { text: '任务面 lotask4j 托管方案', link: '/开发文档/05_任务面lotask4j托管方案' },
              { text: '任务面 face-task 开发手册', link: '/开发文档/06_任务面face-task开发手册' },
              { text: 'lotask4j 租户开通手册', link: '/开发文档/07_lotask4j租户开通手册' },
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
          { text: 'Overview', link: '/en/user/overview' },
          { text: 'Quickstart', link: '/en/user/quickstart' },
          { text: 'LLM Face', link: '/en/user/llm-guide' },
          { text: 'Task Face', link: '/en/user/task-guide' },
          { text: 'Dev Docs', link: '/en/dev/design' },
          { text: 'GitHub', link: 'https://github.com/funcommons/token-gateway' },
        ],
        sidebar: [
          {
            text: 'User Docs (Callers)',
            items: [
              { text: 'Product Overview', link: '/en/user/overview' },
              { text: 'Quickstart', link: '/en/user/quickstart' },
              { text: 'Conventions (Auth/Errors/RateLimit)', link: '/en/user/conventions' },
              { text: 'LLM Face Guide', link: '/en/user/llm-guide' },
              { text: 'Task Face Guide', link: '/en/user/task-guide' },
              { text: 'FAQ', link: '/en/user/faq' },
            ]
          },
          {
            text: 'API Contracts',
            items: [
              { text: 'LLM API Contract (YAML)', link: `${githubBlob}/用户文档/07_LLM面API契约.yaml` },
              { text: 'Task API Contract (YAML)', link: `${githubBlob}/用户文档/08_任务面API契约.yaml` },
            ]
          },
          {
            text: 'Developer Docs (Gateway/Backend)',
            items: [
              { text: 'Design Proposal', link: '/en/dev/design' },
              { text: 'Backend Onboarding Manual', link: '/en/dev/backend-onboarding' },
              { text: 'Backend Security Contract', link: '/en/dev/backend-security-contract' },
              { text: 'Task Face lotask4j Hosting', link: '/en/dev/task-lotask4j-hosting' },
              { text: 'Task Face Development Handbook', link: '/en/dev/task-face-dev-handbook' },
              { text: 'lotask4j Tenant Onboarding', link: '/en/dev/lotask4j-tenant-onboarding' },
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
