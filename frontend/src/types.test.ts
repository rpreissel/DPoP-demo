import { describe, expect, it } from 'vitest'
import { confirmPromptOf, stepDataOf, type StepData } from './types'

describe('stepDataOf', () => {
  it('returns the step data when it has the asked-for shape', () => {
    const stepData: StepData = { kind: 'select-method', options: ['auth-sms'], title: { key: 'a1', args: {}, texts: {} } }
    expect(stepDataOf(stepData, 'select-method')?.options).toEqual(['auth-sms'])
  })

  it('returns undefined for any other shape', () => {
    const stepData: StepData = { kind: 'message', message: { key: 'b2', args: {}, texts: {} } }
    expect(stepDataOf(stepData, 'select-method')).toBeUndefined()
  })

  // Der Grund fuer UnknownStepData: Ein neuerer Server darf eine Form liefern, die dieser Build
  // nicht kennt. Sie wird nicht gerendert - aber sie bricht auch nichts.
  it('passes over a shape from a newer backend without breaking', () => {
    const fromTomorrow: StepData = { kind: 'eine-form-von-morgen' }
    expect(stepDataOf(fromTomorrow, 'select-method')).toBeUndefined()
    expect(stepDataOf(fromTomorrow, 'failed-attempt')).toBeUndefined()
  })
})

describe('confirmPromptOf', () => {
  it('narrows a Confirm prompt', () => {
    const ref = (key: string) => ({ key, args: {}, texts: {} })
    const prompt = { kind: 'Confirm', title: ref('sure'), confirmLabel: ref('yes'), cancelLabel: ref('no') }
    expect(confirmPromptOf(prompt)?.confirmLabel.key).toBe('yes')
  })

  it('does not guess at a prompt kind it does not know', () => {
    expect(confirmPromptOf({ kind: 'Choice', title: { key: 'which', args: {}, texts: {} } })).toBeUndefined()
  })
})
