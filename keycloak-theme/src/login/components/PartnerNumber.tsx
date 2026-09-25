import { t } from '../../texts'

/** The Partnernummer, folded away: the KVNR comes first, the Partnernummer only counts without one (ADR-34). */
export function PartnerNumber() {
  return (
    <details className="orc-field orc-details">
      <summary>{t('Ich habe keine Versichertennummer')}</summary>
      <label htmlFor="partnernr">{t('Partnernummer')}</label>
      <input type="text" id="partnernr" name="partnernr" placeholder="P000000000" autoComplete="off" />
    </details>
  )
}
