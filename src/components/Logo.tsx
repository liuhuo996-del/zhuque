import { useId } from 'react'
import { cn } from '@/lib/utils'

export type LogoMarkVariant = 'brand' | 'inverse' | 'ink' | 'vermilion'

export interface LogoMarkProps {
  size?: number
  variant?: LogoMarkVariant
  className?: string
}

/**
 * GateForge 标志。
 *
 * - 拱门：执行前的策略门禁
 * - 熔芯：将 API 和工具铸成可注册能力
 * - 印记：通过门禁后的可审计发布
 *
 * 默认使用品牌版；反色版用于无彩色的深色场景。
 * 其余变体保留旧组件的调用兼容性。
 */
export function LogoMark({ size = 28, variant = 'brand', className }: LogoMarkProps) {
  const id = useId().replace(/:/g, '')
  const gradientId = `gateforge-mark-${id}`
  const isBrand = variant === 'brand'
  const isInverse = variant === 'inverse'

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      aria-hidden="true"
      focusable="false"
      className={cn(
        'shrink-0',
        variant === 'ink' && 'text-ink',
        variant === 'vermilion' && 'text-[#F05A3C]',
        className,
      )}
    >
      {isBrand && (
        <defs>
          <linearGradient id={gradientId} x1="8" y1="5" x2="41" y2="44" gradientUnits="userSpaceOnUse">
            <stop stopColor="#818CF8" />
            <stop offset="0.5" stopColor="#6366F1" />
            <stop offset="1" stopColor="#4F46E5" />
          </linearGradient>
        </defs>
      )}

      <rect
        x="2"
        y="2"
        width="44"
        height="44"
        rx="12"
        fill={isBrand ? `url(#${gradientId})` : isInverse ? 'rgba(255,255,255,0.12)' : 'currentColor'}
      />
      <rect
        x="2.75"
        y="2.75"
        width="42.5"
        height="42.5"
        rx="11.25"
        fill="none"
        stroke="white"
        strokeOpacity={isInverse ? 0.16 : 0.22}
        strokeWidth="1.5"
      />

      {/* 拱门：开放的下沿表示能力经过检查后交给运行时。 */}
      <path
        d="M13.5 35.5V19.25C13.5 13.31 18.18 8.5 24 8.5s10.5 4.81 10.5 10.75V35.5"
        fill="none"
        stroke="white"
        strokeWidth="3.25"
        strokeLinecap="round"
      />

      {/* 熔芯：轮廓简化以保证 24px 尺寸下仍可辨识。 */}
      <path
        d="M24.1 14.4c.35 4.15-3.95 6.65-3.95 11.1 0 2.05 1.02 3.66 2.65 4.65-.08-2.12.9-3.84 2.72-5.55 1.73 1.73 2.68 3.62 2.68 5.55 0 1.77-.76 3.31-2.05 4.35 3.55-.88 5.95-3.92 5.95-7.9 0-5.05-3-9.11-8-12.2Z"
        fill="white"
        fillOpacity="0.96"
      />

      {/* 铸造印记：表示 API 已经过工程化加工与质量检查。 */}
      <circle cx="34.25" cy="34.25" r="6.75" fill="#111A2D" />
      <circle cx="34.25" cy="34.25" r="6" fill="none" stroke="white" strokeOpacity="0.18" />
      <path
        d="m30.9 34.2 2.15 2.1 4.15-4.55"
        fill="none"
        stroke="white"
        strokeWidth="1.85"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export interface LogoLockupProps {
  className?: string
  markClassName?: string
  subtitle?: string
  compact?: boolean
  theme?: 'light' | 'dark'
}

/** 默认匹配 HiMarket 的浅色管理端，并保留深色侧栏变体。 */
export function LogoLockup({
  className,
  markClassName,
  subtitle = 'API 到 MCP 能力包工程化',
  compact = false,
  theme = 'light',
}: LogoLockupProps = {}) {
  return (
    <div
      className={cn(
        'flex h-16 items-center gap-2.5 border-b px-4',
        theme === 'light'
          ? 'border-slate-200 bg-white text-slate-900'
          : 'border-white/10 bg-[#101827] text-white',
        className,
      )}
    >
      <LogoMark size={30} className={markClassName} />
      {!compact && (
        <div className="min-w-0 leading-none">
          <div className="whitespace-nowrap text-[16px] font-semibold tracking-[-0.015em]">
            <span>Gate</span>
            <span className="text-[#4F46E5]">Forge</span>
          </div>
          <div className="mt-1.5 truncate text-[10px] font-medium tracking-[0.1em] text-slate-400">
            {subtitle}
          </div>
        </div>
      )}
    </div>
  )
}
