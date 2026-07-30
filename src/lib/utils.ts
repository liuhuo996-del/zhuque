import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatDate(iso: string | null | undefined) {
  if (!iso) return '—'
  return iso.slice(0, 16).replace('T', ' ')
}

export function shortHash(hash: string) {
  const h = hash.replace(/^sha256:/, '')
  return `${h.slice(0, 10)}…`
}

export async function copyText(text: string) {
  await navigator.clipboard.writeText(text)
}
