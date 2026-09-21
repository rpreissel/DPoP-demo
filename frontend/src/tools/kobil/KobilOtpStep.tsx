import { useEffect, useRef, useState } from 'react'
import { login } from '../../kobilSdk'

interface KobilOtpStepProps {
  tenantId?: string
  kobilUserId?: string
  /**
   * The released PIN. Required, not optional: [KobilAuthStep] shows the unlock screen instead when
   * there is none, so "this step without a PIN" is not a state this component can be in.
   */
  kobilPin: string
  onSubmit: (body: Record<string, unknown>) => void
  onBackToUnlock: () => void
  error?: string
}

/**
 * auth-kobil/otp - runs the (mocked) SDK login with the freshly released PIN and sends back the
 * one-time password KOBIL answers with.
 *
 * It starts on its own: there is nothing here for the user to decide, and no reason to leave a
 * released PIN sitting in a rendered page waiting for a click. The PIN lives in this component's
 * props for the duration of one call and is never stored.
 */
export function KobilOtpStep({
  tenantId,
  kobilUserId,
  kobilPin,
  onSubmit,
  onBackToUnlock,
  error,
}: KobilOtpStepProps) {
  const [sdkError, setSdkError] = useState<string>()
  // One SDK login per released PIN: React may render this twice (StrictMode), and a second login
  // would file a second assertion while the first OTP is still the one being submitted.
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    if (tenantId === undefined || kobilUserId === undefined) return
    started.current = true
    login({ tenantId, userId: kobilUserId }, kobilPin)
      .then(({ otp }) => onSubmit({ otp }))
      .catch((err) => setSdkError(err instanceof Error ? err.message : String(err)))
  }, [tenantId, kobilUserId, kobilPin, onSubmit])

  return (
    <div className="card">
      <h2>Gerät wird geprüft</h2>
      <p>
        KOBIL prüft das Gerät und hinterlegt das Ergebnis. Der Client erhält davon nur eine
        Einmalkennung – die Bestätigung selbst holt sich das Backend direkt bei KOBIL.
      </p>
      {(error || sdkError) && <div className="hint">{error ?? sdkError}</div>}
      {sdkError && (
        <div className="form-actions" style={{ marginTop: '1rem' }}>
          <button type="button" onClick={onBackToUnlock}>
            Erneut entsperren
          </button>
        </div>
      )}
    </div>
  )
}
