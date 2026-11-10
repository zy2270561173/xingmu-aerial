<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Button, Empty, Input, Message, Modal, Progress, Select, Tabs, Tag } from 'animal-island-ui-vue';
import {
  ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Cast, Circle, Home, Pause, Play,
  Power, RefreshCw, RotateCcw, Square, Upload, Volume1, Volume2,
} from '@lucide/vue';
import {
  createHostedPlayerSession, createP2PSession, getCommands, readableError, sendCommand, uploadApk,
} from '../api';
import { useDeviceStore } from '../stores/devices';
import type { Command, RealtimeMessage } from '../types';
import { ControlSocket } from '../ws/control';

const store = useDeviceStore();
const activeTab = ref('remote');
const tabs = [
  { key: 'remote', label: '遥控器' },
  { key: 'install', label: '安装 APK' },
  { key: 'p2p', label: '远程通道' },
];
const mediaUrl = ref('');
const commands = ref<Command[]>([]);
const busy = ref('');
const notice = ref('');
const error = ref('');
const uploadProgress = ref(0);
const selectedFile = ref<File | null>(null);
const p2pState = ref<{
  session_id: string;
  selected_mode: string;
  direct_capable: boolean;
  selection_reason: string;
  relay_fallback: boolean;
} | null>(null);
const canvasRef = ref<HTMLCanvasElement>();
const hasPreviewFrame = ref(false);
const snapshotError = ref('');
const screenConnected = ref(false);
const powerOpen = ref(false);
const powerBusy = ref(false);
let socket: ControlSocket | undefined;
let snapshotTimer: number | undefined;
let pendingJpeg: ArrayBuffer | undefined;
let drawingFrame = false;
let lastFrameAt = 0;

async function queueJpeg(value: ArrayBuffer) {
  if (value.byteLength < 4 || value.byteLength > 2_000_000) return;
  pendingJpeg = value;
  lastFrameAt = performance.now();
  if (drawingFrame) return;
  drawingFrame = true;
  try {
    while (pendingJpeg) {
      const frame = pendingJpeg;
      pendingJpeg = undefined;
      try {
        await drawJpeg(frame);
      } catch {
        snapshotError.value = '电视画面解码失败';
      }
    }
  } finally {
    drawingFrame = false;
  }
}

async function drawJpeg(value: ArrayBuffer) {
  const canvas = canvasRef.value;
  if (!canvas) return;
  const blob = new Blob([value], { type: 'image/jpeg' });
  const bitmap = typeof createImageBitmap === 'function' ? await createImageBitmap(blob) : await loadImage(blob);
  try {
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    const width = Math.max(1, Math.round(canvas.clientWidth * ratio));
    const height = Math.max(1, Math.round(canvas.clientHeight * ratio));
    if (canvas.width !== width) canvas.width = width;
    if (canvas.height !== height) canvas.height = height;
    const context = canvas.getContext('2d', { alpha: false });
    if (!context) return;
    context.fillStyle = '#111513';
    context.fillRect(0, 0, width, height);
    const scale = Math.min(width / bitmap.width, height / bitmap.height);
    const drawWidth = bitmap.width * scale;
    const drawHeight = bitmap.height * scale;
    context.drawImage(bitmap, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight);
    hasPreviewFrame.value = true;
    snapshotError.value = '';
  } finally {
    if ('close' in bitmap) bitmap.close();
  }
}

function loadImage(blob: Blob): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(blob);
    const image = new Image();
    image.onload = () => { URL.revokeObjectURL(url); resolve(image); };
    image.onerror = () => { URL.revokeObjectURL(url); reject(new Error('JPEG decode failed')); };
    image.src = url;
  });
}

async function queueDataUri(value: string) {
  try {
    await queueJpeg(await (await fetch(value)).arrayBuffer());
  } catch {
    snapshotError.value = '电视画面解码失败';
  }
}

