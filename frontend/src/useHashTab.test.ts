import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useHashTab } from './useHashTab'

const TABS = ['demo', 'journeytrace'] as const

describe('useHashTab', () => {
  afterEach(() => {
    window.location.hash = ''
  })

  it('opens the tab named in the hash and falls back for an unknown one', () => {
    window.location.hash = 'journeytrace'
    expect(renderHook(() => useHashTab(TABS, 'demo')).result.current[0]).toBe('journeytrace')

    window.location.hash = 'settings'
    expect(renderHook(() => useHashTab(TABS, 'demo')).result.current[0]).toBe('demo')
  })

  it('writes the selected tab to the hash, the fallback as no hash at all', () => {
    const { result } = renderHook(() => useHashTab(TABS, 'demo'))

    act(() => result.current[1]('journeytrace'))
    expect(window.location.hash).toBe('#journeytrace')
    expect(result.current[0]).toBe('journeytrace')

    act(() => result.current[1]('demo'))
    expect(window.location.hash).toBe('')
  })
})
