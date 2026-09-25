import { chooseLanguage, language, supportedLanguages, t } from '../texts'

/**
 * DE | EN, like a real app's or website's language setting - the choice applies to the whole demo
 * and, via `ui_locales`, to Keycloak's sign-in pages too (webOidc.ts). The look is the host's.
 */
export function LanguageSwitch({ className, buttonClassName }: { className: string; buttonClassName: string }) {
  const current = language()
  return (
    <div className={className} role="group" aria-label={t('Sprache')}>
      {supportedLanguages.map((lang) => (
        <button
          key={lang}
          className={buttonClassName}
          aria-pressed={lang === current}
          onClick={() => lang !== current && chooseLanguage(lang)}
        >
          {lang.toUpperCase()}
        </button>
      ))}
    </div>
  )
}
