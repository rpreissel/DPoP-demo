import { useEffect, useRef } from 'react'
import { stepDataOf } from '../../types'
import { attemptError } from '../stepData'
import type { ToolRenderContext } from '../types'
import { reportNectCase, retryNect } from './api'
import { takeReturnedNectCase } from './returnedCase'
import { t } from '../../texts'

/**
 * toolId=ident-nect / step=redirect: sends the user to Nect's jump page, and - once they are back
 * with `?nectCaseId=...` - reports that case without another click. The result never passes
 * through here: the backend redeems it from Nect.
 */
export function NectRedirectStep({ ctx }: { ctx: ToolRenderContext }) {
  const redirect = stepDataOf(ctx.stepData, 'nect-redirect')
  const error = attemptError(ctx)
  const reportedRef = useRef(false)

  useEffect(() => {
    if (!redirect || reportedRef.current) return
    const returned = takeReturnedNectCase()
    if (returned !== redirect.caseId) return
    reportedRef.current = true
    void reportNectCase(ctx, returned)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [redirect?.caseId])

  return (
    <div className="card">
      <h2>{t('Mit Nect identifizieren')}</h2>
      <p>
        {t(
          'Sie wechseln zu Nect und weisen sich dort mit Personalausweis, Reisepass oder EUDI-Wallet aus. Danach kommen Sie ' +
            'automatisch hierher zurück. Eine Zuordnung zu Ihrer Versichertennummer ist ein eigener Schritt danach.',
        )}
      </p>
      {error && <div className="error-card">{error}</div>}
      <div className="form-actions">
        {redirect ? (
          <button type="button" onClick={() => window.location.assign(redirect.jumpUrl)}>
            {t('Weiter zu Nect')}
          </button>
        ) : (
          <button type="button" onClick={() => void retryNect(ctx)}>
            {t('Erneut versuchen')}
          </button>
        )}
      </div>
    </div>
  )
}