const options = computed(() => store.devices.filter((item) => item.status === 'approved').map((item) => ({ label: item.name, value: item.device_id })));
const selection = computed<string | number>({
  get: () => store.selectedId,
  set: (value) => store.select(String(value)),
});
const selected = computed(() => store.selected);
const controlMode = computed(() => capabilityLabel(selected.value?.metadata.control_mode, 'control'));
const captureMode = computed(() => capabilityLabel(selected.value?.metadata.capture_mode, 'capture'));
const powerReady = computed(() => selected.value?.online && (
  selected.value.metadata.remote_power_off === true
  || ['adb', 'root'].includes(String(selected.value.metadata.control_mode || ''))
));

function showNotice(value: string) {
  notice.value = value;
  window.setTimeout(() => (notice.value = ''), 2200);
}

function upsertCommand(command: Command) {
  const index = commands.value.findIndex((item) => item.id === command.id);
  if (index >= 0) commands.value[index] = command;
  else commands.value.unshift(command);
}

async function command(type: string, payload: Record<string, unknown>, key: string) {
  if (!selected.value) return;
  busy.value = key;
  error.value = '';
  try {
    const result = await sendCommand(selected.value.device_id, type, payload);
    upsertCommand(result);
    showNotice(result.status === 'delivered' ? '指令已送达 Agent' : '设备离线，指令已排队');
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    busy.value = '';
  }
}

async function sendKey(key: string) {
  await command('key', { key }, key);
}

async function foregroundTvApp() {
  await command('app', { action: 'foreground' }, 'foreground');
}

async function shutdownTv() {
  if (!selected.value) return;
  powerBusy.value = true;
  try {
    await command('power', { action: 'shutdown' }, 'shutdown');
    powerOpen.value = false;
  } finally {
    powerBusy.value = false;
  }
}

async function sendMedia() {
  const url = mediaUrl.value.trim();
  if (!/^https?:\/\//i.test(url)) {
    error.value = '请输入有效的 HTTP 或 HTTPS 媒体地址';
    return;
  }
  await command('media', { action: 'play_url', url }, 'media_url');
}

async function sendHostedMedia() {
  const url = mediaUrl.value.trim();
  if (!/^https?:\/\//i.test(url)) {
    error.value = '请输入有效的 HTTP 或 HTTPS 媒体地址';
    return;
  }
  busy.value = 'media_hosted';
  error.value = '';
  try {
    const mediaType = /\.m3u8(?:$|\?)/i.test(url) ? 'application/vnd.apple.mpegurl' : '';
    const session = await createHostedPlayerSession(url, mediaType);
    const result = await sendCommand(selected.value!.device_id, 'media', {
      action: 'play_hosted',
      player_path: session.player_path,
    });
    upsertCommand(result);
    showNotice('后端 DPlayer 任务已下发');
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    busy.value = '';
  }
}

function chooseFile(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] || null;
}

async function install() {
  if (!selected.value || !selectedFile.value) return;
  busy.value = 'install';
  uploadProgress.value = 0;
  error.value = '';
  try {
    const apk = await uploadApk(selectedFile.value, (value) => (uploadProgress.value = value));
    const result = await sendCommand(selected.value.device_id, 'install', {
      apk_id: apk.id,
      url: apk.url,
      filename: apk.filename,
      sha256: apk.sha256,
    });
    upsertCommand(result);
    showNotice(result.status === 'delivered' ? '安装任务已送达电视' : '安装任务已排队');
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    busy.value = '';
  }
}

async function startP2P(mode: 'auto' | 'relay') {
  if (!selected.value) return;
  busy.value = `p2p_${mode}`;
  try {
    p2pState.value = await createP2PSession(selected.value.device_id, mode);
    showNotice(p2pState.value.selected_mode === 'udp_jpeg'
      ? '已申请认证 P2P JPEG 直连，会优先尝试设备直连'
      : p2pState.value.selected_mode === 'relay'
        ? '当前客户端不具备直连视频能力，继续使用后端控制通道'
        : '直连和控制回退通道均不可用');
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    busy.value = '';
  }
}

