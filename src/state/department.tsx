import { createContext, useContext, useState, type ReactNode } from 'react'

// 部门是 v1 的组织根：所有列表默认按当前部门过滤，'all' 为「全部部门」。
const Ctx = createContext<{ deptId: string; setDeptId: (id: string) => void }>({
  deptId: 'd-cs',
  setDeptId: () => {},
})

export function DepartmentProvider({ children }: { children: ReactNode }) {
  const [deptId, setDeptId] = useState('d-cs')
  return <Ctx.Provider value={{ deptId, setDeptId }}>{children}</Ctx.Provider>
}

export function useDepartment() {
  return useContext(Ctx)
}
