<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { Button, Empty, Input, Message, Progress, Select, Tag } from 'animal-island-ui-vue';
import { ListMusic, Music, RefreshCw, Search, Trash2 } from '@lucide/vue';
import {
  deleteMusicQueueItem,
  getMusicDevices,
  getMusicState,
  readableError,
  requestMusic,
  searchMusic,
} from '../api';
import type { MusicDevice, MusicQueueItem, MusicSong, MusicState } from '../types';

const STORAGE_KEY = 'xingmu_admin_music_device';
const QUALITY = 'MP3_320';

const devices = ref<MusicDevice[]>([]);
const devicesLoading = ref(false);
const devicesError = ref('');
const selectedId = ref(localStorage.getItem(STORAGE_KEY) || '');
const musicState = ref<MusicState | null>(null);
const keyword = ref('');
const results = ref<MusicSong[]>([]);
const lastQuery = ref('');
const searched = ref(false);
const searching = ref(false);
const searchError = ref('');
const requestingMid = ref('');
const removingId = ref('');
const notice = ref('');
const error = ref('');
const refreshing = ref(false);
let pollTimer: number | undefined;
let noticeTimer: number | undefined;
let errorTimer: number | undefined;
let stateBusy = false;
let searchSequence = 0;

const selectedDevice = computed(() => devices.value.find((item) => item.device_id === selectedId.value) || null);
const onlineCount = computed(() => devices.value.filter((item) => item.online).length);
const duplicateNames = computed(() => {
  const counts: Record<string, number> = {};
  for (const device of devices.value) counts[device.name] = (counts[device.name] || 0) + 1;
  return counts;
});
const deviceOptions = computed(() => devices.value.map((device) => ({
  label: `${device.online ? '● 在线' : '○ 离线'} · ${device.name}${duplicateNames.value[device.name] > 1 ? ` · ${device.device_id.slice(-6)}` : ''}`,
  value: device.device_id,
})));
const selection = computed<string | number>({
  get: () => selectedId.value,
  set: (value) => selectDevice(String(value)),
});
const rawQueue = computed(() => musicState.value?.queue || []);
// A TV that reports the same queue row twice must not become two identical rows; keep the first.
const queue = computed(() => {
  const seen = new Set<string>();
  return rawQueue.value.filter((item) => {
    const key = item.id || `${item.mid}:${item.name}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
});
const currentIndex = computed(() => {
  const rawIndex = Number(musicState.value?.index ?? -1);
  if (rawIndex < 0) return -1;
  const currentId = rawQueue.value[rawIndex]?.id;
  if (currentId) {
    const matched = queue.value.findIndex((item) => item.id === currentId);
    if (matched >= 0) return matched;
  }
  return Math.min(rawIndex, queue.value.length - 1);
});
const current = computed<MusicQueueItem | null>(() => {
  const index = currentIndex.value;
  return index >= 0 && index < queue.value.length ? queue.value[index] : null;
});
const durationMs = computed(() => {
  const reported = Number(musicState.value?.duration_ms || 0);
  if (reported > 0) return reported;
  return Math.max(0, Number(current.value?.interval || 0) * 1000);
});
const positionMs = computed(() => Math.min(durationMs.value, Math.max(0, Number(musicState.value?.position_ms || 0))));
const progress = computed(() => (durationMs.value
  ? Math.round(Math.min(100, Math.max(0, (positionMs.value / durationMs.value) * 100)) * 10) / 10
  : 0));
const playbackStatus = computed<{ label: string; color: 'default' | 'mint' | 'yellow' | 'pink' | 'blue' }>(() => {
  if (!musicState.value) return { label: '同步中', color: 'default' };
  if (!musicState.value.online) return { label: '电视离线', color: 'pink' };
  if (current.value && musicState.value.playing) return { label: '正在播放', color: 'mint' };
  if (current.value && musicState.value.prepared) return { label: '已暂停', color: 'yellow' };
  if (current.value) return { label: '缓冲中', color: 'blue' };
  return { label: '待播放', color: 'default' };
});
const headerHint = computed(() => {
  if (devicesLoading.value) return '正在读取可点歌的电视…';
  if (!devices.value.length) return '暂无可用电视';
  if (!selectedDevice.value) return `${devices.value.length} 台电视可用，请选择一台`;
  return selectedDevice.value.online
    ? `电视在线 · 共 ${onlineCount.value} 台在线`
    : `电视离线 · 点歌会进入等待队列（共 ${onlineCount.value} 台在线）`;
});
const searchHint = computed(() => {
  if (searching.value) return '正在搜索曲库…';
  if (searchError.value) return searchError.value;
  if (!searched.value) return '支持歌名、歌手与专辑';
  return `“${lastQuery.value}” · ${results.value.length} 首`;
});

function showNotice(value: string) {
  window.clearTimeout(noticeTimer);
  notice.value = value;
  noticeTimer = window.setTimeout(() => (notice.value = ''), 3600);
}

function showError(value: string) {
  window.clearTimeout(errorTimer);
  error.value = value;
  errorTimer = window.setTimeout(() => (error.value = ''), 4200);
}

function formatDuration(seconds: number) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

function formatMilliseconds(milliseconds: number) {
  return formatDuration(Math.floor((Number(milliseconds) || 0) / 1000));
}

function qualityLabel(value: string) {
  if (!value) return '';
  if (value.startsWith('MP3_')) return `MP3 ${value.slice(4)}K`;
  if (value.startsWith('ACC_')) return `AAC ${value.slice(4)}K`;
  return value;
}

function coverSource(url: string) {
  return url ? `/api/music/cover?url=${encodeURIComponent(url)}` : '';
}

function hideBrokenCover(event: Event) {
  (event.target as HTMLImageElement).style.visibility = 'hidden';
}

async function loadDevices() {
  devicesLoading.value = true;
  devicesError.value = '';
  try {
    devices.value = await getMusicDevices();
    const stored = localStorage.getItem(STORAGE_KEY) || '';
    const next = devices.value.some((device) => device.device_id === stored)
      ? stored
      : devices.value[0]?.device_id || '';
    selectedId.value = next;
    if (next) localStorage.setItem(STORAGE_KEY, next);
    else localStorage.removeItem(STORAGE_KEY);
  } catch (reason) {
    devicesError.value = readableError(reason);
    devices.value = [];
    selectedId.value = '';
  } finally {
    devicesLoading.value = false;
  }
}

function selectDevice(deviceId: string) {
  if (!deviceId || deviceId === selectedId.value) return;
  selectedId.value = deviceId;
  localStorage.setItem(STORAGE_KEY, deviceId);
  musicState.value = null;
  void loadState();
}

async function loadState(showSpinner = false) {
  if (!selectedId.value || stateBusy) return;
  stateBusy = true;
  if (showSpinner) refreshing.value = true;
  const deviceId = selectedId.value;
  try {
    const snapshot = await getMusicState(deviceId);
    if (deviceId === selectedId.value) musicState.value = snapshot;
  } catch (reason) {
    if (showSpinner) showError(readableError(reason));
  } finally {
    stateBusy = false;
    if (showSpinner) refreshing.value = false;
  }
}

function applyQueue(items: MusicQueueItem[], index: number) {
  const base: MusicState = musicState.value ?? {
    online: Boolean(selectedDevice.value?.online),
    playing: false,
    prepared: false,
    position_ms: 0,
    duration_ms: 0,
    queue: [],
    index: -1,
  };
  const nextIndex = items.length ? Math.min(Math.max(index, -1), items.length - 1) : -1;
  musicState.value = {
    ...base,
    queue: items,
    index: nextIndex,
    current: nextIndex >= 0 ? items[nextIndex] : null,
  };
}

async function search() {
  const query = keyword.value.trim();
  if (!query) {
    searchError.value = '请输入歌名或歌手';
    return;
  }
  const sequence = ++searchSequence;
  searching.value = true;
  searchError.value = '';
  results.value = [];
  searched.value = true;
  lastQuery.value = query;
  try {
    const response = await searchMusic(query, 20);
    if (sequence !== searchSequence) return;
    results.value = response.data.list || [];
  } catch (reason) {
    if (sequence !== searchSequence) return;
    searchError.value = readableError(reason);
    showError(searchError.value);
  } finally {
    if (sequence === searchSequence) searching.value = false;
  }
}

async function requestSong(song: MusicSong) {
  if (!selectedId.value) {
    showError('请先选择一台电视');
    return;
  }
  if (requestingMid.value) return;
  requestingMid.value = song.mid;
  try {
    const result = await requestMusic({
      device_id: selectedId.value,
      mid: song.mid,
      name: song.name,
      singer: song.singer,
      album: song.album,
      cover: song.cover,
      interval: song.interval,
      quality: QUALITY,
    });
    applyQueue(result.queue, musicState.value?.index ?? -1);
    if (result.resolve_error) showError(`已加入队列，但播放地址解析失败：${result.resolve_error}`);
    else if (result.delivered) showNotice(`已点《${song.name}》，电视将开始播放`);
    else showNotice(`已加入队列《${song.name}》，电视上线后播放`);
    await loadState();
  } catch (reason) {
    showError(readableError(reason));
  } finally {
    requestingMid.value = '';
  }
}

async function removeSong(item: MusicQueueItem) {
  if (!selectedId.value || !item.id || removingId.value) return;
  removingId.value = item.id;
  try {
    const result = await deleteMusicQueueItem(selectedId.value, item.id);
    applyQueue(result.queue, result.index);
    showNotice(`已从歌单删除《${item.name}》`);
    await loadState();
  } catch (reason) {
    showError(readableError(reason));
  } finally {
    removingId.value = '';
  }
}

onMounted(async () => {
  await loadDevices();
  await loadState();
  pollTimer = window.setInterval(() => {
    if (selectedId.value && document.visibilityState !== 'hidden') void loadState();
  }, 3000);
});

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer);
  window.clearTimeout(noticeTimer);
  window.clearTimeout(errorTimer);
});
</script>

<template>
  <div class="music-page">
    <div class="toast-stack"><Message v-if="notice" type="success" :content="notice" /><Message v-if="error" type="warning" :content="error" /></div>
    <header class="page-header">
      <div><h1>点歌台</h1><p>{{ headerHint }}</p></div>
      <Select v-model="selection" class="music-device-select" :options="deviceOptions" :disabled="!devices.length" placeholder="选择电视" />
    </header>

    <div v-if="devicesLoading && !devices.length" class="empty-band"><Empty title="正在加载电视" description="正在读取可点歌的电视列表" /></div>
    <div v-else-if="devicesError" class="empty-band"><Empty title="设备加载失败" :description="devicesError" /></div>
    <div v-else-if="!devices.length" class="empty-band"><Empty title="暂无可用电视" description="请先在设备页批准并启用一台电视，再回来点歌" /></div>
    <div v-else class="music-layout">
      <section class="music-panel music-search-panel">
        <div class="music-panel-head">
          <div><h2>搜索曲库</h2><p>{{ searchHint }}</p></div>
          <span class="music-quality">音质 320K</span>
        </div>
        <div class="music-search-row">
          <Input v-model="keyword" allow-clear placeholder="搜索歌名、歌手或专辑" @keyup.enter="search" />
          <Button type="primary" :loading="searching" @click="search"><Search :size="17" /> 搜索</Button>
        </div>
        <div v-if="searching" class="music-inline-empty"><Search :size="20" /><span>正在搜索曲库…</span></div>
        <div v-else-if="searchError" class="music-inline-empty"><Search :size="20" /><span>{{ searchError }}</span></div>
        <div v-else-if="!searched" class="music-inline-empty"><Music :size="20" /><span>输入歌名或歌手，搜索后点「点歌」</span></div>
        <div v-else-if="!results.length" class="music-inline-empty"><Music :size="20" /><span>没有找到相关歌曲，换个关键词试试</span></div>
        <ul v-else class="music-result-list">
          <li v-for="song in results" :key="song.mid" class="music-song-row">
            <span class="music-cover"><Music :size="18" /><img v-if="song.cover" :src="coverSource(song.cover)" alt="" loading="lazy" referrerpolicy="no-referrer" @error="hideBrokenCover" /></span>
            <div class="music-song-meta">
              <strong>{{ song.name }}</strong>
              <span>{{ song.singer || '未知歌手' }}<template v-if="song.album"> · {{ song.album }}</template></span>
            </div>
            <span class="music-duration">{{ formatDuration(song.interval) }}</span>
            <Button size="small" type="primary" :loading="requestingMid === song.mid" :disabled="Boolean(requestingMid)" @click="requestSong(song)">点歌</Button>
          </li>
        </ul>
      </section>

      <aside class="music-side">
        <section class="music-panel now-panel">
          <div class="music-panel-head">
            <div><h2>当前播放</h2><p>{{ musicState ? (musicState.online ? '状态每 3 秒同步' : '电视离线，显示最后一次状态') : '等待电视状态' }}</p></div>
            <Tag :color="playbackStatus.color">{{ playbackStatus.label }}</Tag>
          </div>
          <div v-if="current" class="music-now">
            <span class="music-now-cover"><Music :size="24" /><img v-if="current.cover" :src="coverSource(current.cover)" alt="" referrerpolicy="no-referrer" @error="hideBrokenCover" /></span>
            <div class="music-now-meta">
              <strong>{{ current.name }}</strong>
              <span>{{ current.singer || '未知歌手' }}</span>
              <small>{{ current.album || '未知专辑' }}<template v-if="current.quality"> · {{ qualityLabel(current.quality) }}</template></small>
            </div>
          </div>
          <div v-else class="music-inline-empty"><Music :size="20" /><span>电视暂无播放内容</span></div>
          <div class="music-progress">
            <Progress :percent="progress" :animated="false" />
            <div class="music-progress-time"><span>{{ formatMilliseconds(positionMs) }}</span><span>{{ formatMilliseconds(durationMs) }}</span></div>
          </div>
        </section>

        <section class="music-panel queue-panel">
          <div class="music-panel-head">
            <div><h2>已点歌单</h2><p>{{ queue.length ? `${queue.length} 首 · 删除后同步到电视` : '暂无已点歌曲' }}</p></div>
            <Button type="text" size="small" title="刷新歌单" :loading="refreshing" @click="loadState(true)"><RefreshCw :size="16" /></Button>
          </div>
          <ul v-if="queue.length" class="music-queue">
            <li v-for="(item, index) in queue" :key="item.id || `${item.mid}-${index}`" class="music-queue-row" :class="{ current: index === currentIndex }">
              <span class="music-queue-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <div class="music-queue-meta">
                <strong>{{ item.name }}</strong>
                <span>{{ item.singer || '未知歌手' }}<template v-if="index === currentIndex"> · 播放中</template></span>
              </div>
              <Button size="small" type="text" danger title="从歌单删除" :loading="removingId === item.id" :disabled="!item.id" @click="removeSong(item)"><Trash2 :size="15" /></Button>
            </li>
          </ul>
          <div v-else class="music-inline-empty"><ListMusic :size="20" /><span>暂无已点歌曲</span></div>
        </section>
      </aside>
    </div>
  </div>
</template>