async function connectScope() {
  socket?.close();
  commands.value = [];
  hasPreviewFrame.value = false;
  pendingJpeg = undefined;
  snapshotError.value = '';
  if (!store.selectedId) return;
  commands.value = [...new Map((await getCommands(store.selectedId)).map(item => [item.id, item])).values()];
  socket = new ControlSocket(store.selectedId, (message: RealtimeMessage) => {
    if (message.command) upsertCommand(message.command);
    if (message.type === 'device_event' && message.event === 'snapshot') {
      const value = message.data?.screen_base64;
      if (typeof value === 'string' && value.startsWith('data:image/')) {
        void queueDataUri(value);
      } else if (!hasPreviewFrame.value) {
        snapshotError.value = String(message.data?.capture_error || '电视画面采集失败');
      }
    }
    store.applyRealtime(message);
  }, (connected) => {
    screenConnected.value = connected;
    if (connected) socket?.startPreview(10);
  }, (jpeg) => void queueJpeg(jpeg));
  socket.connect();
}

watch(() => store.selectedId, () => void connectScope());
watch(activeTab, (tab) => {
  if (tab === 'remote') socket?.startPreview(10);
  else socket?.stopPreview();
});
onMounted(async () => {
  if (!store.devices.length) await store.refresh();
  await connectScope();
  snapshotTimer = window.setInterval(() => {
    if (activeTab.value === 'remote' && selected.value?.online
      && performance.now() - lastFrameAt > 2500) socket?.startPreview(10);
  }, 1000);
});
onBeforeUnmount(() => {
  if (snapshotTimer) window.clearInterval(snapshotTimer);
  socket?.stopPreview();
  socket?.close();
});

function commandLabel(item: Command) {
  if (item.type === 'key') return `按键 · ${String(item.payload.key || '')}`;
  if (item.type === 'media') return '播放媒体';
  if (item.type === 'install') return `安装 · ${String(item.payload.filename || 'APK')}`;
  if (item.type === 'p2p') return '远程通道';
  return item.type;
}

function commandResult(item: Command) {
  if (!item.result) return '-';
  const message = item.result.message;
  return typeof message === 'string' && message ? message : JSON.stringify(item.result);
}

function capabilityLabel(value: unknown, kind: 'control' | 'capture') {
  const labels: Record<string, string> = kind === 'control'
    ? { accessibility: '无障碍全局', root: 'Root/ADB', player: '播放器内', unavailable: '未就绪' }
    : { accessibility: '无障碍全屏', root: 'Root/ADB 全屏', app_window: '应用内', unavailable: '未就绪' };
  return labels[String(value || 'unavailable')] || String(value || '未上报');
}
</script>

