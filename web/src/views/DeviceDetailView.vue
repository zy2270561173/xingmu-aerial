<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Button, Descriptions, Empty, Input, Message, Modal, Select, Tag } from 'animal-island-ui-vue';
import { Camera, Crown, Home, Link2Off, Package as PackageIcon, Play, RefreshCw, Search, Settings, Trash2, UserPlus, UserRoundCog, UserRoundMinus } from '@lucide/vue';
import { addDeviceBinding, deleteDevice, getDevice, getDeviceApps, getDeviceBindings, getEvents, getUsers, readableError, removeDeviceBinding, sendCommand, setPrimaryDeviceBinding, unbindDevice, updateDevice } from '../api';
import type { Device, DeviceBinding, DeviceEvent, InstalledApp, RealtimeMessage, User } from '../types';
import { useAuthStore } from '../stores/auth';
import { useDeviceStore } from '../stores/devices';
import { ControlSocket } from '../ws/control';

const route = useRoute();
const router = useRouter();
const store = useDeviceStore();
const auth = useAuthStore();
const deviceId = String(route.params.deviceId);
const device = ref<Device | null>(null);
const events = ref<DeviceEvent[]>([]);
const apps = ref<InstalledApp[]>([]);
const appSearch = ref('');
const appsLoading = ref(false);
const appCommandPackage = ref('');
const loading = ref(false);
const capturing = ref(false);
const socketConnected = ref(false);
const liveSnapshot = ref('');
const snapshotError = ref('');
const notice = ref('');
const error = ref('');
const deleteOpen = ref(false);
const unbindOpen = ref(false);
const deleteBusy = ref(false);
const users = ref<User[]>([]);
const bindings = ref<DeviceBinding[]>([]);
const bindingUserId = ref<number | undefined>();
const bindingBusy = ref('');
let socket: ControlSocket | undefined;
let liveSnapshotObjectUrl = '';

const latestSnapshot = computed(() => {
  if (liveSnapshot.value) return liveSnapshot.value;
  const event = events.value.find((item) => item.type === 'snapshot');
  const value = event?.data.screen_base64;
  return typeof value === 'string' && value.startsWith('data:image/') ? value : '';
});

const details = computed(() => device.value ? [
  { label: '设备 ID', value: device.value.device_id },
  { label: '系统型号', value: device.value.model || '未知' },
  { label: 'Agent 版本', value: device.value.app_version || '未上报' },
  { label: '局域网 IP', value: device.value.lan_ip || '未上报' },
  { label: '公网 IP', value: device.value.wan_ip || '未上报' },
  { label: '网络环境', value: networkEnvironment(device.value) },
  { label: 'NAT 类型', value: natLabel(device.value.nat_type) },
  { label: '当前页面', value: device.value.current_activity || '未上报' },
  { label: '播放位置', value: `${Math.round(device.value.position_ms / 1000)} 秒` },
] : []);

function networkEnvironment(value: Device) {
  const environment = String(value.metadata.network_environment || '');
  const transport = String(value.metadata.network_transport || value.metadata.network_type || '').toLowerCase();
  const transportLabel = transport.includes('wifi') ? 'Wi-Fi' : transport.includes('ethernet') ? '网线' : transport ? transport.toUpperCase() : '未知网络';
  const isLan = environment === 'lan' || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(value.lan_ip);
  return `${isLan ? '局域网' : '网络待识别'} · ${transportLabel}`;
}

function natLabel(value: string) {
  return !value || value === 'unknown' || value === 'not_tested'
    ? '未执行 STUN / ICE 公网探测'
    : value;
}

const systemDetails = computed(() => {
  const metadata = device.value?.metadata || {};
  return [
    { label: 'Android', value: `${text(metadata.android_release)}（API ${text(metadata.android_api)}）` },
    { label: '固件', value: text(metadata.build_display) },
    { label: 'CPU / ABI', value: cpuLabel(metadata.cpu_cores, metadata.abis) },
    { label: '内存', value: capacityLabel(metadata.memory_available_bytes, metadata.memory_total_bytes) },
    { label: '存储', value: capacityLabel(metadata.storage_available_bytes, metadata.storage_total_bytes) },
    { label: '屏幕', value: screenLabel(metadata.screen_width_px, metadata.screen_height_px, metadata.screen_refresh_hz) },
    { label: '当前应用', value: text(metadata.foreground_package) },
    { label: 'ADB 连接', value: metadata.adb_endpoint ? `${metadata.adb_endpoint} · ${adbStatusLabel(metadata.adb_status)}` : '未上报' },
    { label: '网络', value: metadata.network_type ? `${metadata.network_type} · ${metadata.network_connected ? '已连接' : '未连接'}` : '未上报' },
    { label: '电量', value: Number(metadata.battery_percent) >= 0 ? `${metadata.battery_percent}%${metadata.battery_charging ? ' · 充电中' : ''}` : '未上报' },
    { label: '系统运行', value: formatDuration(metadata.system_uptime_ms) },
  ];
});

