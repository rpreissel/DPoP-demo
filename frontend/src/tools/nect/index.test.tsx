import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DpopKeyPair } from '../../dpop'
import type { ToolRenderContext } from '../types'
import { identNect } from './index'
import { storeReturnedNectCase } from './returnedCase'

vi.mock('./api', () => ({
  reportNectCase: vi.fn(),
  retryNect: vi.fn(),
}))

import { reportNectCase, retryNect } from './api'

const redirect = { kind: 'nect-redirect', jumpUrl: '/nect/?case=case-1', caseId: 'case-1' }

function ctx(overrides: Partial<ToolRenderContext>): ToolRenderContext {
  return {
    step: 'redirect',
    toolId: 'ident-nect',
    toolSessionId: 'tool-session-1',
    proof: { kind: 'dpop', dpop: {} as DpopKeyPair },
    onResult: vi.fn(),
    onError: vi.fn(),
    ...overrides,
  }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  localStorage.clear()
})

describe('ident-nect/redirect', () => {
  it('renders nothing for a step that is not its own', () => {
    expect(identNect.render(ctx({ step: 'card' }))).toBeNull()
  })

  it('reports the case Nect returned with, without another click - once', () => {
    storeReturnedNectCase('case-1')
    const c = ctx({ stepData: redirect as ToolRenderContext['stepData'] })
    render(<>{identNect.render(c)}</>)
    expect(reportNectCase).toHaveBeenCalledWith(c, 'case-1')
    expect(reportNectCase).toHaveBeenCalledTimes(1)
  })

  it('does not report a returned case of another run, and forgets it', () => {
    storeReturnedNectCase('someone-elses-case')
    render(<>{identNect.render(ctx({ stepData: redirect as ToolRenderContext['stepData'] }))}</>)
    expect(reportNectCase).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Weiter zu Nect' })).toBeInTheDocument()
    expect(localStorage.length).toBe(0)
  })

  it('offers a fresh case after a failed attempt', () => {
    const failed = { kind: 'failed-attempt', error: 'Identifizierung bei Nect abgebrochen' }
    render(<>{identNect.render(ctx({ stepData: failed as ToolRenderContext['stepData'] }))}</>)
    expect(screen.getByText('Identifizierung bei Nect abgebrochen')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }))
    expect(retryNect).toHaveBeenCalledTimes(1)
  })
})