<template>
  <div>
    <div class="toast-stack"><Message v-if="notice" type="success" :content="notice" /><Message v-if="error" type="warning" :content="error" /></div>
    <header class="page-header">
      <div><h1>遥控与安装</h1><p>{{ selected?.online ? 'Agent 在线' : 'Agent 离线，指令将进入队列' }}</p></div>
      <Select v-model="selection" :options="options" placeholder="选择设备" />
    </header>
    <div v-if="!selected" class="empty-band"><Empty title="没有可控设备" description="请先批准并启用一台电视" /></div>
    <template v-else>
      <div class="device-strip"><span class="status-line" :class="{ online: selected.online }">{{ selected.name }}</span><span class="mono muted">{{ selected.lan_ip || selected.device_id }}</span><Tag :color="selected.online ? 'mint' : 'default'">{{ selected.online ? '在线' : '离线' }}</Tag><Tag :color="selected.metadata.control_mode === 'unavailable' ? 'yellow' : 'mint'">遥控：{{ controlMode }}</Tag><Tag :color="selected.metadata.capture_mode === 'unavailable' ? 'yellow' : 'blue'">采集：{{ captureMode }}</Tag></div>
      <Tabs v-model:active-key="activeTab" :items="tabs" />

      <section v-if="activeTab === 'remote'" class="remote-panel">
        <div class="live-screen">
          <div class="live-screen-head"><strong>电视画面</strong><span class="live-state" :class="{ online: screenConnected }">{{ screenConnected ? 'JPEG 实时画面' : '连接中' }}</span></div>
          <div class="live-screen-frame">
            <canvas ref="canvasRef" aria-label="电视实时画面" />
            <span v-if="!hasPreviewFrame">{{ snapshotError || (selected.online ? '正在建立实时画面' : '电视当前离线') }}</span>
          </div>
        </div>
        <div class="dpad" aria-label="方向遥控器">
          <Button class="dpad-up" title="向上" aria-label="向上" :loading="busy === 'DPAD_UP'" @click="sendKey('DPAD_UP')"><ArrowUp /></Button>
          <Button class="dpad-left" title="向左" aria-label="向左" :loading="busy === 'DPAD_LEFT'" @click="sendKey('DPAD_LEFT')"><ArrowLeft /></Button>
          <Button class="dpad-ok" type="primary" title="确认" aria-label="确认" :loading="busy === 'DPAD_CENTER'" @click="sendKey('DPAD_CENTER')"><Circle :size="18" /></Button>
          <Button class="dpad-right" title="向右" aria-label="向右" :loading="busy === 'DPAD_RIGHT'" @click="sendKey('DPAD_RIGHT')"><ArrowRight /></Button>
          <Button class="dpad-down" title="向下" aria-label="向下" :loading="busy === 'DPAD_DOWN'" @click="sendKey('DPAD_DOWN')"><ArrowDown /></Button>
        </div>
        <div class="remote-actions">
          <Button title="返回" @click="sendKey('BACK')"><RotateCcw :size="18" /> 返回</Button>
          <Button title="主页" @click="sendKey('HOME')"><Home :size="18" /> 主页</Button>
          <Button type="primary" title="打开电视端星投屏" :loading="busy === 'foreground'" @click="foregroundTvApp"><Cast :size="18" /> 星投屏</Button>
          <Button title="音量减" aria-label="音量减" @click="sendKey('VOLUME_DOWN')"><Volume1 :size="18" /></Button>
          <Button title="音量加" aria-label="音量加" @click="sendKey('VOLUME_UP')"><Volume2 :size="18" /></Button>
          <Button class="power-button" danger :disabled="!powerReady" title="远程关闭电视" @click="powerOpen = true"><Power :size="18" /> 关机</Button>
        </div>
        <div class="media-actions">
          <Button title="播放" aria-label="播放" @click="sendKey('MEDIA_PLAY')"><Play :size="18" /></Button>
          <Button title="暂停" aria-label="暂停" @click="sendKey('MEDIA_PAUSE')"><Pause :size="18" /></Button>
          <Button title="停止" aria-label="停止" @click="sendKey('MEDIA_STOP')"><Square :size="17" /></Button>
        </div>
        <div class="url-command">
          <Input v-model="mediaUrl" allow-clear placeholder="https://.../video.mp4 或 .m3u8" @keyup.enter="sendMedia" />
          <Button :loading="busy === 'media_url'" @click="sendMedia"><Play :size="17" /> 电视播放器</Button>
          <Button type="primary" :loading="busy === 'media_hosted'" @click="sendHostedMedia"><Cast :size="17" /> 后端 DPlayer</Button>
        </div>
      </section>

      <section v-else-if="activeTab === 'install'" class="tool-panel">
        <label class="file-picker"><Upload :size="22" /><span>{{ selectedFile?.name || '选择 APK' }}</span><input type="file" accept=".apk,application/vnd.android.package-archive" @change="chooseFile" /></label>
        <Progress v-if="uploadProgress" :percent="uploadProgress" :status="uploadProgress === 100 ? 'success' : 'default'" />
        <Button type="primary" :disabled="!selectedFile" :loading="busy === 'install'" @click="install">上传并下发安装</Button>
      </section>

      <section v-else class="tool-panel">
        <div class="p2p-status"><strong>{{ p2pState ? `会话 ${p2pState.session_id.slice(0, 12)}` : '未建立远程会话' }}</strong><Tag :color="p2pState?.selected_mode === 'udp_jpeg' ? 'mint' : 'yellow'">{{ p2pState?.selected_mode || 'idle' }}</Tag></div>
        <p class="muted">手机 App 在局域网内优先连接电视 8080 TCP JPEG 流，跨公网时使用认证 UDP P2P；浏览器受安全模型限制，通过后端二进制 WebSocket 接收同一 JPEG 帧。</p>
        <div class="toolbar"><Button type="primary" :loading="busy === 'p2p_auto'" @click="startP2P('auto')">自动选择</Button><Button :loading="busy === 'p2p_relay'" @click="startP2P('relay')">强制中继</Button></div>
      </section>

      <section class="section">
        <div class="command-head"><h2 class="section-title">最近指令</h2><Button type="text" title="刷新指令" aria-label="刷新指令" @click="connectScope"><RefreshCw :size="16" /></Button></div>
        <div v-if="commands.length" class="island-table-wrap command-scroll"><table class="island-table"><thead><tr><th>时间</th><th>指令</th><th>状态</th><th>结果</th></tr></thead><tbody><tr v-for="item in commands.slice(0, 100)" :key="item.id"><td class="nowrap">{{ new Date(item.created_at * 1000).toLocaleTimeString() }}</td><td>{{ commandLabel(item) }}</td><td><Tag :color="item.status === 'completed' ? 'mint' : item.status === 'failed' ? 'pink' : item.status === 'delivered' ? 'blue' : 'yellow'">{{ item.status }}</Tag></td><td class="mono command-result">{{ commandResult(item) }}</td></tr></tbody></table></div>
      </section>
    </template>
    <Modal v-model:open="powerOpen" title="确认关闭电视" :closable="!powerBusy">
      <p>关机后电视会立即离线，需要在电视旁手动开机。此操作仅在电视已授权 ADB 或 Root 时可用。</p>
      <template #footer><Button :disabled="powerBusy" @click="powerOpen = false">取消</Button><Button danger :loading="powerBusy" @click="shutdownTv"><Power :size="17" /> 确认关机</Button></template>
    </Modal>
  </div>
