import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'

// 部门是 v1 的组织根：所有列表默认按当前部门过滤，'all' 为「全部部门」。
const Ctx = createContext<{ deptId: string; setDeptId: (id: string) => void }>({
  deptId: 'all',
  setDeptId: () => {},
})

export function DepartmentProvider({ children }: { children: ReactNode }) {
  const [deptId, setDeptIdState] = useState(() => window.localStorage.getItem('zhuque.department') || 'all')
  const setDeptId = useCallback((id: string) => {
    setDeptIdState(id)
    window.localStorage.setItem('zhuque.department', id)
  }, [])
  return <Ctx.Provider value={{ deptId, setDeptId }}>{children}</Ctx.Provider>
}

export function useDepartment() {
  return useContext(Ctx)
}
