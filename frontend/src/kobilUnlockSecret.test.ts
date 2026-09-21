import { afterEach, describe, expect, it } from 'vitest'
import { forgetAllUnlockSecrets, loadUnlockSecret, storeUnlockSecret } from './kobilUnlockSecret'

afterEach(() => localStorage.clear())

describe('forgetAllUnlockSecrets', () => {
  it('drops every KOBIL secret this browser holds', () => {
    // Several because a browser can have been through more than one setup; all of them belonged
    // to a binding on this one key, so once that key carries none, none of them is good for
    // anything (tools/kobil/localData.ts calls this when device-link lists no kobil binding).
    storeUnlockSecret('kob-1', 'one')
    storeUnlockSecret('kob-2', 'two')

    forgetAllUnlockSecrets()

    expect(loadUnlockSecret('kob-1')).toBeNull()
    expect(loadUnlockSecret('kob-2')).toBeNull()
  })

  it('leaves everything else in local storage alone', () => {
    localStorage.setItem('dpop-demo-channel', 'keep-me')
    storeUnlockSecret('kob-1', 'one')

    forgetAllUnlockSecrets()

    expect(localStorage.getItem('dpop-demo-channel')).toBe('keep-me')
  })
})
