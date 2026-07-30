import type { Config } from 'tailwindcss'

// P1.0 设计 token。颜色语义：界面上出现颜色 = 系统做出了一个判断。
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        canvas: 'var(--canvas)',
        surface: 'var(--surface)',
        line: 'var(--border)',
        ink: {
          DEFAULT: 'var(--ink)',
          muted: 'var(--ink-muted)',
          faint: 'var(--ink-faint)',
        },
        pass: 'var(--pass)',
        block: 'var(--block)',
        warn: 'var(--warn)',
        'effect-read': 'var(--effect-read)',
        'effect-write': 'var(--effect-write)',
        'effect-delete': 'var(--effect-delete)',
      },
      fontFamily: {
        sans: ['Inter', '"Noto Sans SC"', 'system-ui', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      fontSize: {
        xs: ['12px', '16px'],
        sm: ['13px', '20px'],
        base: ['14px', '22px'],
        lg: ['16px', '24px'],
        xl: ['20px', '28px'],
      },
    },
  },
  plugins: [],
} satisfies Config
