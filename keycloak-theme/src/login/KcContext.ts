import type { ExtendKcContext } from 'keycloakify/login'

/**
 * The orchestrator's own pages - the same page ids and attributes as the FreeMarker theme
 * (docs/ideen/keycloakify-statt-freemarker.md, section 6). Every page also carries `t`, a Java
 * object the FreeMarker templates call; it does not survive the trip into kcContext and is not
 * used here - texts come from ../texts.ts.
 */
export type KcContextExtension = {
  themeName: string
  properties: Record<string, string | undefined>
}

export type KcContextExtensionPerPage = {
  'orchestrator-tool.ftl': {
    toolId: string
    title?: string
    hint?: string
    /** One entry per stepData.missingFields, value always "". */
    fields: Record<string, string>
  }
  'orchestrator-select.ftl': {
    title?: string
    description?: string
    options: string[]
    optionLabels: Record<string, string>
    offerRegistration?: boolean
  }
}

export type KcContext = ExtendKcContext<KcContextExtension, KcContextExtensionPerPage>
