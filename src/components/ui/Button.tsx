import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'
type Size = 'sm' | 'md'

const variants: Record<Variant, string> = {
  primary: 'border border-brand bg-brand text-white shadow-sm hover:border-brand-strong hover:bg-brand-strong',
  secondary: 'border border-line-strong bg-surface text-ink shadow-sm hover:border-brand hover:text-brand-strong',
  ghost: 'border border-transparent text-ink-muted hover:bg-brand-tint hover:text-brand-strong',
  danger: 'border border-block bg-block text-white shadow-sm hover:bg-block/90',
}
const sizes: Record<Size, string> = {
  sm: 'h-8 px-3 text-xs',
  md: 'h-9 px-4 text-sm',
}

export const Button = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant
  size?: Size
}>(({ variant = 'secondary', size = 'md', className, ...props }, ref) => (
  <button
    ref={ref}
    className={cn(
      'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md font-medium transition-colors duration-150 select-none disabled:cursor-not-allowed disabled:opacity-45',
      variants[variant], sizes[size], className,
    )}
    {...props}
  />
))
Button.displayName = 'Button'
