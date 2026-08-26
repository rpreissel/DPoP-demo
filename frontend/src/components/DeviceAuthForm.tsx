import { useState } from 'react'
import { createDeviceProof, getOrCreateDeviceKeyPair } from '../deviceKey.ts'
import { DeviceAccessGate } from './DeviceAccessGate'

interface DeviceAuthFormProps {
  toolSessionId: string
  toolId: string
  onSubmit: (body: Record<string, unknown>) => void
  error?: string
}

/** auth-device/auth step - proves possession of the previously enrolled device key. */
export function DeviceAuthForm({ toolSessionId, toolId, onSubmit, error }: DeviceAuthFormProps) {
  const [busy, setBusy] = useState(false)

  async function handleConfirm(userVerification: 'pin' | 'biometric') {
    setBusy(true)
    try {
      const { keyPair } = await getOrCreateDeviceKeyPair()
      const htu = `${window.location.origin}/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`
      const deviceProof = await createDeviceProof(keyPair, 'PATCH', htu, userVerification)
      onSubmit({ deviceProof })
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {error && <div className="hint">{error}</div>}
      <DeviceAccessGate onConfirm={handleConfirm} busy={busy} />
    </>
  )
}
