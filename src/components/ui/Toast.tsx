import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react'

interface ToastItem {
  id: number
  message: string
  actionLabel?: string
  onAction?: () => void
}

const ToastCtx = createContext<(message: string, action?: { label: string; run: () => void }) => void>(() => {})

export function useToast() {
  return useContext(ToastCtx)
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const nextId = useRef(1)

  const show = useCallback((message: string, action?: { label: string; run: () => void }) => {
    const id = nextId.current++
    setItems((xs) => [...xs, { id, message, actionLabel: action?.label, onAction: action?.run }])
    window.setTimeout(() => setItems((xs) => xs.filter((x) => x.id !== id)), action ? 6000 : 3000)
  }, [])

  return (
    <ToastCtx.Provider value={show}>
      {children}
      <div className="pointer-events-none fixed bottom-6 left-1/2 z-50 flex -translate-x-1/2 flex-col items-center gap-2">
        {items.map((t) => (
          <div
            key={t.id}
            className="pointer-events-auto flex items-center gap-3 rounded border border-line bg-ink px-3.5 py-2 text-sm text-white shadow-lg"
          >
            <span>{t.message}</span>
            {t.actionLabel && (
              <button
                className="font-medium underline underline-offset-2"
                onClick={() => {
                  t.onAction?.()
                  setItems((xs) => xs.filter((x) => x.id !== t.id))
                }}
              >
                {t.actionLabel}
              </button>
            )}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}
