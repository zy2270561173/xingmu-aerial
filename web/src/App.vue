<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Badge, Button } from 'animal-island-ui-vue';
import { BadgeDollarSign, CheckCheck, LogOut, Monitor, RadioTower, Settings, SlidersHorizontal, Users } from '@lucide/vue';
import { useAuthStore } from './stores/auth';
import { useDeviceStore } from './stores/devices';
import { ControlSocket } from './ws/control';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const devices = useDeviceStore();
let socket: ControlSocket | undefined;

const showShell = computed(() => route.name !== 'login');
const nav = computed(() => [
  { name: 'devices', label: '设备', icon: Monitor, show: true },
  { name: 'approvals', label: '审批设备', icon: CheckCheck, show: true },
  { name: 'users', label: '账号', icon: Users, show: auth.user?.role === 'admin' },
  { name: 'sales', label: '会员销售', icon: BadgeDollarSign, show: auth.user?.role === 'admin' },
  { name: 'remote', label: '遥控', icon: SlidersHorizontal, show: true },
  { name: 'settings', label: '设置', icon: Settings, show: auth.user?.role === 'admin' },
]);

function logout() {
  auth.logout();
  socket?.close();
  void router.replace({ name: 'login' });
}

onMounted(async () => {
  window.addEventListener('starlight-auth-expired', logout);
  if (auth.token && !auth.user) await auth.restore();
  if (auth.user) {
    await devices.refresh();
    await devices.refreshApprovals();
    if (auth.user.role === 'admin') {
      socket = new ControlSocket('*', (message) => {
        devices.applyRealtime(message);
        if (message.type === 'approval_requested' || message.type === 'approval_result') {
          void devices.refreshApprovals();
          void devices.refresh();
        }
      });
      socket.connect();
    }
  }
});

onBeforeUnmount(() => {
  socket?.close();
  window.removeEventListener('starlight-auth-expired', logout);
});
</script>

<template>
  <RouterView v-if="!showShell" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark"><RadioTower :size="24" /></span>
        <div><strong>星投屏</strong><small>控制中心</small></div>
      </div>
      <nav class="nav-list" aria-label="主导航">
        <RouterLink
          v-for="item in nav.filter((entry) => entry.show)"
          :key="item.name"
          :to="{ name: item.name }"
          class="nav-item"
        >
          <component :is="item.icon" :size="19" />
          <span>{{ item.label }}</span>
          <Badge v-if="item.name === 'approvals' && devices.approvals.length" :count="devices.approvals.length" />
        </RouterLink>
      </nav>
      <div class="sidebar-footer">
        <div class="account"><span>{{ auth.user?.username }}</span><small>{{ auth.user?.role === 'admin' ? '超级管理员' : '普通用户' }}</small></div>
        <Button type="text" title="退出登录" aria-label="退出登录" @click="logout"><LogOut :size="18" /></Button>
      </div>
    </aside>
    <main class="workspace">
      <header class="topbar">
        <div class="system-state"><span class="status-indicator" :class="{ active: devices.onlineCount > 0 }" />{{ devices.onlineCount }} 台在线</div>
        <div class="scope-label">信令中心 · 媒体不经服务器</div>
      </header>
      <div class="route-content"><RouterView /></div>
    </main>
  </div>
</template>
