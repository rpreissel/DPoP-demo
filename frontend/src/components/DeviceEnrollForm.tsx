import { useState } from 'react'
import { createDeviceProof, getOrCreateDeviceKeyPair } from '../deviceKey.ts'
import { DeviceAccessGate } from './DeviceAccessGate'

interface DeviceEnrollFormProps {
  toolSessionId: string
  toolId: string
  onSubmit: (body: Record<string, unknown>) => void
  error?: string
}

/** enroll-device/enroll step - registers this device's key pair as a new loa2-capable credential. */
export function DeviceEnrollForm({ toolSessionId, toolId, onSubmit, error }: DeviceEnrollFormProps) {
  const [busy, setBusy] = useState(false)

  async function handleConfirm(accessMeans: 'pin' | 'biometric') {
    setBusy(true)
    try {
      const { keyPair } = await getOrCreateDeviceKeyPair()
      const htu = `${window.location.origin}/orchestrator/api/v1/tools/${toolSessionId}/${toolId}`
      const deviceProof = await createDeviceProof(keyPair, 'PATCH', htu, accessMeans)
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
