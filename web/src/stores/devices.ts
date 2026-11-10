import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { getApprovals, getDevices } from '../api';
import type { Device, RealtimeMessage } from '../types';

export const useDeviceStore = defineStore('devices', () => {
  const devices = ref<Device[]>([]);
  const approvals = ref<Device[]>([]);
  const loading = ref(false);
  const selectedId = ref(localStorage.getItem('starlight_selected_device') || '');
  const selected = computed(() => devices.value.find((item) => item.device_id === selectedId.value) || null);
  const onlineCount = computed(() => devices.value.filter((item) => item.online).length);

  async function refresh() {
    loading.value = true;
    try {
      devices.value = await getDevices();
      if (!selectedId.value || !devices.value.some((item) => item.device_id === selectedId.value)) {
        selectedId.value = devices.value.find((item) => item.status === 'approved')?.device_id || '';
      }
    } finally {
      loading.value = false;
    }
  }

  async function refreshApprovals() {
    approvals.value = await getApprovals();
  }

  function select(deviceId: string) {
    selectedId.value = deviceId;
    localStorage.setItem('starlight_selected_device', deviceId);
  }

  function remove(deviceId: string) {
    devices.value = devices.value.filter((item) => item.device_id !== deviceId);
    approvals.value = approvals.value.filter((item) => item.device_id !== deviceId);
    if (selectedId.value === deviceId) {
      selectedId.value = devices.value.find((item) => item.status === 'approved')?.device_id || '';
      if (selectedId.value) localStorage.setItem('starlight_selected_device', selectedId.value);
      else localStorage.removeItem('starlight_selected_device');
    }
  }

  function applyRealtime(message: RealtimeMessage) {
    if (message.type === 'device_deleted' && message.device_id) {
      remove(message.device_id);
      return;
    }
    if (message.device) {
      const index = devices.value.findIndex((item) => item.device_id === message.device?.device_id);
      if (index >= 0) devices.value[index] = message.device;
      else devices.value.unshift(message.device);
    }
    if (message.type === 'presence' && message.device_id) {
      const target = devices.value.find((item) => item.device_id === message.device_id);
      if (target) target.online = Boolean(message.online);
    }
  }

  return { devices, approvals, loading, selectedId, selected, onlineCount, refresh, refreshApprovals, select, remove, applyRealtime };
});
