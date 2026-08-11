interface AuthenticationSetupViewProps {
  methods: string[]
}

export function AuthenticationSetupView({ methods }: AuthenticationSetupViewProps) {
  return (
    <div className="card">
      <h2>Authentication Setup</h2>
      <p>Verfügbare Methoden:</p>
      <ul>
        {methods.map((method) => (
          <li key={method}>{method}</li>
        ))}
      </ul>
    </div>
  )
}
