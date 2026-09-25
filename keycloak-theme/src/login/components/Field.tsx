import type { InputHTMLAttributes, ReactNode } from 'react'

/** One labelled input; its id is also its name - the field name the tool expects. */
export function Field({ id, label, hint, ...input }: { id: string; label: string; hint?: ReactNode } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <div className="orc-field">
      <label htmlFor={id}>{label}</label>
      <input id={id} name={id} type="text" autoComplete="off" {...input} />
      {hint && <span className="orc-hint">{hint}</span>}
    </div>
  )
}
