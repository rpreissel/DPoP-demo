import type { KcContext } from '../KcContext'
import { Layout } from '../Layout'
import { t } from '../../texts'

/** `orchestrator-select.ftl`: choosing a method, as rows with a chevron. */
export function OrchestratorSelect({ kcContext }: { kcContext: Extract<KcContext, { pageId: 'orchestrator-select.ftl' }> }) {
  const { url, title, description, options, optionLabels, offerRegistration } = kcContext
  const registrationUrl = (url as { registrationUrl?: string }).registrationUrl
  return (
    <Layout
      kcContext={kcContext}
      title={title ?? t('Anmeldemethode wählen')}
      info={
        offerRegistration && registrationUrl ? (
          <span>
            {t('Noch kein Konto?')} <a href={registrationUrl}>{t('Registrieren')}</a>
          </span>
        ) : undefined
      }
    >
      {description && <p className="orc-subtitle">{description}</p>}
      <form id="kc-orchestrator-select-form" action={url.loginAction} method="post">
        <ul className="orc-choices">
          {options.map((option) => (
            <li key={option}>
              <button className="orc-choice" type="submit" name="toolId" value={option}>
                {optionLabels[option] ?? option}
              </button>
            </li>
          ))}
        </ul>
        <div className="orc-actions orc-actions-start">
          <button className="orc-button" type="submit" name="orchestrator_abandon" value="true">
            {t('Abbrechen')}
          </button>
        </div>
      </form>
    </Layout>
  )
}
