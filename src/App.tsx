import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AppShell } from '@/components/layout/AppShell'
import { DepartmentProvider } from '@/state/department'
import { ToastProvider } from '@/components/ui/Toast'
import { Overview } from '@/pages/Overview'
import { AgentsList } from '@/pages/AgentsList'
import { AgentNew } from '@/pages/AgentNew'
import { AgentDetail } from '@/pages/AgentDetail'
import { Tools } from '@/pages/Tools'
import { Packs } from '@/pages/Packs'
import { Releases } from '@/pages/Releases'
import { ReleaseDetail } from '@/pages/ReleaseDetail'
import { Settings } from '@/pages/Settings'

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: Infinity, retry: false } },
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <DepartmentProvider>
        <ToastProvider>
          <BrowserRouter>
            <Routes>
              <Route element={<AppShell />}>
                <Route index element={<Overview />} />
                <Route path="agents" element={<AgentsList />} />
                <Route path="agents/new" element={<AgentNew />} />
                <Route path="agents/:id" element={<AgentDetail />} />
                <Route path="tools" element={<Tools />} />
                <Route path="packs" element={<Packs />} />
                <Route path="releases" element={<Releases />} />
                <Route path="releases/:id" element={<ReleaseDetail />} />
                <Route path="settings" element={<Settings />} />
              </Route>
            </Routes>
          </BrowserRouter>
        </ToastProvider>
      </DepartmentProvider>
    </QueryClientProvider>
  )
}
