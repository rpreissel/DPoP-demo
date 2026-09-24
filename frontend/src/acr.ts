/** The assurance levels in ascending order - as `tool_spi.AcrLevel` defines them. */
const ACR_ORDER = ['loa1', 'loa2', 'loa3']

/**
 * Whether [current] lies below [target]. An unknown or missing level counts as below: offering a
 * step-up once too often is harmless, hiding it from someone who needs it is not.
 */
export function isBelowAcr(current: string | undefined, target: string): boolean {
  const have = current === undefined ? -1 : ACR_ORDER.indexOf(current)
  return have < ACR_ORDER.indexOf(target)
}
