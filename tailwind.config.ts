import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        canvas: 'var(--canvas)',
        surface: 'var(--surface)',
        'surface-subtle': 'var(--surface-subtle)',
        line: 'var(--border)',
        'line-strong': 'var(--border-strong)',
        brand: {
          DEFAULT: 'var(--brand)',
          strong: 'var(--brand-strong)',
          hover: 'var(--brand-hover)',
          tint: 'var(--brand-tint)',
        },
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
      borderRadius: {
        DEFAULT: '6px',
        md: '7px',
        lg: '10px',
      },
      boxShadow: {
        panel: 'var(--shadow-panel)',
      },
    },
  },
  plugins: [],
} satisfies Config
