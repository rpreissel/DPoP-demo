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

/** What WebFormRenderer.toolForm sets on every tool page; the tool's renderer factory adds the rest. */
type ToolPage = {
  toolId: string
  title: string
  hint?: string
}

/** Raw JSON of demo.persons (AbstractWebToolRendererFactory.demoPersonsJson); absent without demo values. */
type WithPersons = { demoPersonsJson?: string }

export type KcContextExtensionPerPage = {
  'tool-password-auth.ftl': ToolPage & { demoPassword?: string }
  'tool-password-enroll.ftl': ToolPage & { demoPassword?: string }
  'tool-password-lookup.ftl': ToolPage & WithPersons & { demoEmail?: string; demoPassword?: string }
  'tool-email-auth.ftl': ToolPage & { demoTan?: string }
  'tool-email-enroll.ftl': ToolPage & WithPersons & { step: string; demoEmail?: string; demoTan?: string }
  'tool-email-lookup.ftl': ToolPage & WithPersons & { step: string; demoEmail?: string; demoTan?: string }
  'tool-sms-auth.ftl': ToolPage & { demoTan?: string }
  'tool-sms-enroll.ftl': ToolPage & { step: string; demoTan?: string }
  'tool-sms-lookup.ftl': ToolPage & WithPersons & { step: string; demoEmail?: string; demoTan?: string }
  'tool-ident-eid.ftl': ToolPage & WithPersons & { step: string }
  'tool-ident-fsc.ftl': ToolPage & WithPersons & { personalienPage: boolean }
  'tool-ident-kvnr.ftl': ToolPage & WithPersons
  'tool-qr-enroll.ftl': ToolPage
  'tool-qr-wait.ftl': ToolPage & { pairingCode: string; verificationCode?: string; deepLink: string; qrDataUri: string }
  'orchestrator-manage-methods.ftl': {
    methods: { id: string; method: string; label?: string }[]
  }
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

/** The kcContext of one page. */
export type PageContext<P extends KcContext['pageId']> = Extract<KcContext, { pageId: P }>
