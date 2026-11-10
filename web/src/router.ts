import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from './stores/auth';
import LoginView from './views/LoginView.vue';
import DeviceListView from './views/DeviceListView.vue';
import ApprovalView from './views/ApprovalView.vue';
import DeviceDetailView from './views/DeviceDetailView.vue';
import RemoteView from './views/RemoteView.vue';
import SettingsView from './views/SettingsView.vue';
import UsersView from './views/UsersView.vue';
import SalesView from './views/SalesView.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
    { path: '/', redirect: '/devices' },
    { path: '/devices', name: 'devices', component: DeviceListView },
    { path: '/devices/:deviceId', name: 'device-detail', component: DeviceDetailView },
    { path: '/approvals', name: 'approvals', component: ApprovalView },
    { path: '/users', name: 'users', component: UsersView, meta: { admin: true } },
    { path: '/sales', name: 'sales', component: SalesView, meta: { admin: true } },
    { path: '/remote', name: 'remote', component: RemoteView },
    { path: '/settings', name: 'settings', component: SettingsView, meta: { admin: true } },
  ],
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (!auth.user && auth.token) await auth.restore();
  if (!to.meta.public && !auth.authenticated) return { name: 'login', query: { redirect: to.fullPath } };
  if (to.meta.admin && auth.user?.role !== 'admin') return { name: 'devices' };
  if (to.name === 'login' && auth.authenticated) return { name: 'devices' };
  return true;
});

export default router;