const agentDetails = computed(() => Object.entries(device.value?.metadata || {})
  .sort(([left], [right]) => metadataLabel(left).localeCompare(metadataLabel(right), 'zh-CN'))
  .map(([key, value]) => ({ key, label: metadataLabel(key), value: metadataValue(key, value) })));

const controlMode = computed(() => capabilityLabel(device.value?.metadata.control_mode, 'control'));
const captureMode = computed(() => capabilityLabel(device.value?.metadata.capture_mode, 'capture'));
const filteredApps = computed(() => {
  const query = appSearch.value.trim().toLocaleLowerCase();
  if (!query) return apps.value;
  return apps.value.filter((app) =>
    app.label.toLocaleLowerCase().includes(query) || app.package_name.toLocaleLowerCase().includes(query));
});
const boundUserIds = computed(() => new Set(bindings.value.map((item) => item.id)));
const bindingOptions = computed(() => users.value
  .filter((item) => item.active !== false && !boundUserIds.value.has(item.id))
  .map((item) => ({
    label: `${item.username} · ${item.role === 'admin' ? '超级管理员' : `${item.device_count || 0} 台设备`}`,
    value: item.id,
  })));
const ownerName = computed(() => bindings.value.find((item) => item.binding_role === 'owner')?.username
  || users.value.find((item) => item.id === device.value?.owner_user_id)?.username
  || (device.value?.owner_user_id ? `账号 ID ${device.value.owner_user_id}` : '未分配'));

function text(value: unknown) {
  return value === undefined || value === null || value === '' ? '未上报' : String(value);
}

function formatBytes(value: unknown) {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes < 0) return '未上报';
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

function formatDuration(value: unknown) {
  const seconds = Math.floor(Number(value) / 1000);
  if (!Number.isFinite(seconds)) return '未上报';
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  return `${days ? `${days} 天 ` : ''}${hours} 小时`;
}

function cpuLabel(cores: unknown, abis: unknown) {
  const parts = [Number(cores) > 0 ? `${cores} 核` : '', abis ? String(abis) : ''].filter(Boolean);
  return parts.length ? parts.join(' · ') : '未上报';
}

function capacityLabel(available: unknown, total: unknown) {
  if (!Number.isFinite(Number(available)) || !Number.isFinite(Number(total))) return '未上报';
  return `${formatBytes(available)} 可用 / ${formatBytes(total)}`;
}

function screenLabel(width: unknown, height: unknown, refresh: unknown) {
  if (!Number(width) || !Number(height)) return '未上报';
  return `${width} × ${height}${Number(refresh) ? ` · ${refresh} Hz` : ''}`;
}

function capabilityLabel(value: unknown, kind: 'control' | 'capture') {
  const labels: Record<string, string> = kind === 'control'
    ? { accessibility: '无障碍全局控制', adb: 'ADB TCP 全局控制', root: 'Root 全局控制', player: '仅播放器', unavailable: '未就绪' }
    : { accessibility: '无障碍全屏', adb: 'ADB TCP 全屏', root: 'Root 全屏', app_window: '仅星投屏页面', unavailable: '未就绪' };
  return labels[String(value || 'unavailable')] || text(value);
}

function adbStatusLabel(value: unknown) {
  const labels: Record<string, string> = {
    connected: '已连接并授权', authorization_required: '等待电视确认 RSA 授权',
    unreachable: '端口未监听', unsupported: '不是经典 ADB TCP 端口',
    error: '握手失败', untested: '尚未测试',
  };
  return labels[String(value || 'untested')] || text(value);
}

