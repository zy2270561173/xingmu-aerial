<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Button, Empty, Message, Modal, Select, Tag } from 'animal-island-ui-vue';
import { Check, RefreshCw, X } from '@lucide/vue';
import { getUsers, readableError, reviewApproval } from '../api';
import { useAuthStore } from '../stores/auth';
import { useDeviceStore } from '../stores/devices';
import type { Device, User } from '../types';

const store = useDeviceStore();
const auth = useAuthStore();
const dialogOpen = ref(false);
const selected = ref<Device | null>(null);
const approveAction = ref(true);
const saving = ref(false);
const notice = ref('');
const error = ref('');
const users = ref<User[]>([]);
const ownerUserId = ref<number | undefined>();
const ownerOptions = computed(() => users.value.filter((item) => item.active !== false && item.role === 'user')
  .map((item) => ({ label: `${item.username} · ${item.device_count || 0} 台设备`, value: item.id })));

onMounted(async () => {
  await store.refreshApprovals();
  if (auth.user?.role === 'admin') users.value = await getUsers();
});

function ask(device: Device, approve: boolean) {
  selected.value = device;
  approveAction.value = approve;
  ownerUserId.value = device.owner_user_id || undefined;
  dialogOpen.value = true;
}

async function confirm() {
  if (!selected.value) return;
  saving.value = true;
  try {
    await reviewApproval(selected.value.device_id, approveAction.value, undefined, approveAction.value ? ownerUserId.value : undefined);
    dialogOpen.value = false;
    notice.value = approveAction.value ? '设备已批准接入' : '申请已拒绝';
    await Promise.all([store.refreshApprovals(), store.refresh()]);
    window.setTimeout(() => (notice.value = ''), 2200);
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div>
    <div class="toast-stack"><Message v-if="notice" type="success" :content="notice" /><Message v-if="error" type="warning" :content="error" /></div>
    <header class="page-header">
      <div><h1>设备绑定审批</h1><p>扫码绑定后的电视需要在这里确认 · {{ store.approvals.length }} 个待处理</p></div>
      <Button @click="store.refreshApprovals"><RefreshCw :size="17" /> 刷新</Button>
    </header>
    <div v-if="store.approvals.length" class="island-table-wrap">
      <table class="island-table">
        <thead><tr><th>设备</th><th>型号</th><th>地址</th><th>申请时间</th><th>连接</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="device in store.approvals" :key="device.device_id">
            <td><strong>{{ device.name }}</strong><br /><span class="mono muted">{{ device.device_id }}</span></td>
            <td>{{ device.model || '未知' }}</td>
            <td class="mono">{{ device.lan_ip || '未上报' }}</td>
            <td>{{ device.requested_at ? new Date(device.requested_at * 1000).toLocaleString() : '-' }}</td>
            <td><Tag :color="device.online ? 'mint' : 'default'">{{ device.online ? '在线等待' : '离线' }}</Tag></td>
            <td><div class="row-actions"><Button type="primary" size="small" @click="ask(device, true)"><Check :size="15" /> 同意</Button><Button danger size="small" @click="ask(device, false)"><X :size="15" /> 拒绝</Button></div></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="empty-band"><Empty title="没有待审批设备" description="请先用手机 App 扫描电视上的绑定二维码" /></div>
    <Modal v-model:open="dialogOpen" :title="approveAction ? '同意设备接入' : '拒绝设备接入'" :closable="true">
      <p>设备：<strong>{{ selected?.name }}</strong></p>
      <p class="mono muted">{{ selected?.device_id }}</p>
      <div v-if="approveAction && auth.user?.role === 'admin'" class="owner-field">
        <label>归属用户</label>
        <Select v-model="ownerUserId" :options="ownerOptions" placeholder="未选择时归超级管理员" />
      </div>
      <template #footer>
        <Button @click="dialogOpen = false">取消</Button>
        <Button type="primary" :danger="!approveAction" :loading="saving" @click="confirm">确认</Button>
      </template>
    </Modal>
  </div>
</template>

<style scoped lang="less">
.row-actions { display: flex; gap: 7px; }
.owner-field { display: grid; gap: 7px; margin-top: 18px; }
.owner-field label { color: var(--animal-text-color-secondary); font-size: 12px; font-weight: 700; }
</style>
