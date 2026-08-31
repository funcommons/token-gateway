#!/usr/bin/env node
/**
 * SKILL 完整性校验脚本
 *
 * 用法：
 *   node scripts/validate-skills.js                  # 校验所有 SKILL
 *   node scripts/validate-skills.js --fix            # 输出修复建议
 *   node scripts/validate-skills.js --ci             # CI 模式（违反即 exit 1）
 *
 * 检查项：
 *   1. frontmatter 必填字段（name/description/version/enabled）
 *   2. metadata 必填子字段（type/category/tags/language/spec-version|spec-versions/related-specs/related-skills/author/last-reviewed/examples）
 *   3. description 长度 ≤ 500 字符
 *   4. 总行数 ≤ 300（mc-cli 例外，允许 400）
 *   5. 必填章节（§0 用户速查 / §1 元信息 / §2 全局铁律 / §4 文件索引）
 *   6. related-specs 引用的文件实际存在
 *   7. related-skills 引用的 name 在所有 SKILL 中存在
 *   8. tags 是非空数组
 *   9. examples 是非空数组（≥ 3 项）
 *  10. type 取值合法（meta-skill / domain-spec / workflow / tool）
 *  11. category 取值合法（backend / frontend / database / workflow / tooling）
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const VALID_TYPES = new Set(['meta-skill', 'domain-spec', 'workflow', 'tool']);
const VALID_CATEGORIES = new Set(['backend', 'frontend', 'database', 'workflow', 'tooling', 'documentation']);
const MAX_LINES_DEFAULT = 300;
const MAX_LINES_OVERRIDES = { 'mc-cli': 400 }; // meta-skill 允许略长
const MAX_DESCRIPTION_LENGTH = 500;
const REQUIRED_FRONTMATTER = ['name', 'description', 'version', 'enabled'];
const REQUIRED_METADATA = [
  'type', 'category', 'tags', 'language',
  'related-specs', 'related-skills',
  'author', 'last-reviewed', 'examples',
];
const REQUIRED_SECTIONS = [
  /^## 0\.\s*用户速查/m,
  /^## 1\.\s*元信息/m,
];
// domain-spec 额外要求「全局铁律」和「关键文件索引」；meta-skill 不要求
const DOMAIN_ONLY_SECTIONS = [
  /^## 2\.\s*全局铁律/m,
  /^## 4\.\s*关键文件索引/m,
];

/** 提取 frontmatter（YAML between --- and ---） */
function parseFrontmatter(content) {
  const match = content.match(/^---\n([\s\S]*?)\n---\n/);
  if (!match) return null;
  return { yaml: match[1], body: content.slice(match[0].length) };
}

/** 简易 YAML 解析（仅支持 SKILL 用到的字段结构）
 *
 * 缩进约定：
 *   0 空格: top-level（name / description / version / enabled / metadata）
 *   2 空格: metadata 子字段 scalar（type: xxx）
 *   4 空格: metadata 子字段的 list 项（- xxx）或 map 子项（key: value）
 *
 * key 支持中文（如 spec-versions 下的「开发规范」）
 */
