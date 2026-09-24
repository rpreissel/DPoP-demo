import { Fragment, type ReactNode } from 'react'
import { wordingOf } from './texts'

/**
 * A frontend text whose placeholders are markup: `<Tx text="Ihr Code lautet: {code}" code={<strong>{c}</strong>} />`.
 * One sentence, one template - instead of a sentence torn apart by `<strong>` that no language could
 * reorder. Resolved like `t()` (docs/adr/ADR-033); `text` must be a string literal.
 */
export function Tx({ text, ...values }: { text: string } & Record<string, ReactNode>) {
  const parts = wordingOf(text).split(/\{([A-Za-z][A-Za-z0-9_]*)\}/)
  return (
    <>
      {parts.map((part, i) =>
        i % 2 === 0 ? part : <Fragment key={i}>{part in values ? values[part] : `{${part}}`}</Fragment>,
      )}
    </>
  )
}
