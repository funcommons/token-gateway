// src/api/user.ts
import { httpGet } from './http'
import type { User } from '@/types/api'

export const userApi = {
  getCurrent: () => httpGet<User>('user/current'),
  getTeam: () => httpGet<User[]>('user/team'),
}
