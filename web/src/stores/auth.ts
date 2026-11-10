import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { getMe, login as loginRequest } from '../api';
import { TOKEN_KEY } from '../api/http';
import type { User } from '../types';

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null);
  const loading = ref(false);
  const token = ref(localStorage.getItem(TOKEN_KEY) || '');
  const authenticated = computed(() => Boolean(token.value && user.value));

  async function login(username: string, password: string) {
    loading.value = true;
    try {
      const result = await loginRequest(username, password);
      token.value = result.access_token;
      user.value = result.user;
      localStorage.setItem(TOKEN_KEY, token.value);
    } finally {
      loading.value = false;
    }
  }

  async function restore() {
    if (!token.value) return false;
    try {
      user.value = await getMe();
      return true;
    } catch {
      logout();
      return false;
    }
  }

  function logout() {
    token.value = '';
    user.value = null;
    localStorage.removeItem(TOKEN_KEY);
  }

  return { user, loading, token, authenticated, login, restore, logout };
});

