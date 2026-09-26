// The frontend's own user-facing texts (docs/adr/ADR-033): every `t("...")` and `<Tx text="..." />`
// template, read from the parsed source (oxc, the parser Vite/rolldown ship) - not by regex. A
// template must be a string literal (literals joined with + count as one); anything else (a template
// literal with ${}, a variable) is a problem named by file and line. `npm run texts:export <out.json>` writes the
// catalog the backend's TextTranslationsTest and /translate-texts read.
import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseSync } from 'rolldown/experimental'

/** Same id as the backend's `Text.idOf`: slug of the first words, then the first 6 hex digits of the SHA-256. */
export function idOf(template) {
  const hash = createHash('sha256').update(template, 'utf8').digest('hex').slice(0, 6)
  const slug = slugOf(template)
  return slug === '' ? hash : `${slug}-${hash}`
}

const SLUG_MAX = 40

/** `Text.slugOf`, character for character. */
function slugOf(template) {
  const words = template
    .toLowerCase()
    .replaceAll('ä', 'ae').replaceAll('ö', 'oe').replaceAll('ü', 'ue').replaceAll('ß', 'ss')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  if (words.length <= SLUG_MAX) return words
  const cut = words.substring(0, SLUG_MAX + 1).lastIndexOf('-')
  return (cut > 0 ? words.substring(0, cut) : words.substring(0, SLUG_MAX)).replace(/^-+|-+$/g, '')
}

/** The simulated foreign systems' pages keep their own bundle; everything else is this application's. */
export function bundleOf(pathInSrc) {
  const p = pathInSrc.split(sep).join('/')
  if (p.startsWith('entries/nect/')) return 'nect'
  if (p.startsWith('entries/personenverzeichnis/')) return 'personenverzeichnis'
  if (p === 'kobilSdk.ts') return 'kobil'
  return 'app'
}

/** Files that define the markers themselves - they pass templates through, by design. */
const DEFINITIONS = new Set(['texts.ts', 'Tx.tsx'])

function lineOf(code, offset) {
  let line = 1
  for (let i = 0; i < offset && i < code.length; i++) if (code.charCodeAt(i) === 10) line++
  return line
}

function literal(node) {
  if (!node) return undefined
  if (node.type === 'Literal' && typeof node.value === 'string') return node.value
  if (node.type === 'JSXExpressionContainer') return literal(node.expression)
  // 'a ' + 'b' over several lines is one template, as the Kotlin compiler folds it for the backend.
  if (node.type === 'BinaryExpression' && node.operator === '+') {
    const left = literal(node.left)
    const right = literal(node.right)
    return left !== undefined && right !== undefined ? left + right : undefined
  }
  return undefined
}

/** Templates and problems of one source file. */
export function scanSource(file, code) {
  const found = []
  const problems = []
  const { program } = parseSync(file, code, { lang: file.endsWith('x') ? 'tsx' : 'ts' })
  const visit = (node) => {
    if (!node || typeof node !== 'object') return
    if (Array.isArray(node)) return node.forEach(visit)
    if (node.type === 'CallExpression' && node.callee?.type === 'Identifier' && node.callee.name === 't') {
      const template = literal(node.arguments[0])
      if (template === undefined) problems.push(`${file}:${lineOf(code, node.start)}: t() needs a string literal as template (placeholders as {name})`)
      else found.push({ template, line: lineOf(code, node.start) })
    }
    if (node.type === 'JSXOpeningElement' && node.name?.type === 'JSXIdentifier' && node.name.name === 'Tx') {
      const attribute = node.attributes.find((a) => a.type === 'JSXAttribute' && a.name?.name === 'text')
      const template = literal(attribute?.value)
      if (template === undefined) problems.push(`${file}:${lineOf(code, node.start)}: <Tx text> needs a string literal (placeholders as {name})`)
      else found.push({ template, line: lineOf(code, node.start) })
    }
    for (const key of Object.keys(node)) {
      if (key !== 'type' && key !== 'start' && key !== 'end') visit(node[key])
    }
  }
  visit(program)
  return { found, problems }
}

function sources(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name)
    if (e.isDirectory()) return e.name === 'generated' || e.name === 'test' ? [] : sources(path)
    return /\.(ts|tsx)$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) ? [path] : []
  })
}

/** The whole catalog of a source directory: entries grouped by (bundle, template), with every location. */
export function catalogOf(srcDir) {
  const byKey = new Map()
  const problems = []
  for (const path of sources(srcDir)) {
    const inSrc = relative(srcDir, path)
    if (DEFINITIONS.has(inSrc)) continue
    const result = scanSource(inSrc, readFileSync(path, 'utf8'))
    problems.push(...result.problems)
    const bundle = bundleOf(inSrc)
    for (const { template, line } of result.found) {
      const key = `${bundle}\u0000${template}`
      const entry = byKey.get(key) ?? { id: idOf(template), template, bundle, locations: [] }
      entry.locations.push(`frontend/src/${inSrc.split(sep).join('/')}:${line}`)
      byKey.set(key, entry)
    }
  }
  return { entries: [...byKey.values()], problems }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const src = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')
  const out = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'build', 'texts-catalog.json')
  const { entries, problems } = catalogOf(src)
  if (problems.length) {
    console.error(problems.join('\n'))
    process.exit(1)
  }
  mkdirSync(dirname(out), { recursive: true })
  writeFileSync(out, JSON.stringify(entries, null, 2))
  console.log(`${entries.length} Frontend-Texte -> ${out}`)
}
