import { useEffect, useState } from 'react'
import { fetchRegistrationOrder, setRegistrationOrder } from '../api.ts'

/**
 * REGISTER's "Enrollment zuerst" experiment (docs/04-orchestrierung.md, `RegisterEnrollFirstStrategy`):
 * a global runtime toggle between the ident-first status quo and the alternative order, for the
 * next brand-new REGISTER journey - a journey already in progress keeps whichever order it started
 * with. No DPoP, behind the admin login like every operator endpoint.
 */
export function AdminRegistrationOrderView() {
  const [enrollFirst, setEnrollFirst] = useState<boolean | null>(null)
  const [error, setError] = useState('')

  function reload() {
    fetchRegistrationOrder()
      .then((state) => setEnrollFirst(state.enrollFirst))
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  useEffect(reload, [])

  async function toggle() {
    if (enrollFirst === null) return
    try {
      setError('')
      await setRegistrationOrder(!enrollFirst)
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <div className="card">
      <h2>Registrierungsreihenfolge</h2>
      <p>
        Experiment: Identifikation zuerst (Status quo) oder erst Enrollment, mit optionaler Identifikation am Ende.
        Wirkt für die nächste neu gestartete REGISTER-Journey - eine bereits laufende behält ihre Reihenfolge.
      </p>
      {error && <p className="error-card">{error}</p>}
      {enrollFirst === null ? (
        !error && <p>Lädt…</p>
      ) : (
        <ul className="status-list">
          <li>
            <span className="label">Reihenfolge</span>
            <span className="value-with-action">
              <span className="value">{enrollFirst ? 'Enrollment zuerst' : 'Identifikation zuerst'}</span>
              <button className="secondary small" onClick={toggle}>
                Umschalten
              </button>
            </span>
          </li>
        </ul>
      )}
    </div>
  )
}
