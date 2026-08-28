export interface ToolMeta {
  icon: string
  label: string
  hint: string
}

/**
 * Human-readable presentation for a toolId - the API only ever hands the client an opaque id
 * (docs/05-api.md #2), so this mapping is purely a frontend display concern, never a routing
 * decision (routing.ts still owns that from `next`, unchanged). Shared by SelectMethodView (the
 * choice cards) and SessionStatusView (the plain-language "current step" line) so the two never
 * describe the same toolId differently.
 */
const TOOL_META: Record<string, ToolMeta> = {
  'ident-fsc': { icon: '🪪', label: 'Freischaltcode', hint: 'Versichertennummer, Name und Freischaltcode' },
  'ident-eid': { icon: '🆔', label: 'eID', hint: 'Online-Ausweisfunktion (simuliert)' },
  'enroll-sms': { icon: '📱', label: 'SMS', hint: 'Code an eine Telefonnummer' },
  'auth-sms': { icon: '📱', label: 'SMS', hint: 'Code an die hinterlegte Telefonnummer' },
  'auth-sms-lookup': { icon: '📱', label: 'SMS', hint: 'E-Mail-Adresse + SMS-Code' },
  'enroll-password': { icon: '🔑', label: 'Passwort', hint: 'Eigenes Passwort festlegen' },
  'auth-password': { icon: '🔑', label: 'Passwort', hint: 'Mit dem hinterlegten Passwort' },
  'auth-password-lookup': { icon: '🔑', label: 'Passwort', hint: 'E-Mail-Adresse + Passwort' },
  'enroll-email': { icon: '✉️', label: 'E-Mail', hint: 'Bestätigungscode an eine E-Mail-Adresse' },
  'auth-email': { icon: '✉️', label: 'E-Mail', hint: 'Code an die bestätigte E-Mail-Adresse' },
  'auth-email-lookup': { icon: '✉️', label: 'E-Mail', hint: 'E-Mail-Adresse + Bestätigungscode' },
  'enroll-device': { icon: '📲', label: 'Gerät', hint: 'Geräteeigener Schlüssel + PIN/Biometrie' },
  'auth-device': { icon: '📲', label: 'Gerät', hint: 'Geräteeigener Schlüssel + PIN/Biometrie' },
}

export function metaFor(toolId: string): ToolMeta {
  return TOOL_META[toolId] ?? { icon: '🔐', label: toolId, hint: '' }
}
