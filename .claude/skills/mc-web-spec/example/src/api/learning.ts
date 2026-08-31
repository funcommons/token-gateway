// src/api/learning.ts
import { httpGet } from './http'
import type { LearningStat, TimelineItem } from '@/types/api'

export const learningApi = {
  getStats: () => httpGet<LearningStat[]>('learning/stats'),
  getTimeline: () => httpGet<TimelineItem[]>('learning/timeline'),
}
