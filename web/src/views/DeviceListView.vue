<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Button, Card, Empty, Input, Message, Modal, Switch, Tag } from 'animal-island-ui-vue';
import { RefreshCw, SlidersHorizontal, Trash2 } from '@lucide/vue';
import { deleteDevice, readableError, updateDevice } from '../api';
import { useDeviceStore } from '../stores/devices';
import type { Device } from '../types';

const store = useDeviceStore();
const router = useRouter();
const editingId = ref('');
const editingName = ref('');
const notice = ref('');
const error = ref('');
const deleting = ref<Device | null>(null);
const deleteOpen = ref(false);
const deleteBusy = ref(false);

onMounted(() => void store.refresh());

async function toggle(device: Device, enabled: boolean) {
  try {
    Object.assign(device, await updateDevice(device.device_id, { enabled }));
  } catch (reason) {
    error.value = readableError(reason);
  }
}

async function saveName(device: Device) {
  try {
    Object.assign(device, await updateDevice(device.device_id, { name: editingName.value.trim() }));
    editingId.value = '';
    notice.value = '设备名称已保存';
    window.setTimeout(() => (notice.value = ''), 2200);
  } catch (reason) {
    error.value = readableError(reason);
  }
}

function openRemote(device: Device) {
  store.select(device.device_id);
  void router.push({ name: 'remote' });
}

function capabilityLabel(value: unknown, kind: 'control' | 'capture') {
  const labels: Record<string, string> = kind === 'control'
    ? { accessibility: '无障碍全局', adb: 'ADB TCP 全局', root: 'Root 全局', player: '播放器内', unavailable: '未就绪' }
    : { accessibility: '无障碍全屏', adb: 'ADB TCP 全屏', root: 'Root 全屏', app_window: '应用内', unavailable: '未就绪' };
  return labels[String(value || 'unavailable')] || String(value || '未上报');
}

function networkEnvironment(device: Device) {
  const environment = String(device.metadata.network_environment || '');
  const transport = String(device.metadata.network_transport || device.metadata.network_type || '').toLowerCase();
  const transportLabel = transport.includes('wifi') ? 'Wi-Fi' : transport.includes('ethernet') ? '网线' : transport ? transport.toUpperCase() : '未知网络';
  const isLan = environment === 'lan' || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(device.lan_ip);
  return `${isLan ? '局域网' : '网络待识别'} · ${transportLabel}`;
}

async function confirmDelete() {
  if (!deleting.value) return;
  deleteBusy.value = true;
  try {
    await deleteDevice(deleting.value.device_id);
    store.remove(deleting.value.device_id);
    deleting.value = null;
    deleteOpen.value = false;
    notice.value = '旧设备及其历史数据已删除';
    window.setTimeout(() => (notice.value = ''), 2200);
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    deleteBusy.value = false;
  }
}

function askDelete(device: Device) {
  deleting.value = device;
  deleteOpen.value = true;
}
</script>

<template>
  <div>
    <div class="toast-stack"><Message v-if="notice" type="success" :content="notice" /><Message v-if="error" type="warning" :content="error" /></div>
    <header class="page-header">
      <div><h1>设备</h1><p>{{ store.devices.length }} 台已登记，{{ store.onlineCount }} 台在线</p></div>
      <Button :loading="store.loading" title="刷新" @click="store.refresh"><RefreshCw :size="17" /> 刷新</Button>
    </header>
    <div v-if="store.devices.length" class="device-grid">
      <Card v-for="device in store.devices" :key="device.device_id" class="device-card">
        <div class="device-card-head">
          <div>
            <div class="device-title-row"><h2>{{ device.name }}</h2><Tag :color="device.status === 'approved' ? 'mint' : 'yellow'">{{ device.status }}</Tag></div>
            <span class="status-line" :class="{ online: device.online }">{{ device.online ? '在线' : device.power_state === 'assumed_off' ? '疑似关机' : '等待重连' }}</span>
          </div>
          <Switch :model-value="device.enabled" :disabled="device.status !== 'approved'" checked-children="启用" un-checked-children="停用" @update:model-value="(value: boolean) => toggle(device, value)" />
        </div>
        <dl>
          <div><dt>型号</dt><dd>{{ device.model || '未知' }}</dd></div>
          <div><dt>局域网</dt><dd class="mono">{{ device.lan_ip || '未上报' }}</dd></div>
          <div><dt>Agent</dt><dd>{{ device.app_version || '未上报' }}</dd></div>
          <div><dt>网络环境</dt><dd>{{ networkEnvironment(device) }}</dd></div>
          <div><dt>遥控级别</dt><dd>{{ capabilityLabel(device.metadata.control_mode, 'control') }}</dd></div>
          <div><dt>画面采集</dt><dd>{{ capabilityLabel(device.metadata.capture_mode, 'capture') }}</dd></div>
        </dl>
        <div v-if="editingId === device.device_id" class="rename-row">
          <Input v-model="editingName" allow-clear @keyup.enter="saveName(device)" />
          <Button type="primary" @click="saveName(device)">保存</Button>
        </div>
        <div class="card-actions">
          <Button type="primary" :disabled="device.status !== 'approved' || !device.enabled" @click="openRemote(device)"><SlidersHorizontal :size="16" /> 遥控</Button>
          <Button @click="router.push({ name: 'device-detail', params: { deviceId: device.device_id } })">详情</Button>
          <Button type="text" @click="editingId = device.device_id; editingName = device.name">改名</Button>
          <Button class="delete-device" type="default" danger title="删除旧设备" @click="askDelete(device)"><Trash2 :size="16" /> 删除设备</Button>
        </div>
      </Card>
    </div>
    <div v-else class="empty-band"><Empty title="暂无设备" description="电视 Agent 注册后会出现在审批列表" /></div>
    <Modal v-model:open="deleteOpen" title="删除旧设备" :closable="!deleteBusy">
      <p>确定删除 <strong>{{ deleting?.name }}</strong>？设备记录、历史指令和上报日志会一起删除。</p>
      <template #footer><Button :disabled="deleteBusy" @click="deleteOpen = false">取消</Button><Button danger :loading="deleteBusy" @click="confirmDelete">确认删除</Button></template>
    </Modal>
  </div>
</template>

<style scoped lang="less">
.device-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(310px, 1fr)); gap: 14px; }
.device-card { padding: 18px; }
.device-card-head, .device-title-row, .card-actions, .rename-row { display: flex; align-items: center; gap: 9px; }
.device-card-head { align-items: flex-start; justify-content: space-between; }
.device-title-row { flex-wrap: wrap; }
h2 { margin: 0; font-size: 17px; letter-spacing: 0; }
.status-line { margin-top: 7px; color: var(--animal-text-color-secondary); font-size: 12px; }
dl { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; margin: 20px 0; }
dl div { min-width: 0; }
dt { color: var(--animal-text-color-secondary); font-size: 11px; }
dd { margin: 4px 0 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.card-actions { flex-wrap: wrap; }
.rename-row { margin-bottom: 12px; }
.rename-row :deep(.animal-input-wrapper) { flex: 1; }
.delete-device { display: inline-flex !important; margin-left: auto; border: 1px solid #d92d20 !important; background: #fff !important; color: #b42318 !important; opacity: 1 !important; visibility: visible !important; }
@media (max-width: 430px) { .delete-device { width: 100%; margin-left: 0; justify-content: center; } }
</style>
