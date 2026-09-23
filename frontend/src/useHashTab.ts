import { useEffect, useState } from 'react'

function fromHash<K extends string>(tabs: readonly K[], fallback: K): K {
  const raw = window.location.hash.slice(1)
  return (tabs as readonly string[]).includes(raw) ? (raw as K) : fallback
}

/**
 * The active sub-tab lives in the URL hash (the fallback tab meaning "no hash"), so a reload, a
 * shared link or the browser's own back/forward button all land on the same place. One hook for
 * every page with tabs instead of the same subFromHash/setActiveTab/hashchange trio per page.
 */
export function useHashTab<K extends string>(tabs: readonly K[], fallback: K): [K, (tab: K) => void] {
  const [tab, setTabState] = useState<K>(() => fromHash(tabs, fallback))

  useEffect(() => {
    const onHashChange = () => setTabState(fromHash(tabs, fallback))
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [tabs, fallback])

  function setTab(next: K) {
    setTabState(next)
    const hash = next === fallback ? '' : next
    if (window.location.hash.slice(1) !== hash) window.location.hash = hash
  }

  return [tab, setTab]
}
