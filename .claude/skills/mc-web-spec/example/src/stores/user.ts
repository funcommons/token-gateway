// src/stores/user.ts
import { defineStore } from 'pinia'
import type { User } from '@/types/api'

interface UserState {
  current: User | null
  loading: boolean
}

export const useUserStore = defineStore('user', {
  state: (): UserState => ({
    current: null,
    loading: false,
  }),
  actions: {
    setCurrent(user: User | null) {
      this.current = user
    },
    setLoading(loading: boolean) {
      this.loading = loading
    },
  },
})