function metadataLabel(key: string) {
  const labels: Record<string, string> = {
    capabilities_version: '能力协议版本', android_api: 'Android API', android_release: 'Android 版本',
    security_patch: '安全补丁', manufacturer: '制造商', brand: '品牌', product: '产品', device: '设备代号',
    hardware: '硬件平台', board: '主板', build_display: '固件版本', build_id: '构建 ID',
    build_incremental: '增量版本', build_type: '构建类型', build_tags: '系统签名标签', build_fingerprint: '系统指纹',
    abis: 'CPU ABI', cpu_cores: 'CPU 核心', locale: '系统语言', timezone: '时区', agent_pid: 'Agent 进程 PID',
    agent_threads: 'Agent 线程数', runtime_max_memory_bytes: 'Agent 最大堆内存', runtime_free_memory_bytes: 'Agent 空闲堆内存',
    memory_total_bytes: '总内存', memory_available_bytes: '可用内存', memory_low: '低内存状态', low_ram_device: '低内存设备',
    storage_total_bytes: '总存储', storage_available_bytes: '可用存储', external_storage_state: '外部存储状态',
    screen_width_px: '屏幕宽度', screen_height_px: '屏幕高度', screen_density_dpi: '屏幕密度',
    screen_orientation: '屏幕方向', screen_refresh_hz: '刷新率', screen_on: '屏幕点亮',
    system_uptime_ms: '系统运行时长', agent_uptime_ms: 'Agent 运行时长', battery_percent: '电量',
    battery_charging: '充电状态', battery_temperature_tenths_c: '电池温度', battery_voltage_mv: '电池电压',
    battery_health: '电池健康代码', battery_technology: '电池技术', power_source: '供电来源代码',
    network_connected: '网络连接', network_type: '网络类型', network_subtype: '网络子类型', network_state: '网络状态',
    network_environment: '网络环境', network_transport: '连接方式', nat_discovery: 'NAT 探测',
    network_roaming: '网络漫游', network_metered: '计费网络', ip_addresses: '本机 IP', wifi_rssi_dbm: 'Wi-Fi 信号',
    wifi_link_speed_mbps: 'Wi-Fi 速率', wifi_ssid: 'Wi-Fi 名称', gateway_ipv4: '网关', dns1_ipv4: 'DNS 1', dns2_ipv4: 'DNS 2',
    accessibility_enabled: '无障碍已启用', accessibility_active: '无障碍运行中', root_binary: 'Root 程序', root_access: 'Root 权限',
    adb_tcp_port: 'ADB 端口', adb_endpoint: 'ADB 地址', adb_status: 'ADB 状态', adb_connected: 'ADB 已连接',
    adb_port_source: 'ADB 来源', adb_requires_manual_enable: 'ADB 需手动开启', control_mode: '遥控方式', capture_mode: '采集方式',
    capture_scope: '采集范围', preview_max_width: '预览最大宽度', preview_max_height: '预览最大高度',
    preview_jpeg_quality: '预览 JPEG 质量', last_capture_duration_ms: '最近截图耗时',
    last_capture_payload_chars: '最近截图传输字符', foreground_package: '前台应用', background_agent: '后台 Agent',
    boot_autostart: '开机自启', agent_heartbeat_seconds: '心跳间隔', agent_reconnect_seconds: '重连间隔',
    global_capture_requires: '全屏采集条件', control_features: '遥控能力', app_inventory: '应用清单',
    remote_app_launch: '远程启动应用', playback_state: '播放状态', player_route: '播放器入口',
    remote_power_off: '远程关机',
    external_player_active: '外部播放器活动', duration_ms: '媒体总时长', volume_percent: '音量',
  };
  return labels[key] || key;
}

function metadataValue(key: string, value: unknown) {
  if (key.endsWith('_bytes')) return formatBytes(value);
  if (key.endsWith('_uptime_ms') || key === 'duration_ms') return formatDuration(value);
  if (key === 'last_capture_duration_ms') return `${Number(value) || 0} ms`;
  if (key === 'battery_temperature_tenths_c' && Number(value) >= 0) return `${(Number(value) / 10).toFixed(1)} °C`;
  if (key === 'battery_voltage_mv' && Number(value) >= 0) return `${value} mV`;
  if (key === 'wifi_rssi_dbm') return `${value} dBm`;
  if (key === 'wifi_link_speed_mbps') return `${value} Mbps`;
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.join(' · ') || '无';
  if (value && typeof value === 'object') return JSON.stringify(value);
  return text(value);
}

async function refresh() {
  loading.value = true;
  try {
    const [nextDevice, nextEvents, nextApps, nextBindings] = await Promise.all([
      getDevice(deviceId), getEvents(deviceId), getDeviceApps(deviceId),
      auth.user?.role === 'admin' ? getDeviceBindings(deviceId) : Promise.resolve([]),
    ]);
    device.value = nextDevice;
    events.value = nextEvents;
    apps.value = nextApps;
    bindings.value = nextBindings;
  } finally {
    loading.value = false;
  }
}

async function addBinding() {
  if (!bindingUserId.value) return;
  bindingBusy.value = 'add';
  error.value = '';
  try {
    if (device.value?.owner_user_id) {
      bindings.value = await addDeviceBinding(deviceId, bindingUserId.value);
    } else {
      device.value = await updateDevice(deviceId, { owner_user_id: bindingUserId.value });
      bindings.value = await getDeviceBindings(deviceId);
    }
    const account = users.value.find((item) => item.id === bindingUserId.value)?.username || '所选账号';
    notice.value = `${account} 已绑定这台电视`;
    bindingUserId.value = undefined;
    await store.refresh();
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    bindingBusy.value = '';
  }
}