function parseSimpleYaml(yaml) {
  const result = {};
  const lines = yaml.split('\n');
  let currentKey = null;
  let currentSubKey = null;

  // key 正则：ASCII 字母/数字/下划线/连字符，或任意非 ASCII（中文/日文等）
  const KEY_PATTERN = '[A-Za-z0-9_-]+|[^\\x00-\\x7f][^:]*';
  const keyRegex = (indent) => new RegExp(`^${indent}(${KEY_PATTERN}):\\s*(.*)$`);

  for (const line of lines) {
    if (!line.trim() || line.trim().startsWith('#')) continue;

    // 4-space 缩进：metadata 子字段下的 list 项 或 map 子项
    const deepMatch = line.match(/^    (\S.*)$/);
    if (deepMatch && currentKey === 'metadata' && currentSubKey) {
      const inner = deepMatch[1];
      // list 项: "- xxx"
      const listItem = inner.match(/^-\s+(.+)$/);
      if (listItem) {
        if (!Array.isArray(result.metadata[currentSubKey])) {
          result.metadata[currentSubKey] = [];
        }
        result.metadata[currentSubKey].push(listItem[1].trim().replace(/^["']|["']$/g, ''));
        continue;
      }
      // map 子项: "key: value"（支持中文 key）
      const mapItem = inner.match(new RegExp(`^(${KEY_PATTERN}):\\s*(.*)$`));
      if (mapItem) {
        if (!result.metadata[currentSubKey] || Array.isArray(result.metadata[currentSubKey]) || typeof result.metadata[currentSubKey] !== 'object') {
          result.metadata[currentSubKey] = {};
        }
        const v = mapItem[2].trim();
        result.metadata[currentSubKey][mapItem[1]] = v === '' ? null : parseScalarOrArray(v);
        continue;
      }
    }

    // 2-space 缩进：metadata 子字段
    const subMatch = line.match(keyRegex('  '));
    if (subMatch && currentKey === 'metadata') {
      if (!result.metadata) result.metadata = {};
      const subKey = subMatch[1];
      const subVal = subMatch[2].trim();
      currentSubKey = subKey;
      if (subVal === '') {
        result.metadata[subKey] = null;  // 等下一行决定 list/map
      } else if (subVal === '[]') {
        result.metadata[subKey] = [];
        currentSubKey = null;
      } else {
        result.metadata[subKey] = parseScalarOrArray(subVal);
        currentSubKey = null;  // scalar，结束
      }
      continue;
    }

    // top-level
    const topMatch = line.match(keyRegex(''));
    if (topMatch) {
      currentKey = topMatch[1];
      currentSubKey = null;
      const val = topMatch[2].trim();
      if (val === '') {
        result[currentKey] = null;
      } else if (val === '[]') {
        result[currentKey] = [];
      } else {
        result[currentKey] = parseScalarOrArray(val);
      }
    }
  }
  return result;
}

function parseScalarOrArray(val) {
  const inlineArray = val.match(/^\[(.*)\]$/);
  if (inlineArray) {
    const inner = inlineArray[1].trim();
    if (!inner) return [];
    return inlineArray[1]
      .split(',')
      .map(s => s.trim().replace(/^["']|["']$/g, ''))
      .filter(Boolean);
  }
  return val.replace(/^["']|["']$/g, '');
}

/** 查找所有 SKILL.md */
function findSkillFiles() {
  const result = [];
  const entries = fs.readdirSync(ROOT, { withFileTypes: true });
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const skillPath = path.join(ROOT, entry.name, 'SKILL.md');
    if (fs.existsSync(skillPath)) {
      result.push({ name: entry.name, path: skillPath });
    }
  }
  return result;
}

/** 校验单个 SKILL */
function validateSkill(skillFile, allSkillNames) {
  const errors = [];
  const warnings = [];
  const { name: skillName, path: filePath } = skillFile;

  const content = fs.readFileSync(filePath, 'utf8');
  const lines = content.split('\n');

  // 1. frontmatter 解析
  const fm = parseFrontmatter(content);
  if (!fm) {
    errors.push('缺少 frontmatter（--- ... ---）');
    return { skillName, errors, warnings };
  }

  const meta = parseSimpleYaml(fm.yaml);

  // 2. 必填 frontmatter 字段
  for (const key of REQUIRED_FRONTMATTER) {
    if (meta[key] === undefined || meta[key] === null || meta[key] === '') {
      errors.push(`frontmatter 缺少必填字段: ${key}`);
    }
  }

  // 3. description 长度
  if (meta.description && meta.description.length > MAX_DESCRIPTION_LENGTH) {
    errors.push(`description 长度 ${meta.description.length} 超过 ${MAX_DESCRIPTION_LENGTH}`);
  }

  // 4. metadata 完整性
  const metadata = meta.metadata || {};
  for (const key of REQUIRED_METADATA) {
    if (metadata[key] === undefined || metadata[key] === null) {
      // spec-version 和 spec-versions 至少有一个
      if (key === 'spec-version' && metadata['spec-versions']) continue;
      errors.push(`metadata 缺少必填字段: ${key}`);
    }
  }

  // spec-version / spec-versions 至少一个
  if (!metadata['spec-version'] && !metadata['spec-versions']) {
    errors.push('metadata 必须有 spec-version 或 spec-versions');
  }

  // 5. type 取值
  if (metadata.type && !VALID_TYPES.has(metadata.type)) {
    errors.push(`metadata.type "${metadata.type}" 不合法，可选: ${[...VALID_TYPES].join('/')}`);
  }

  // 6. category 取值
  if (metadata.category && !VALID_CATEGORIES.has(metadata.category)) {
    errors.push(`metadata.category "${metadata.category}" 不合法，可选: ${[...VALID_CATEGORIES].join('/')}`);
  }

  // 7. tags 非空数组
  if (metadata.tags !== undefined) {
    if (!Array.isArray(metadata.tags) || metadata.tags.length === 0) {
      errors.push('metadata.tags 必须是非空数组');
    } else if (metadata.tags.length < 3) {
      warnings.push(`metadata.tags 仅 ${metadata.tags.length} 项，建议 ≥ 5`);
    }
  }

  // 8. examples 非空数组 ≥ 3
  if (metadata.examples !== undefined) {
    if (!Array.isArray(metadata.examples) || metadata.examples.length < 3) {
      errors.push('metadata.examples 必须是数组且至少 3 项');
    }
  }

  // 9. related-skills 引用的 name 必须存在
  if (Array.isArray(metadata['related-skills'])) {
    for (const ref of metadata['related-skills']) {
      const cleanRef = ref.replace(/^["']|["']$/g, '');
      if (!allSkillNames.includes(cleanRef)) {
        errors.push(`related-skills 引用了不存在的 SKILL: ${cleanRef}`);
      }
    }
  }

  // 10. related-specs 引用的文件必须存在
  if (Array.isArray(metadata['related-specs'])) {
    for (const ref of metadata['related-specs']) {
      const cleanRef = ref.replace(/^["']|["']$/g, '');
      const refPath = path.join(ROOT, skillName, cleanRef);
      if (!fs.existsSync(refPath)) {
        errors.push(`related-specs 引用了不存在的文件: ${cleanRef}`);
      }
    }
  }

  // 11. 行数检查
  const maxLines = MAX_LINES_OVERRIDES[skillName] || MAX_LINES_DEFAULT;
  if (lines.length > maxLines) {
    errors.push(`总行数 ${lines.length} 超过 ${maxLines}（${skillName} 上限）`);
  }

  // 12. 必填章节（所有 SKILL）
  for (const pattern of REQUIRED_SECTIONS) {
    if (!pattern.test(content)) {
      warnings.push(`缺少建议章节: ${pattern.source.replace(/[\\^]/g, '')}`);
    }
  }

  // domain-spec / workflow / tool 额外章节
  const isMetaSkill = metadata.type === 'meta-skill';
  if (!isMetaSkill) {
    for (const pattern of DOMAIN_ONLY_SECTIONS) {
      if (!pattern.test(content)) {
        warnings.push(`缺少建议章节: ${pattern.source.replace(/[\\^]/g, '')}`);
      }
    }
  }

  // 13. last-reviewed 日期格式
  if (metadata['last-reviewed']) {
    const dateStr = String(metadata['last-reviewed']);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) {
      warnings.push(`last-reviewed 格式应为 YYYY-MM-DD，当前: ${dateStr}`);
    }
  }

  return { skillName, errors, warnings };
}

/** 主流程 */
function main() {
  const args = process.argv.slice(2);
  const ciMode = args.includes('--ci');
  const fixMode = args.includes('--fix');

  const skillFiles = findSkillFiles();
  const allSkillNames = skillFiles.map(s => s.name);

  console.log(`\n📋 校验 ${skillFiles.length} 个 SKILL\n${'─'.repeat(60)}`);

  let totalErrors = 0;
  let totalWarnings = 0;
  const results = [];

  for (const skillFile of skillFiles) {
    const result = validateSkill(skillFile, allSkillNames);
    results.push(result);
    totalErrors += result.errors.length;
    totalWarnings += result.warnings.length;

    const status = result.errors.length === 0
      ? (result.warnings.length === 0 ? '✅ PASS' : '⚠️  WARN')
      : '❌ FAIL';

    console.log(`${status}  ${result.skillName}`);
    for (const err of result.errors) console.log(`       ERROR: ${err}`);
    for (const warn of result.warnings) console.log(`       WARN:  ${warn}`);
  }

  console.log(`\n${'─'.repeat(60)}`);
  console.log(`总计: ${skillFiles.length} 个 SKILL / ${totalErrors} 错误 / ${totalWarnings} 警告\n`);

  if (fixMode) {
    console.log('💡 修复建议:');
    console.log('  - 缺字段：参考 mc-cli/SKILL.md 的 frontmatter 模板');
    console.log('  - 行数超：精简到 ≤ 300，删除冗余模板/示例');
    console.log('  - 引用失效：检查 related-specs 文件名拼写 / related-skills 名称');
    console.log('  - 章节缺失：补 §0/§1/§2/§4（统一模板）');
    console.log('');
  }

  if (ciMode && totalErrors > 0) {
    process.exit(1);
  }
}

main();
