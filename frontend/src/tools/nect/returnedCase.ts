const RETURNED_CASE_KEY = 'dpop-demo-nect-returned-case'

/**
 * The case id Nect sent the browser back with (`/app/?nectCaseId=...`). Kept across the page load
 * that the return itself is: the app reloads, resumes its channel, and only then does this tool's
 * redirect step render and report the case. Owned by this tool module - the app shell only hands
 * the URL parameter over.
 */
export function storeReturnedNectCase(caseId: string): void {
  try {
    localStorage.setItem(RETURNED_CASE_KEY, caseId)
  } catch {
    // Without storage the user just reports nothing - the retry button still gets them through.
  }
}

/** Reads and forgets it: a case is reported at most once. */
export function takeReturnedNectCase(): string | null {
  try {
    const caseId = localStorage.getItem(RETURNED_CASE_KEY)
    localStorage.removeItem(RETURNED_CASE_KEY)
    return caseId
  } catch {
    return null
  }
}
