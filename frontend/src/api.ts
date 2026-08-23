import { createDpopProof, type DpopKeyPair } from './dpop'
import type { ChannelResponse, ToolStateResponse } from './types'

/** Carries the server's own error/message (docs/07-betrieb.md #1) instead of a raw fetch string. */
export class ApiError extends Error {
  readonly status: number
  readonly errorCode: string | undefined

  constructor(status: number, errorCode: string | undefined, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.errorCode = errorCode
  }
}

async function call<T>(dpop: DpopKeyPair, method: string, path: string, body?: unknown): Promise<T> {
  const url = `${window.location.origin}${path}`
  const proof = await createDpopProof(dpop.keyPair, method, url)
  const response = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', DPoP: proof },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    const text = await response.text()
    let errorCode: string | undefined
    let message = text || `${method} ${path} failed: ${response.status}`
    try {
      const parsed = JSON.parse(text) as { error?: string; message?: string }
      errorCode = parsed.error
      message = parsed.message ?? message
    } catch {
      // Response body wasn't the documented {error, message} shape - fall back to raw text.
    }
    throw new ApiError(response.status, errorCode, message)
  }
  return (await response.json()) as T
}

export function createChannel(dpop: DpopKeyPair, requiredAcr?: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', '/orchestrator/api/v1/app/channels', requiredAcr ? { requiredAcr } : {})
}

export function getChannel(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'GET', `/orchestrator/api/v1/app/channels/${channelSessionId}`)
}

export function raiseRequiredAcr(dpop: DpopKeyPair, channelSessionId: string, requiredAcr: string): Promise<ChannelResponse> {
  return call(dpop, 'PATCH', `/orchestrator/api/v1/app/channels/${channelSessionId}`, { requiredAcr })
}

/** Abandons the active REGISTRATION/LOGIN/STEP_UP process; the response already offers a fresh start where applicable. */
export function cancelProcess(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/app/channels/${channelSessionId}/cancel`)
}

/** toolId always comes from next.toolId or a chosen stepData.options entry - never constructed by the client. */
export function activateTool(dpop: DpopKeyPair, channelSessionId: string, toolId: string): Promise<ToolStateResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/app/channels/${channelSessionId}/tool-activate/${toolId}`)
}

export function patchTool(
  dpop: DpopKeyPair,
  toolSessionId: string,
  toolId: string,
  body: Record<string, unknown>
): Promise<ToolStateResponse> {
  return call(dpop, 'PATCH', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`, body)
}

export function getTool(dpop: DpopKeyPair, toolSessionId: string, toolId: string): Promise<ToolStateResponse> {
  return call(dpop, 'GET', `/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`)
}
