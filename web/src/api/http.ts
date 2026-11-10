import axios from 'axios';

const TOKEN_KEY = 'starlight_console_token';

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '',
  timeout: 20_000,
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY);
      window.dispatchEvent(new Event('starlight-auth-expired'));
    }
    return Promise.reject(error);
  },
);

export { TOKEN_KEY };

