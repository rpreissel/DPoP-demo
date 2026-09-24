# English – translation

Translate the source wording (German, written by developers) into the English a member of a German statutory
health insurer reads in its login and registration app. Translate the meaning, not the developer phrasing.

- **Tone**: neutral, polite, plain English; address the reader as "you"; no exclamation marks, no capitals for
  emphasis.
- **Short**: one or two sentences, most important first, include what the user can do next if the source says so.
- **Replace jargon** as the German prompt does: enrollment → sign-in method; account binding → linked device;
  journey → process; loa/acr → security level (placeholder values stay as they are).
- **Fixed terms**: health insurance number (KVNR), activation code (Freischaltcode), online ID (eID), passport,
  EUDI Wallet, TAN, password, email code, QR code, KOBIL, Nect.
- **Factor types** ("Wissen", "Besitz", "Inhärenz"): "knowledge", "possession", "biometrics" (lower case, they are
  inserted mid-sentence).
- **Technical errors** with `{detail}`/`{id}`: a general, understandable sentence, detail in parentheses at the end.
- **Foreign services** (bundles `nect`, `kobil`, `register`): their own short service tone.
- British or American spelling: American.
