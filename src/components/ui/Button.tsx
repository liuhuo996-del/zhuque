import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'
type Size = 'sm' | 'md'

const variants: Record<Variant, string> = {
  primary: 'bg-ink text-white hover:bg-ink/90 disabled:bg-ink/35',
  secondary: 'bg-surface border border-line text-ink hover:bg-canvas disabled:text-ink-faint',
  ghost: 'text-ink-muted hover:bg-canvas hover:text-ink disabled:text-ink-faint',
  danger: 'bg-block text-white hover:bg-block/90 disabled:bg-block/40',
}
const sizes: Record<Size, string> = {
  sm: 'h-7 px-2.5 text-xs',
  md: 'h-8 px-3.5 text-sm',
}

export const Button = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant
  size?: Size
}>(({ variant = 'secondary', size = 'md', className, ...props }, ref) => (
  <button
    ref={ref}
    className={cn(
      'inline-flex items-center gap-1.5 rounded font-medium whitespace-nowrap select-none disabled:cursor-not-allowed',
      variants[variant], sizes[size], className,
    )}
    {...props}
  />
))
Button.displayName = 'Button'
