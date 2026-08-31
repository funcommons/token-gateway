// src/api/course.ts
import { httpGet } from './http'
import type { Course } from '@/types/api'

export const courseApi = {
  getRecommend: () => httpGet<Course[]>('course/recommend'),
}
