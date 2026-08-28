import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { DpopKeyPair } from '../dpop'
import { knownToolIds, metaFor, renderToolStep } from './registry'
import type { ToolRenderContext } from './types'

function baseCtx(overrides: Partial<ToolRenderContext>): ToolRenderContext {
  return {
    step: '',
    toolId: '',
    dpop: {} as DpopKeyPair,
    onResult: vi.fn(),
    onError: vi.fn(),
    ...overrides,
  }
}

describe('knownToolIds', () => {
  // Deliberately not a fixed list of ids: knownToolIds comes from import.meta.glob discovering
  // tools/*/index.tsx (src/tools/registry.ts) - a test enumerating every id would need editing on
  // every future tool add/remove, defeating the point of that auto-discovery. These check the
  // invariants that actually matter instead.

  it('is non-empty - registry.ts found at least the tool folders under src/tools', () => {
    expect(knownToolIds.length).toBeGreaterThan(0)
  })

  it('has no duplicate toolIds - a collision would silently drop a module in the by-id lookup', () => {
    expect(new Set(knownToolIds).size).toBe(knownToolIds.length)
  })

  it('every discovered toolId resolves to its own meta, not the unknown-tool fallback', () => {
    for (const toolId of knownToolIds) {
      expect(metaFor(toolId).label).not.toBe(toolId)
    }
  })
})

describe('metaFor', () => {
  it('returns the declared meta for a known toolId', () => {
    expect(metaFor('auth-sms-lookup')).toEqual({ icon: '📱', label: 'SMS', hint: 'E-Mail-Adresse + SMS-Code' })
  })

  it('falls back to a generic meta for an unknown toolId', () => {
    expect(metaFor('not-a-real-tool')).toEqual({ icon: '🔐', label: 'not-a-real-tool', hint: '' })
  })
})

describe('renderToolStep', () => {
  it('renders the SMS enroll form for enroll-sms/enroll', () => {
    render(renderToolStep(baseCtx({ toolId: 'enroll-sms', step: 'enroll' })))
    expect(screen.getByRole('heading', { name: 'SMS als zweiten Faktor einrichten' })).toBeInTheDocument()
  })

  it('renders the TAN form for enroll-sms/tanInput', () => {
    render(renderToolStep(baseCtx({ toolId: 'enroll-sms', step: 'tanInput' })))
    expect(screen.getByRole('heading', { name: 'TAN eingeben' })).toBeInTheDocument()
  })

  it('returns null for an unknown step of a known tool', () => {
    expect(renderToolStep(baseCtx({ toolId: 'enroll-sms', step: 'not-a-real-step' }))).toBeNull()
  })

  it('returns null for an unknown toolId', () => {
    expect(renderToolStep(baseCtx({ toolId: 'not-a-real-tool', step: 'enroll' }))).toBeNull()
  })

  it('withholds device forms until toolSessionId is set (matches the previous activeTool guard)', () => {
    expect(renderToolStep(baseCtx({ toolId: 'enroll-device', step: 'enroll' }))).toBeNull()
    render(renderToolStep(baseCtx({ toolId: 'enroll-device', step: 'enroll', toolSessionId: 'ts-1' })))
    expect(screen.getByRole('heading', { name: 'Gerät benennen' })).toBeInTheDocument()
  })
})
