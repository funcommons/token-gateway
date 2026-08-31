// src/api/announcement.ts
import { httpGet } from './http'
import type { Announcement } from '@/types/api'

export const announcementApi = {
  getList: () => httpGet<Announcement[]>('announcement/list'),
}
