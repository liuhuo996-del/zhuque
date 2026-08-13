import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AppShell } from '@/components/layout/AppShell'
import { ToastProvider } from '@/components/ui/Toast'
import { Overview } from '@/pages/Overview'
import { Intake } from '@/pages/Intake'
import { Catalog } from '@/pages/Catalog'
import { Clusters } from '@/pages/Clusters'
import { Graphs } from '@/pages/Graphs'
import { Packs } from '@/pages/Packs'
import { PackDetail } from '@/pages/PackDetail'
import { Registry } from '@/pages/Registry'
import { Settings } from '@/pages/Settings'

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 10_000, retry: false } } })

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<AppShell />}>
              <Route index element={<Overview />} />
              <Route path="intake" element={<Intake />} />
              <Route path="catalog" element={<Catalog />} />
              <Route path="graphs" element={<Graphs />} />
              <Route path="clusters" element={<Clusters />} />
              <Route path="packs" element={<Packs />} />
              <Route path="packs/:id" element={<PackDetail />} />
              <Route path="registry" element={<Registry />} />
              <Route path="settings" element={<Settings />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </QueryClientProvider>
  )
}