async function makePrimary(binding: DeviceBinding) {
  bindingBusy.value = `primary-${binding.id}`;
  error.value = '';
  try {
    bindings.value = await setPrimaryDeviceBinding(deviceId, binding.id);
    if (device.value) device.value.owner_user_id = binding.id;
    notice.value = `${binding.username} 已设为主账号，原主账号保留为共享成员`;
    await store.refresh();
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    bindingBusy.value = '';
  }
}

async function removeBinding(binding: DeviceBinding) {
  bindingBusy.value = `remove-${binding.id}`;
  error.value = '';
  try {
    bindings.value = await removeDeviceBinding(deviceId, binding.id);
    notice.value = `${binding.username} 已解除共享绑定`;
    await store.refresh();
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    bindingBusy.value = '';
  }
}

function realtime(message: RealtimeMessage) {
  if (message.device) device.value = message.device;
  if (message.type === 'device_event' && message.event === 'snapshot') {
    const value = message.data?.screen_base64;
    if (typeof value === 'string' && value.startsWith('data:image/')) {
      liveSnapshot.value = value;
      snapshotError.value = '';
    } else {
      liveSnapshot.value = '';
      snapshotError.value = String(message.data?.capture_error || '电视画面采集失败');
    }
  } else if (message.type === 'device_event' && message.event !== 'heartbeat') {
    void getEvents(deviceId).then((value) => (events.value = value));
    if (message.event === 'app_inventory') {
      void getDeviceApps(deviceId).then((value) => {
        apps.value = value;
        appsLoading.value = false;
      });
    }
  }
  if (message.type === 'command_update' && message.command?.type === 'app'
    && ['completed', 'failed'].includes(message.command.status)) {
    const messageText = String(message.command.result?.message || '应用指令已完成');
    if (message.command.status === 'failed') error.value = messageText;
    else notice.value = messageText;
    appCommandPackage.value = '';
  }
  if (message.type === 'presence' && device.value) device.value.online = Boolean(message.online);
}

async function refreshApps() {
  appsLoading.value = true;
  error.value = '';
  try {
    await sendCommand(deviceId, 'app', { action: 'refresh' });
    notice.value = '已通知电视重新读取应用清单';
    window.setTimeout(async () => {
      try { apps.value = await getDeviceApps(deviceId); } finally { appsLoading.value = false; }
    }, 1500);
  } catch (reason) {
    appsLoading.value = false;
    error.value = readableError(reason);
  }
}

async function launchApp(app: InstalledApp) {
  appCommandPackage.value = app.package_name;
  error.value = '';
  try {
    await sendCommand(deviceId, 'app', { action: 'launch', package_name: app.package_name });
    notice.value = `正在电视上打开 ${app.label}`;
  } catch (reason) {
    appCommandPackage.value = '';
    error.value = readableError(reason);
  }
}

const aerialApp = computed(() => apps.value.find((item) =>
  item.package_name === 'app.orchard.tv' || item.package_name === 'app.orchard.tv.lite'));
const aerialHomeBusy = ref(false);

async function makeAerialHome() {
  const target = aerialApp.value;
  if (!target) return;
  aerialHomeBusy.value = true;
  error.value = '';
  try {
    await sendCommand(deviceId, 'app', {
      action: 'set_home',
      package_name: target.package_name,
      activity_name: target.package_name.endsWith('.lite') ? '.HomeActivity' : '.MainActivity',
    });
    notice.value = '已请求把 Aerial 设为默认桌面';
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    aerialHomeBusy.value = false;
  }
}

async function foregroundTvApp() {
  error.value = '';
  try {
    await sendCommand(deviceId, 'app', { action: 'foreground' });
    notice.value = '已通知电视显示星投屏前台';
  } catch (reason) {
    error.value = readableError(reason);
  }
}

async function capture() {
  capturing.value = true;
  error.value = '';
  try {
    if (!socket?.requestSnapshot()) throw new Error('实时通道尚未连接');
    notice.value = '正在刷新电视画面';
    window.setTimeout(() => (notice.value = ''), 2200);
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    capturing.value = false;
  }
}

async function openTvSettings(target: 'accessibility' | 'wireless_debugging') {
  try {
    await sendCommand(deviceId, 'settings', { target });
    notice.value = target === 'accessibility' ? '已通知电视打开无障碍设置' : '已通知电视打开无线调试或开发者选项';
  } catch (reason) {
    error.value = readableError(reason);
  }
}

