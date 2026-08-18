import { http } from './http'
import type {
  LoginRequest,
  RegisterRequest,
  LoginVO,
  CreateApiKeyRequest,
  CreateApiKeyVO,
  ApiKeyVO,
} from '@/types/api'

export const authApi = {
  register: (body: RegisterRequest) => http.post<LoginVO>('/auth/register', body),
  login: (body: LoginRequest) => http.post<LoginVO>('/auth/login', body),

  createApiKey: (body: CreateApiKeyRequest) => http.post<CreateApiKeyVO>('/auth/api-keys', body),
  listApiKeys: () => http.get<ApiKeyVO[]>('/auth/api-keys'),
  revokeApiKey: (id: number) => http.delete<void>(`/auth/api-keys/${id}`),
}
