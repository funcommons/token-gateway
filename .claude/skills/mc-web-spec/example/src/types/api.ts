export interface ApiResponse<T> {
  code: number
  data: T
  message?: string
}

export interface User {
  id: string
  name: string
  avatar: string
  role: string
  department: string
  email: string
}

export interface Course {
  id: number
  title: string
  cover: string
  category: string
  duration: number
  level: 'beginner' | 'intermediate' | 'advanced'
  instructor: string
  students: number
  rating: number
  description: string
  tags: string[]
  progress?: number
}

export interface LearningStat {
  label: string
  value: number
  unit?: string
  trend?: number
  icon: string
}

export interface TimelineItem {
  id: string
  title: string
  description: string
  timestamp: string
  type: 'primary' | 'success' | 'warning' | 'info' | 'danger'
}

export interface Announcement {
  id: string
  title: string
  content: string
  date: string
  important: boolean
}
