/** Decodes the payload of a header.payload.signature JWT for display - no verification, the token here is unsecured (alg=none) anyway. */
export function parseJwtPayload(token: string): Record<string, unknown> | null {
  const parts = token.split('.')
  if (parts.length < 2) return null
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
    // atob yields one character per byte (Latin-1); the payload is UTF-8, so decode the bytes as
    // such - otherwise "Musterstraße" shows as "MusterstraÃe".
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
    return JSON.parse(new TextDecoder('utf-8').decode(bytes)) as Record<string, unknown>
  } catch {
    return null
  }
}
