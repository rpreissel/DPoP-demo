import { describe, expect, it } from 'vitest'
import { getUIComponent } from './routing'
import type { Next } from './types'

describe('getUIComponent', () => {
  it('returns null when next is undefined', () => {
    expect(getUIComponent(undefined)).toBeNull()
  })

  it('maps a known tool step to its form', () => {
    const next: Next = { type: 'tool', toolId: 'enroll-sms', step: 'enroll' }
    expect(getUIComponent(next)).toBe('sms-enroll-form')
  })

  it('maps enroll-device/enroll and auth-device/auth to the device forms', () => {
    expect(getUIComponent({ type: 'tool', toolId: 'enroll-device', step: 'enroll' })).toBe('device-enroll-form')
    expect(getUIComponent({ type: 'tool', toolId: 'auth-device', step: 'auth' })).toBe('device-auth-form')
  })

  it('maps a known flow selection to select-method', () => {
    const next: Next = { type: 'flow', context: 'enrollment', step: 'selectMethod' }
    expect(getUIComponent(next)).toBe('select-method')
  })

  it('maps the authenticated flow completion to authentication-completed', () => {
    const next: Next = { type: 'flow', context: 'authentication', step: 'authenticated' }
    expect(getUIComponent(next)).toBe('authentication-completed')
  })

  it('returns null for an unknown toolId/step combination', () => {
    const next: Next = { type: 'tool', toolId: 'enroll-sms', step: 'not-a-real-step' }
    expect(getUIComponent(next)).toBeNull()
  })

  it('returns null for a tool-type next without toolId', () => {
    const next = { type: 'tool', step: 'input' } as Next
    expect(getUIComponent(next)).toBeNull()
  })

  it('ignores toolSessionId - routing is keyed only by (type, toolId|context, step)', () => {
    const withSession: Next = { type: 'tool', toolId: 'enroll-sms', step: 'enroll', toolSessionId: 'abc-123' }
    const withoutSession: Next = { type: 'tool', toolId: 'enroll-sms', step: 'enroll' }
    expect(getUIComponent(withSession)).toBe(getUIComponent(withoutSession))
  })
})
