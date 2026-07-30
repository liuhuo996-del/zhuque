import { cn } from '@/lib/utils'

/**
 * 朱雀标志：四笔焰羽构成的升腾火鸟——中焰为身首，两侧新月为翼，尾焰左摆。
 * 应用内默认 ink 单色（设计论点：界面上的颜色只属于判定）；
 * vermilion 变体留给登录页 / 关于页等品牌场合。
 */
export function LogoMark({ size = 24, variant = 'ink', className }: {
  size?: number
  variant?: 'ink' | 'vermilion'
  className?: string
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      aria-hidden
      className={cn(variant === 'ink' ? 'text-ink' : 'text-[#C0342B]', className)}
      fill="currentColor"
    >
      <path d="M24 3.5c4.4 5.9 5.6 13.4 0 23.4-5.6-10-4.4-17.5 0-23.4Z" />
      <path d="M5.5 10c8.2 2.5 14 8.4 16.1 17.9C12 26.4 6.3 19.2 5.5 10Z" />
      <path d="M42.5 10c-8.2 2.5-14 8.4-16.1 17.9C36 26.4 41.7 19.2 42.5 10Z" />
      <path d="M24 29.5c3 4.8 2.5 9.9-2.7 14.5-1.1-5.6-.2-10.4 2.7-14.5Z" />
    </svg>
  )
}

export function LogoLockup() {
  return (
    <div className="flex items-center gap-2.5 px-4 h-14 border-b border-line">
      <LogoMark size={26} />
      <div className="leading-none">
        <div className="text-[15px] font-semibold tracking-wide">朱雀</div>
        <div className="text-[10px] text-ink-faint mt-1 tracking-[0.08em]">能力发布控制面</div>
      </div>
    </div>
  )
}