</template>

<style scoped lang="less">
.device-strip { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; padding: 11px 0 18px; }
.remote-panel { display: grid; justify-items: center; gap: 20px; padding: 28px 0; }
.live-screen { width: min(760px, 100%); }
.live-screen-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.live-state { color: var(--animal-text-color-secondary); font-size: 12px; }
.live-state::before { content: ''; display: inline-block; width: 7px; height: 7px; margin-right: 6px; border-radius: 50%; background: #9ba6a0; }
.live-state.online::before { background: #278a59; }
.live-screen-frame { position: relative; width: 100%; aspect-ratio: 16 / 9; display: grid; place-items: center; overflow: hidden; border: 1px solid #dbe2de; border-radius: 6px; background: #171b19; color: #b7c4be; font-size: 13px; }
.live-screen-frame canvas { width: 100%; height: 100%; display: block; }
.live-screen-frame span { position: absolute; }
.dpad { width: 220px; height: 220px; display: grid; grid-template: repeat(3, 64px) / repeat(3, 64px); gap: 10px; place-content: center; }
.dpad :deep(button) { width: 64px; min-width: 64px; height: 64px; padding: 0; }
.dpad-up { grid-area: 1 / 2; } .dpad-left { grid-area: 2 / 1; } .dpad-ok { grid-area: 2 / 2; } .dpad-right { grid-area: 2 / 3; } .dpad-down { grid-area: 3 / 2; }
.remote-actions, .media-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 9px; }
.power-button { border-color: #d92d20 !important; color: #b42318 !important; }
.media-actions :deep(button) { width: 48px; min-width: 48px; padding: 0; }
.url-command { width: min(820px, 100%); display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 9px; }
.tool-panel { min-height: 280px; display: flex; flex-direction: column; align-items: flex-start; gap: 18px; padding: 30px 0; }
.file-picker { width: min(520px, 100%); min-height: 100px; display: flex; align-items: center; justify-content: center; gap: 10px; border: 1px dashed #93a49c; border-radius: 8px; background: white; cursor: pointer; }
.file-picker input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.p2p-status, .command-head { display: flex; align-items: center; gap: 10px; }
.command-head { justify-content: space-between; }
.command-scroll { max-height: 420px; overflow: auto; border: 1px solid #dce2df; background: white; }
.command-scroll .island-table { border: 0; }
.command-scroll th { position: sticky; top: 0; z-index: 1; }
.command-result { min-width: 300px; max-width: 620px; white-space: normal; overflow-wrap: anywhere; }
@media (max-width: 600px) { .url-command { grid-template-columns: 1fr; } }
</style>
