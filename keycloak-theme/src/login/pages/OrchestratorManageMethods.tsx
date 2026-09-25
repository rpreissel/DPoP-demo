import type { PageContext } from '../KcContext'
import { Layout } from '../Layout'
import { t } from '../../texts'

/** `orchestrator-manage-methods.ftl`: the account's active sign-in methods, each removable; add one, or done. */
export function OrchestratorManageMethods({ kcContext }: { kcContext: PageContext<'orchestrator-manage-methods.ftl'> }) {
  const { url, methods } = kcContext
  return (
    <Layout kcContext={kcContext} title={t('Anmeldeverfahren verwalten')}>
      {methods.length === 0 ? (
        <p className="orc-hint">{t('Noch keine Anmeldeverfahren aktiv.')}</p>
      ) : (
        <ul className="orc-methods">
          {methods.map((m) => (
            <li key={m.id}>
              <span>{m.label ?? m.method}</span>
              <form action={url.loginAction} method="post">
                <button className="orc-button" type="submit" name="removeMethodInstanceId" value={m.id}>
                  {t('Entfernen')}
                </button>
              </form>
            </li>
          ))}
        </ul>
      )}
      <div className="orc-actions">
        <form action={url.loginAction} method="post">
          <button className="orc-button orc-button-primary" type="submit" name="action" value="add">
            {t('Neues Anmeldeverfahren hinzufügen')}
          </button>
        </form>
        <form action={url.loginAction} method="post">
          <button className="orc-button" type="submit" name="action" value="done">
            {t('Fertig')}
          </button>
        </form>
      </div>
    </Layout>
  )
}
