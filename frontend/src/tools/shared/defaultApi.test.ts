import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ patchTool: vi.fn() }))
vi.mock('../../api', () => ({ patchTool: api.patchTool, describeError: (p: string, e: unknown) => `${p}: ${String(e)}` }))
const kcApi = vi.hoisted(() => ({ patchKcTool: vi.fn() }))
vi.mock('../../kcApi', () => ({ patchKcTool: kcApi.patchKcTool }))

import type { DpopKeyPair } from '../../dpop'
import type { ToolRenderContext } from '../types'
import { submitViaPatch } from './defaultApi'

function ctx(overrides: Partial<ToolRenderContext> = {}): ToolRenderContext {
  return {
    step: '',
    toolId: 'enroll-sms',
    toolSessionId: 'ts-1',
    proof: { kind: 'dpop', dpop: {} as DpopKeyPair },
    onResult: vi.fn(),
    onError: vi.fn(),
    ...overrides,
  }
}

describe('submitViaPatch (the one thing every tool that opts into it shares)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('PATCHes the tool session with the given body and reports the response via onResult', async () => {
    const response = { channel: {} }
    api.patchTool.mockResolvedValue(response)
    const c = ctx()
    await submitViaPatch(c, { tan: '123456' })
    expect(api.patchTool).toHaveBeenCalledWith((c.proof as { kind: 'dpop'; dpop: DpopKeyPair }).dpop, 'ts-1', 'enroll-sms', { tan: '123456' })
    expect(c.onResult).toHaveBeenCalledWith(response)
  })

  it('reports a failed PATCH via onError instead of throwing', async () => {
    api.patchTool.mockRejectedValue(new Error('boom'))
    const c = ctx()
    await submitViaPatch(c, { tan: '123456' })
    expect(c.onError).toHaveBeenCalledWith('Request failed: Error: boom')
  })

  it('does nothing without a toolSessionId - no ToolSession to address yet', async () => {
    const c = ctx({ toolSessionId: undefined })
    await submitViaPatch(c, { tan: '123456' })
    expect(api.patchTool).not.toHaveBeenCalled()
  })

  it('PATCHes via the kc peer-auth endpoint when proof.kind is kc', async () => {
    const response = { channel: {} }
    kcApi.patchKcTool.mockResolvedValue(response)
    const anchor = { kcAuthSessionId: 'kc-auth-1' }
    const key = {} as never
    const c = ctx({ proof: { kind: 'kc', key, anchor } })
    await submitViaPatch(c, { tan: '123456' })
    expect(kcApi.patchKcTool).toHaveBeenCalledWith(key, anchor, 'ts-1', 'enroll-sms', { tan: '123456' })
    expect(c.onResult).toHaveBeenCalledWith(response)
    expect(api.patchTool).not.toHaveBeenCalled()
  })
})
