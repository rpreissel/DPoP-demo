// Which of the extension's texts each page of this theme uses (docs/ideen/keycloakify-statt-freemarker.md):
// every `t("...")` template of a page component (src/login/pages/OrchestratorSelect.tsx ->
// orchestrator-select.ftl) and of the local files it imports, read from the parsed source, not by
// regex. The ids go into theme.properties; the extension's WebFormRenderer sends each page only
// its own wordings. A template must be a string literal - anything else is a problem.
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseSync } from 'rolldown/experimental'

/** Same id as the extension's `KcText.idOf`: the first 12 hex digits of the template's SHA-256. */
export function idOf(template) {
  return createHash('sha256').update(template, 'utf8').digest('hex').slice(0, 12)
}

/** OrchestratorSelect.tsx -> orchestrator-select.ftl */
export function pageIdOf(fileName) {
  return fileName.replace(/\.tsx$/, '').replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase() + '.ftl'
}

function lineOf(code, offset) {
  return code.slice(0, offset).split('\n').length
}

function literal(node) {
  if (node?.type === 'Literal' && typeof node.value === 'string') return node.value
  if (node?.type === 'BinaryExpression' && node.operator === '+') {
    const left = literal(node.left)
    const right = literal(node.right)
    return left !== undefined && right !== undefined ? left + right : undefined
  }
  return undefined
}

/** Templates (with their line), local imports and problems of one file. */
export function scanFile(path) {
  const code = readFileSync(path, 'utf8')
  const { program } = parseSync(path, code, { lang: path.endsWith('x') ? 'tsx' : 'ts' })
  const templates = []
  const imports = []
  const problems = []
  const visit = (node) => {
    if (!node || typeof node !== 'object') return
    if (Array.isArray(node)) return node.forEach(visit)
    if (node.type === 'ImportDeclaration' && node.source.value.startsWith('.')) imports.push(node.source.value)
    if (node.type === 'CallExpression' && node.callee?.type === 'Identifier' && node.callee.name === 't') {
      const template = literal(node.arguments[0])
      if (template === undefined) problems.push(`${path}:${lineOf(code, node.start)}: t() needs a string literal as template`)
      else templates.push({ template, line: lineOf(code, node.start) })
    }
    for (const key of Object.keys(node)) if (key !== 'type' && key !== 'start' && key !== 'end') visit(node[key])
  }
  visit(program)
  return { templates, imports, problems }
}

function resolveImport(from, spec) {
  const base = resolve(dirname(from), spec)
  return [base, `${base}.ts`, `${base}.tsx`].find((p) => existsSync(p) && /\.tsx?$/.test(p))
}

/** pageId -> sorted template ids, following each page's local imports (texts.ts defines t, skipped). */
export function textsPerPage(srcDir) {
  const pagesDir = join(srcDir, 'login', 'pages')
  const result = {}
  const problems = []
  for (const name of readdirSync(pagesDir).filter((n) => n.endsWith('.tsx') && !n.endsWith('.test.tsx'))) {
    const seen = new Set()
    const templates = new Set()
    const queue = [join(pagesDir, name)]
    while (queue.length) {
      const file = queue.pop()
      if (seen.has(file) || file === join(srcDir, 'texts.ts')) continue
      seen.add(file)
      const scanned = scanFile(file)
      scanned.templates.forEach(({ template }) => templates.add(template))
      problems.push(...scanned.problems.map((p) => relative(srcDir, p)))
      scanned.imports.map((i) => resolveImport(file, i)).filter(Boolean).forEach((f) => queue.push(f))
    }
    result[pageIdOf(name)] = [...templates].map(idOf).sort()
  }
  if (problems.length) throw new Error(problems.join('\n'))
  return result
}

/** The lines for theme.properties: `orchestratorTexts.<pageId>=id,id,...`. */
export function themeProperties(srcDir) {
  return Object.entries(textsPerPage(srcDir)).map(([pageId, ids]) => `orchestratorTexts.${pageId}=${ids.join(',')}`)
}

function sources(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name)
    if (e.isDirectory()) return sources(path)
    return /\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) ? [path] : []
  })
}

/**
 * Every template of the theme with its locations - for /translate-texts, which words them in the
 * `keycloak` bundle next to the FreeMarker templates (keycloak-extension's KcTextCatalog reads it).
 */
export function catalog(srcDir) {
  const byTemplate = new Map()
  const problems = []
  for (const path of sources(srcDir)) {
    if (path === join(srcDir, 'texts.ts')) continue
    const scanned = scanFile(path)
    problems.push(...scanned.problems.map((p) => relative(srcDir, p)))
    for (const { template, line } of scanned.templates) {
      const entry = byTemplate.get(template) ?? { id: idOf(template), template, locations: [] }
      entry.locations.push(`keycloak-theme/src/${relative(srcDir, path).split(sep).join('/')}:${line}`)
      byTemplate.set(template, entry)
    }
  }
  if (problems.length) throw new Error(problems.join('\n'))
  return [...byTemplate.values()]
}

// `npm run texts:export <out.json>`
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const src = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')
  const out = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'build', 'texts-catalog.json')
  const entries = catalog(src)
  mkdirSync(dirname(out), { recursive: true })
  writeFileSync(out, JSON.stringify(entries, null, 2))
  console.log(`${entries.length} Theme-Texte -> ${out}`)
}