async function confirmDelete() {
  deleteBusy.value = true;
  try {
    await deleteDevice(deviceId);
    store.remove(deviceId);
    await router.replace({ name: 'devices' });
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    deleteBusy.value = false;
  }
}

async function confirmUnbind() {
  deleteBusy.value = true;
  try {
    await unbindDevice(deviceId);
    unbindOpen.value = false;
    notice.value = '设备已解除账号绑定';
    await router.replace({ name: 'devices' });
  } catch (reason) { error.value = readableError(reason); }
  finally { deleteBusy.value = false; }
}

function eventData(event: DeviceEvent) {
  const data = { ...event.data };
  if (typeof data.screen_base64 === 'string') data.screen_base64 = '[页面图片]';
  return JSON.stringify(data);
}

onMounted(async () => {
  if (auth.user?.role === 'admin') users.value = await getUsers();
  await refresh();
  socket = new ControlSocket(
    deviceId,
    realtime,
    (connected) => {
      socketConnected.value = connected;
      if (connected) socket?.startPreview(6);
    },
    (jpeg) => {
      const nextUrl = URL.createObjectURL(new Blob([jpeg], { type: 'image/jpeg' }));
      const previousUrl = liveSnapshotObjectUrl;
      liveSnapshotObjectUrl = nextUrl;
      liveSnapshot.value = nextUrl;
      snapshotError.value = '';
      if (previousUrl) URL.revokeObjectURL(previousUrl);
    },
  );
  socket.connect();
});
onBeforeUnmount(() => {
  socket?.stopPreview();
  socket?.close();
  if (liveSnapshotObjectUrl) URL.revokeObjectURL(liveSnapshotObjectUrl);
});
</script>

