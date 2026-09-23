/**
 * The welcome page opens every app in a tab of its own (named targets, see WelcomeApp), so
 * "← Startseite" must not turn the app's tab into a second welcome page - it switches back to the
 * tab the demo started in. That tab carries this name; the welcome page sets it on load.
 */
export const START_WINDOW = 'dpop-demo-start'

export function markAsStartWindow(): void {
  window.name = START_WINDOW
}

/**
 * Brings the start tab to the front. Falls back to navigating this tab when there is no start
 * tab to switch to - the app was opened directly, the start tab was closed, or this IS it.
 */
export function goToStart(): void {
  if (window.name === START_WINDOW) {
    window.location.href = '/'
    return
  }
  // An empty URL addresses the named tab without navigating it - its tab and scroll state stay.
  const start = window.open('', START_WINDOW)
  if (!start) {
    window.location.href = '/'
    return
  }
  let isFreshBlank = false
  try {
    isFreshBlank = start.location.href === 'about:blank'
  } catch {
    // Not readable means some other origin lives there - not our start page either.
    isFreshBlank = true
  }
  if (isFreshBlank) {
    // No start tab existed; window.open just created an empty one. Discard it and go home here.
    start.close()
    window.location.href = '/'
    return
  }
  start.focus()
}
