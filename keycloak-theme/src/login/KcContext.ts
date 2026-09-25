import type { ExtendKcContext } from 'keycloakify/login'

/**
 * The orchestrator's own pages - the same page ids and attributes as the FreeMarker theme
 * (docs/ideen/keycloakify-statt-freemarker.md, section 6). Every page also carries `t`, a Java
 * object the FreeMarker templates call; it arrives here empty. What this theme uses instead is
 * `texts`, the same wordings as a plain map (../texts.ts).
 */
export type KcContextExtension = {
  themeName: string
  properties: Record<string, string | undefined>
  /** Only on the orchestrator's own pages, not on Keycloak's. */
  texts?: Record<string, string>
}

export type KcContextExtensionPerPage = {
  'orchestrator-tool.ftl': {
    toolId: string
    title?: string
    hint?: string
    /** One entry per stepData.missingFields, value always "". */
    fields: Record<string, string>
  }
  'orchestrator-confirm.ftl': {
    title?: string
    confirmLabel?: string
    cancelLabel?: string
  }
  /** Nothing of its own - the message is Keycloak's (kcContext.message). */
  'orchestrator-error.ftl': Record<string, unknown>
  'orchestrator-select.ftl': {
    title?: string
    description?: string
    options: string[]
    optionLabels: Record<string, string>
    offerRegistration?: boolean
  }
}

export type KcContext = ExtendKcContext<KcContextExtension, KcContextExtensionPerPage>
