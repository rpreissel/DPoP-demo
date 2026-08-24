/** Shortens long opaque identifiers (UUIDs, thumbprints) for inline display; full value stays available in the debug sidebar. */
export function shorten(value: string | undefined, headLen = 8, tailLen = 6): string {
  if (!value) return '–'
  if (value.length <= headLen + tailLen + 1) return value
  return `${value.slice(0, headLen)}…${value.slice(-tailLen)}`
}
