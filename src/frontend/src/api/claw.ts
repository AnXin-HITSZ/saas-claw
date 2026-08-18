import { http } from './http'
import type { Claw, ClawCreateRequest } from '@/types/api'

export const clawApi = {
  list: () => http.get<Claw[]>('/claws'),
  create: (body: ClawCreateRequest) => http.post<Claw>('/claws', body),
  remove: (id: number) => http.delete<void>(`/claws/${id}`),
}