<template>
  <div>
    <div class="toast-stack"><Message v-if="notice" type="success" :content="notice" /><Message v-if="error" type="warning" :content="error" /></div>
    <header class="page-header">
      <div><h1>{{ device?.name || '设备详情' }}</h1><p class="mono">{{ deviceId }}</p></div>
      <div class="toolbar"><Button :disabled="!device?.online" :loading="capturing" @click="capture"><Camera :size="17" /> 页面快照</Button><Button :loading="loading" @click="refresh"><RefreshCw :size="17" /> 刷新</Button><Button v-if="device?.owner_user_id" @click="unbindOpen = true"><Link2Off :size="17" /> 解绑全部账号</Button><Button danger title="删除旧设备" @click="deleteOpen = true"><Trash2 :size="17" /> 删除</Button></div>
    </header>
    <template v-if="device">
      <div class="status-band">
        <span class="status-line" :class="{ online: device.online }">{{ device.online ? 'Agent 在线' : 'Agent 离线' }}</span>
        <Tag :color="device.status === 'approved' ? 'mint' : 'yellow'">{{ device.status }}</Tag>
        <Tag :color="device.battery_whitelist ? 'blue' : 'default'">{{ device.battery_whitelist ? '已保活' : '未加入白名单' }}</Tag>
        <Tag :color="device.metadata.control_mode === 'unavailable' ? 'yellow' : 'mint'">遥控：{{ controlMode }}</Tag>
        <Tag :color="device.metadata.capture_mode === 'unavailable' ? 'yellow' : 'blue'">采集：{{ captureMode }}</Tag>
        <Tag color="blue">主账号：{{ ownerName }}</Tag>
        <Tag v-if="bindings.length" color="mint">{{ bindings.length }} 个绑定账号</Tag>
        <Tag v-if="!device.online" :color="device.power_state === 'assumed_off' ? 'yellow' : 'blue'">{{ device.power_state === 'assumed_off' ? '连续 5 次无心跳，疑似关机' : '等待 Agent 重连' }}</Tag>
      </div>
      <section v-if="auth.user?.role === 'admin'" class="account-bindings">
        <div class="binding-heading">
          <div><h2 class="section-title">绑定账号</h2><p>一台电视可由多个账号查看和控制；共享成员使用各自的会员权益。</p></div>
          <div class="binding-add">
            <Select v-model="bindingUserId" :options="bindingOptions" placeholder="选择要绑定的账号" />
            <Button type="primary" :loading="bindingBusy === 'add'" :disabled="!bindingUserId || Boolean(bindingBusy)" @click="addBinding"><UserPlus :size="16" /> 添加账号</Button>
          </div>
        </div>
        <div v-if="bindings.length" class="binding-list">
          <div v-for="binding in bindings" :key="binding.id" class="binding-row">
            <span class="binding-avatar"><img v-if="binding.avatar_url" :src="binding.avatar_url" :alt="binding.username" /><span v-else>{{ (binding.nickname || binding.username).slice(0, 1).toUpperCase() }}</span></span>
            <span class="binding-identity"><strong>{{ binding.nickname || binding.username }}</strong><small>{{ binding.username }}<template v-if="binding.phone"> · {{ binding.phone }}</template></small></span>
            <span class="binding-tags"><Tag :color="binding.binding_role === 'owner' ? 'yellow' : 'blue'">{{ binding.binding_role === 'owner' ? '主账号' : '共享成员' }}</Tag><Tag v-if="binding.vip_active" color="mint">{{ binding.vip_plan_name || 'VIP' }}</Tag><Tag v-if="binding.active === false" color="default">已停用</Tag></span>
            <span class="binding-actions">
              <Button v-if="binding.binding_role === 'member'" :loading="bindingBusy === `primary-${binding.id}`" :disabled="Boolean(bindingBusy)" title="设为电视主账号" @click="makePrimary(binding)"><Crown :size="16" /> 设为主账号</Button>
              <Button v-if="binding.binding_role === 'member'" danger :loading="bindingBusy === `remove-${binding.id}`" :disabled="Boolean(bindingBusy)" title="解除该账号的设备权限" @click="removeBinding(binding)"><UserRoundMinus :size="16" /> 移除</Button>
              <span v-else class="primary-note"><UserRoundCog :size="15" /> 电视端账号与会员资料来源</span>
            </span>
          </div>
        </div>
        <Empty v-else title="尚未绑定账号" description="先为设备选择一个主账号" />
      </section>
      <section class="detail-grid">
        <div><h2 class="section-title">设备信息</h2><Descriptions :items="details" /></div>
        <div>
          <div class="snapshot-head"><h2 class="section-title">电视画面</h2><span class="live-state" :class="{ online: socketConnected }">{{ socketConnected ? '快照预览' : '连接中' }}</span></div>
          <div class="snapshot-frame">
            <img v-if="latestSnapshot" class="device-snapshot" :src="latestSnapshot" alt="电视当前页面快照" />
            <div v-else class="snapshot-empty">{{ snapshotError || (device.online ? '正在获取电视画面' : '电视当前离线') }}</div>
          </div>
          <div class="media-state"><strong>{{ device.playing_url ? '正在播放' : '空闲' }}</strong><span class="mono">{{ device.playing_url || '没有活动媒体' }}</span></div>
        </div>
      </section>
      <section class="section system-info">
        <h2 class="section-title">系统与能力</h2>
        <Descriptions :items="systemDetails" />
        <div class="settings-actions">
          <Button :disabled="!device.online" @click="openTvSettings('accessibility')"><Settings :size="16" /> 打开电视无障碍设置</Button>
          <Button :disabled="!device.online" @click="openTvSettings('wireless_debugging')"><Settings :size="16" /> 打开无线调试 / 开发者选项</Button>
          <Button type="primary" :disabled="!device.online" @click="foregroundTvApp"><Home :size="16" /> 唤醒星投屏前台</Button>
        </div>
        <p class="permission-note">Android 13 以上新版 Agent 通过系统级方向键控制，无需 Root。若厂商固件隐藏无线调试入口，普通 App 无法绕过系统权限强制开启，只能使用无障碍、厂商工程菜单或允许调试的固件。</p>
      </section>
      <section class="section agent-diagnostics">
        <details>
          <summary>完整 Agent 诊断 · {{ agentDetails.length }} 项</summary>
          <dl class="metadata-grid">
            <div v-for="item in agentDetails" :key="item.key"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></div>
          </dl>
        </details>
      </section>
      <section class="section">
        <h2 class="section-title">Aerial 桌面</h2>
        <p v-if="!aerialApp" class="muted">这台电视还没装 Aerial（<span class="mono">app.orchard.tv</span> / <span class="mono">app.orchard.tv.lite</span>）。用电脑 adb 装好后，这里可以直接打开它，或者把主页键交给它。</p>
        <div v-else class="toolbar">
          <span class="muted">{{ aerialApp.label }} · <span class="mono">{{ aerialApp.package_name }}</span> · v{{ aerialApp.version_name || aerialApp.version_code || '未知' }}</span>
          <Button :disabled="!device.online" :loading="appCommandPackage === aerialApp.package_name" @click="launchApp(aerialApp)"><Play :size="16" /> 打开 Aerial</Button>
          <Button :disabled="!device.online" :loading="aerialHomeBusy" @click="makeAerialHome">设为默认桌面</Button>
        </div>
      </section>
      <section class="section">
        <div class="apps-head">
          <div><h2 class="section-title">电视应用</h2><p>{{ apps.length }} 个已上报应用，系统组件也会显示</p></div>
          <div class="apps-tools">
            <Input v-model="appSearch" class="app-search" allow-clear placeholder="搜索名称或包名"><template #prefix><Search :size="16" /></template></Input>
            <Button :disabled="!device.online" :loading="appsLoading" @click="refreshApps"><RefreshCw :size="16" /> 重新读取</Button>
          </div>
        </div>
        <div v-if="filteredApps.length" class="island-table-wrap apps-scroll">
          <table class="island-table">
            <thead><tr><th>应用</th><th>版本</th><th>类型</th><th>状态</th><th></th></tr></thead>
            <tbody><tr v-for="app in filteredApps" :key="app.package_name">
              <td><div class="app-identity"><span class="app-icon"><img v-if="app.icon_data_uri" :src="app.icon_data_uri" :alt="app.label" loading="lazy" /><PackageIcon v-else :size="22" /></span><span><strong>{{ app.label }}</strong><span class="mono app-package">{{ app.package_name }}</span></span></div></td>
              <td>{{ app.version_name || app.version_code || '未知' }}</td>
              <td><Tag :color="app.system ? 'default' : 'blue'">{{ app.system ? '系统' : '用户' }}</Tag></td>
              <td>{{ app.enabled ? (app.launchable ? '可打开' : '无启动入口') : '已停用' }}</td>
              <td><Button :disabled="!device.online || !app.enabled || !app.launchable" :loading="appCommandPackage === app.package_name" title="在电视上打开" @click="launchApp(app)"><Play :size="16" /> 打开</Button></td>
            </tr></tbody>
          </table>
        </div>
        <Empty v-else title="暂无应用清单" description="电视 Agent 上线后会自动上报，也可以点击重新读取" />
      </section>
      <section class="section">
        <h2 class="section-title">最近上报</h2>
        <div v-if="events.length" class="island-table-wrap event-scroll">
          <table class="island-table"><thead><tr><th>时间</th><th>类型</th><th>数据</th></tr></thead><tbody><tr v-for="event in events" :key="event.id"><td class="nowrap">{{ new Date(event.created_at * 1000).toLocaleString() }}</td><td><Tag color="blue">{{ event.type }}</Tag></td><td class="mono event-data">{{ eventData(event) }}</td></tr></tbody></table>
        </div>
        <Empty v-else title="暂无上报" description="Agent 上报状态后会显示在这里" />
      </section>
    </template>
    <Modal v-model:open="deleteOpen" title="删除旧设备" :closable="!deleteBusy">
      <p>确定删除 <strong>{{ device?.name }}</strong>？设备记录、历史指令和上报日志会一起删除。</p>
      <template #footer><Button :disabled="deleteBusy" @click="deleteOpen = false">取消</Button><Button danger :loading="deleteBusy" @click="confirmDelete">确认删除</Button></template>
    </Modal>
    <Modal v-model:open="unbindOpen" title="解除账号绑定" :closable="!deleteBusy">
      <p>解除 <strong>{{ device?.name }}</strong> 与全部账号的绑定？主账号和共享成员都会失去权限，设备记录保留并回到待绑定状态。</p>
      <template #footer><Button :disabled="deleteBusy" @click="unbindOpen = false">取消</Button><Button danger :loading="deleteBusy" @click="confirmUnbind">确认解绑</Button></template>
    </Modal>
  </div>
