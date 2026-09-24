# English – translation

Translate the source wording (German, written by developers) into the English a member of a German statutory
health insurer reads in its login and registration app. Translate the meaning, not the developer phrasing.

- **Tone**: neutral, polite, plain English; address the reader as "you"; no exclamation marks, no capitals for
  emphasis.
- **Short**: one or two sentences, most important first, include what the user can do next if the source says so.
- **Never** use internal coinages such as "register person" or "enrollment": say "your insurance record",
  "sign-in method".
- **Fixed demo terms, keep them**: "journey" / "journey log" (not "process"), "prospect" and "insured member"
  (account status without / with assigned insurance data), "pairing code".
- **Replace jargon** as the German prompt does: enrollment → sign-in method; account binding → linked device;
  journey → process; loa/acr → security level (placeholder values stay as they are).
- **Fixed terms**: health insurance number (KVNR), activation code (Freischaltcode), online ID (eID), passport,
  EUDI Wallet, TAN, password, email code, QR code, KOBIL, Nect.
- **Factor types** ("Wissen", "Besitz", "Inhärenz"): "knowledge", "possession", "biometrics" (lower case, they are
  inserted mid-sentence).
- **Technical errors** with `{detail}`/`{id}`: a general, understandable sentence, detail in parentheses at the end.
- **Foreign services** (bundles `nect`, `kobil`, `register`): their own short service tone.
- British or American spelling: American.

## Labels in the frontend and on the login page

- **Buttons**: short, imperative ("Send code", "Continue"), no punctuation.
- **Labels and headings**: noun or short title, no punctuation ("Phone number", "Enter TAN"); sentence case.
- **Input placeholders**: an example or format, not a sentence.
- **Demo notes** (texts mentioning "Demo"): may stay technical, they address testers.
- Fixed terms like "TAN", "SMS", "QR code" stay as they are.
