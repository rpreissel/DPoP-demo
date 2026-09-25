export function idOf(template: string): string
export function pageIdOf(fileName: string): string
export function textsPerPage(srcDir: string): Record<string, string[]>
export function themeProperties(srcDir: string): string[]
export function catalog(srcDir: string): { id: string; template: string; locations: string[] }[]