</template>

<style scoped lang="less">
.status-band { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 14px 0; border-top: 1px solid #d8dfdb; border-bottom: 1px solid #d8dfdb; }
.account-bindings { padding: 22px 0; border-bottom: 1px solid #d8dfdb; }
.binding-heading { display: flex; align-items: end; justify-content: space-between; gap: 22px; }
.binding-heading h2 { margin: 0; }
.binding-heading p { margin: 4px 0 0; color: var(--animal-text-color-secondary); font-size: 12px; }
.binding-add { width: min(560px, 54%); display: grid; grid-template-columns: minmax(220px, 1fr) auto; gap: 10px; }
.binding-list { margin-top: 16px; border-top: 1px solid #dce2df; }
.binding-row { min-height: 74px; display: grid; grid-template-columns: 42px minmax(180px, 1fr) minmax(180px, auto) minmax(250px, auto); align-items: center; gap: 13px; padding: 12px 0; border-bottom: 1px solid #e3e8e5; }
.binding-avatar { width: 42px; height: 42px; display: grid; place-items: center; overflow: hidden; border-radius: 50%; background: #e8f3ee; color: #287352; font-weight: 700; }
.binding-avatar img { width: 100%; height: 100%; object-fit: cover; }
.binding-identity { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.binding-identity strong, .binding-identity small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.binding-identity small { color: var(--animal-text-color-secondary); }
.binding-tags, .binding-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.primary-note { display: inline-flex; align-items: center; gap: 6px; color: var(--animal-text-color-secondary); font-size: 12px; }
.detail-grid { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(280px, .9fr); gap: 28px; margin-top: 26px; }
.detail-grid > * { min-width: 0; }
.detail-grid :deep([class*='animal-value']),
.system-info :deep([class*='animal-value']) { min-width: 0; overflow-wrap: anywhere; word-break: break-word; }
.media-state { min-height: 150px; display: flex; flex-direction: column; gap: 13px; padding: 18px; border: 1px solid #dbe2de; border-radius: 8px; background: white; }
.media-state span { overflow-wrap: anywhere; color: var(--animal-text-color-secondary); }
.snapshot-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.live-state { color: var(--animal-text-color-secondary); font-size: 12px; }
.live-state::before { content: ''; display: inline-block; width: 7px; height: 7px; margin-right: 6px; border-radius: 50%; background: #9ba6a0; }
.live-state.online::before { background: #278a59; }
.snapshot-frame { width: 100%; aspect-ratio: 16 / 9; margin-bottom: 12px; border: 1px solid #dbe2de; border-radius: 6px; overflow: hidden; background: #171b19; }
.device-snapshot { width: 100%; height: 100%; object-fit: contain; }
.snapshot-empty { height: 100%; display: grid; place-items: center; color: #b7c4be; font-size: 13px; }
.event-data { max-width: 560px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.system-info { max-width: 980px; }
.agent-diagnostics details { border-top: 1px solid #dce2df; border-bottom: 1px solid #dce2df; padding: 14px 0; }
.agent-diagnostics summary { cursor: pointer; font-weight: 700; }
.metadata-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 28px; margin: 14px 0 0; }
.metadata-grid div { min-width: 0; display: grid; grid-template-columns: minmax(110px, .7fr) minmax(0, 1.3fr); gap: 12px; padding: 9px 0; border-top: 1px solid #edf0ee; }
.metadata-grid dt { color: var(--animal-text-color-secondary); font-size: 12px; }
.metadata-grid dd { min-width: 0; margin: 0; overflow-wrap: anywhere; font: 12px/1.5 ui-monospace, monospace; }
.settings-actions { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 14px; }
.permission-note { max-width: 820px; margin: 10px 0 0; color: var(--animal-text-color-secondary); font-size: 12px; line-height: 1.7; }
.event-scroll { max-height: 360px; overflow: auto; border: 1px solid #dce2df; background: white; }
.event-scroll .island-table { border: 0; }
.event-scroll th { position: sticky; top: 0; z-index: 1; }
.apps-head { display: flex; align-items: end; justify-content: space-between; gap: 18px; margin-bottom: 12px; }
.apps-head p { margin: 3px 0 0; color: var(--animal-text-color-secondary); font-size: 12px; }
.apps-tools { display: flex; align-items: center; gap: 9px; }
.app-search { width: min(320px, 42vw); }
.app-identity { min-width: 230px; display: flex; align-items: center; gap: 11px; }
.app-identity > span:last-child { min-width: 0; }
.app-icon { width: 42px; height: 42px; flex: 0 0 42px; display: grid; place-items: center; overflow: hidden; border: 1px solid #dce2df; border-radius: 8px; background: #f3f6f4; color: #6f7e77; }
.app-icon img { width: 100%; height: 100%; padding: 3px; object-fit: contain; }
.app-package { display: block; margin-top: 3px; }
.apps-scroll { max-height: 440px; overflow: auto; border: 1px solid #dce2df; background: white; }
.apps-scroll .island-table { border: 0; }
.apps-scroll th { position: sticky; top: 0; z-index: 1; }
.app-package { max-width: 420px; margin-top: 4px; color: var(--animal-text-color-secondary); font-size: 12px; overflow-wrap: anywhere; }
@media (max-width: 900px) { .detail-grid { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .apps-head { align-items: stretch; flex-direction: column; } .apps-tools { align-items: stretch; flex-direction: column; } .app-search { width: 100%; } }
@media (max-width: 720px) { .metadata-grid { grid-template-columns: 1fr; } }
@media (max-width: 900px) { .binding-heading { align-items: stretch; flex-direction: column; } .binding-add { width: 100%; } .binding-row { grid-template-columns: 42px minmax(0, 1fr); } .binding-tags, .binding-actions { grid-column: 2; justify-content: flex-start; } }
@media (max-width: 560px) { .binding-add { grid-template-columns: 1fr; } .binding-actions { align-items: stretch; flex-direction: column; } }
</style>
