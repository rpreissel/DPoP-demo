import { describe, expect, it } from 'vitest'
import { getUIComponent } from './routing'
import type { Next } from './types'

describe('getUIComponent (orchestrator-owned screens only - tool steps are covered by src/tools/registry.test.ts)', () => {
  it('returns null when next is undefined', () => {
    expect(getUIComponent(undefined)).toBeNull()
  })

  it('returns null for a tool-type next - tools render via renderToolStep, not this table', () => {
    const next: Next = { type: 'tool', toolId: 'enroll-sms', step: 'enroll' }
    expect(getUIComponent(next)).toBeNull()
  })

  it('maps a known flow selection to select-method', () => {
    const next: Next = { type: 'orchestrator', context: 'enrollment', step: 'selectMethod' }
    expect(getUIComponent(next)).toBe('select-method')
  })

  it('maps the authenticated flow completion to authentication-completed', () => {
    const next: Next = { type: 'orchestrator', context: 'authentication', step: 'authenticated' }
    expect(getUIComponent(next)).toBe('authentication-completed')
  })

  it('returns null for an unknown orchestrator context/step combination', () => {
    const next: Next = { type: 'orchestrator', context: 'authentication', step: 'not-a-real-step' }
    expect(getUIComponent(next)).toBeNull()
  })

  it('returns null for an orchestrator-type next without context', () => {
    const next = { type: 'orchestrator', step: 'input' } as Next
    expect(getUIComponent(next)).toBeNull()
  })
})
