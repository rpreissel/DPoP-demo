interface AuthenticationSetupViewProps {
  methods: string[]
}

export function AuthenticationSetupView({ methods }: AuthenticationSetupViewProps) {
  return (
    <div className="card">
      <h2>Authentifizierung einrichten</h2>
      <p>Die Identifikation war erfolgreich. Wählen Sie eine Authentifizierungsmethode aus:</p>
      <div className="form-actions" style={{ marginTop: '1rem' }}>
        {methods.map((method) => (
          <button key={method} className="secondary">
            {method.toUpperCase()} einrichten
          </button>
        ))}
      </div>
    </div>
  )
}
