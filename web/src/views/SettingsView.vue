<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Button, Form, FormItem, Input, Message, Select, Switch, Textarea } from 'animal-island-ui-vue';
import { Activity, BadgeCheck, CreditCard, KeyRound, PackageCheck, Save, Trash2, UploadCloud } from '@lucide/vue';
import { getReleases, getSettings, getSystemStatus, publishRelease, readableError, saveSettings, testAiConnection, testEpayConnection, uploadApk } from '../api';
import type { ClientRelease, ConsoleSettings, ConsoleSettingsUpdate, SystemStatus } from '../types';
import AerialRow from '../components/AerialRow.vue';
import AerialPanel from '../components/AerialPanel.vue';

const settings = reactive<ConsoleSettingsUpdate>({
  ai_enabled: false,
  ai_api_url: '',
  ai_api_key: '',
  ai_api_model: '',
  ai_api_key_set: false,
  clear_ai_api_key: false,
  p2p_enabled: false,
  relay_enabled: true,
  sales_enabled: false,
  payment_enabled: false,
  epay_payment_methods: ['alipay', 'wxpay'],
  epay_base_url: 'https://pay.id0.cn',
  epay_pid: '',
  epay_key: '',
  epay_key_set: false,
  clear_epay_key: false,
  epay_notify_url: '',
  epay_return_url: '',
  epay_merchant_name: '',
  epay_merchant_account: '',
  epay_merchant_active: false,
  epay_merchant_balance: '',
  epay_merchant_settlement_type: 0,
  epay_merchant_orders: 0,
  epay_merchant_orders_today: 0,
  epay_merchant_orders_lastday: 0,
  epay_verified_pid: '',
  epay_verified_at: 0,
});
const saving = ref(false);
const testing = ref(false);
const paymentTesting = ref(false);
const notice = ref('');
const error = ref('');
const baseline = ref('');
const system = ref<SystemStatus | null>(null);
const releases = ref<ClientRelease[]>([]);
const releaseTarget = ref<'tv' | 'mobile'>('tv');
const releaseVersionName = ref('');
const releaseVersionCode = ref('');
const releaseNotes = ref('');
const releaseMandatory = ref(false);
const releaseFile = ref<File | null>(null);
const releaseBusy = ref(false);
const releaseProgress = ref(0);
const releaseStage = ref('');
const releasePlatforms: Array<'tv' | 'mobile'> = ['tv', 'mobile'];
const releaseOptions = [
  { label: '电视端', value: 'tv' },
  { label: '手机端', value: 'mobile' },
];
const paymentMethods = [
  { value: 'alipay' as const, label: '支付宝', description: '调用值 alipay' },
  { value: 'wxpay' as const, label: '微信支付', description: '调用值 wxpay' },
];

const systemItems = computed(() => system.value ? [
  ['后端版本', `v${system.value.version}`],
  ['运行时间', duration(system.value.uptime_seconds)],
  ['电视 Agent', `${system.value.connected_agents} 在线 / ${system.value.registered_devices} 已登记`],
  ['控制端连接', `${system.value.connected_controls} 个`],
  ['进行中指令', `${system.value.active_commands} 条`],
  ['失败指令', `${system.value.failed_commands} 条`],
  ['待审批', `${system.value.pending_approvals} 台`],
  ['数据库', formatBytes(system.value.database_size_bytes)],
] : []);

function applySettings(value: ConsoleSettings) {
  Object.assign(settings, value, { ai_api_key: '', clear_ai_api_key: false, epay_key: '', clear_epay_key: false });
}

/** Serialized snapshot of every editable field, used to detect unsaved changes. */
function snapshot() {
  return JSON.stringify(settings);
}

const dirty = computed(() => baseline.value !== '' && snapshot() !== baseline.value);

function revert() {
  if (!baseline.value) return;
  Object.assign(settings, JSON.parse(baseline.value));
  notice.value = '已撤销未保存的修改';
  window.setTimeout(() => (notice.value = ''), 2000);
}

onMounted(async () => {
  try {
    const [configured, status, published] = await Promise.all([getSettings(), getSystemStatus(), getReleases()]);
    applySettings(configured);
    baseline.value = snapshot();
    system.value = status;
    releases.value = published;
  } catch (reason) {
    error.value = readableError(reason);
  }
});

function chooseReleaseFile(event: Event) {
  releaseFile.value = (event.target as HTMLInputElement).files?.[0] || null;
}

function latestFor(platform: 'tv' | 'mobile') {
  return releases.value.find((item) => item.platform === platform);
}

async function publishClientUpdate() {
  error.value = '';
  notice.value = '';
  const code = Number(releaseVersionCode.value);
  if (!releaseFile.value || !releaseVersionName.value.trim() || !Number.isInteger(code) || code <= 0) {
    error.value = '请选择 APK，并填写正确的版本名称和递增版本码';
    return;
  }
  releaseBusy.value = true;
  releaseProgress.value = 0;
  releaseStage.value = '正在上传安装包';
  try {
    const apk = await uploadApk(releaseFile.value, (value) => (releaseProgress.value = value));
    releaseStage.value = '上传完成，正在登记发布版本';
    const published = await publishRelease({
      platform: releaseTarget.value,
      apk_id: apk.id,
      version_code: code,
      version_name: releaseVersionName.value.trim(),
      release_notes: releaseNotes.value.trim(),
      mandatory: releaseMandatory.value,
    });
    releases.value = [published, ...releases.value.filter((item) =>
      !(item.platform === published.platform && item.version_code === published.version_code))]
      .sort((left, right) => left.platform.localeCompare(right.platform) || right.version_code - left.version_code);
    notice.value = `${published.platform === 'tv' ? '电视端' : '手机端'} ${published.version_name} 已发布`;
    releaseFile.value = null;
    releaseVersionName.value = '';
    releaseVersionCode.value = '';
    releaseNotes.value = '';
    releaseProgress.value = 0;
    releaseStage.value = '';
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    releaseBusy.value = false;
    if (error.value) releaseStage.value = '';
  }
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 ** 2).toFixed(1)} MB`;
}

function duration(seconds: number) {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return [days ? `${days} 天` : '', hours ? `${hours} 小时` : '', `${minutes} 分`].filter(Boolean).join(' ');
}

function toggleClearApiKey() {
  settings.clear_ai_api_key = !settings.clear_ai_api_key;
  if (settings.clear_ai_api_key) settings.ai_api_key = '';
}

function toggleClearEpayKey() {
  settings.clear_epay_key = !settings.clear_epay_key;
  if (settings.clear_epay_key) settings.epay_key = '';
}

function paymentMethodEnabled(method: 'alipay' | 'wxpay') {
  return settings.epay_payment_methods.includes(method);
}

function setPaymentMethod(method: 'alipay' | 'wxpay', enabled: boolean) {
  settings.epay_payment_methods = enabled
    ? Array.from(new Set([...settings.epay_payment_methods, method]))
    : settings.epay_payment_methods.filter((item) => item !== method);
}

function settlementTypeLabel(value: number) {
  return ({ 1: '支付宝', 2: '微信', 3: 'QQ', 4: '银行卡' } as Record<number, string>)[value] || '未返回';
}

function dateTime(value: number) {
  return value ? new Date(value * 1000).toLocaleString('zh-CN', { hour12: false }) : '尚未验证';
}

function validate(requireAiConfig = settings.ai_enabled): boolean {
  const apiUrl = settings.ai_api_url.trim();
  const apiModel = settings.ai_api_model.trim();
  settings.ai_api_url = apiUrl;
  settings.ai_api_model = apiModel;
  if (requireAiConfig && (!apiUrl || !apiModel)) {
    error.value = '测试或启用 AI 前，请填写 API URL 和 API Model';
    return false;
  }
  if (apiUrl) {
    try {
      const parsed = new URL(apiUrl);
      if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) {
        throw new Error('invalid');
      }
    } catch {
      error.value = 'API URL 必须是有效的 HTTP/HTTPS 地址，且不能包含密钥或查询参数';
      return false;
    }
  }
  if (settings.payment_enabled && (!settings.epay_base_url.trim() || !settings.epay_pid.trim() || (!settings.epay_key_set && !settings.epay_key.trim()))) {
    error.value = '启用在线支付前，请完整填写易支付地址、商户 PID 和商户密钥';
    return false;
  }
  if (settings.payment_enabled && settings.epay_payment_methods.length === 0) {
    error.value = '启用在线支付时，至少选择一种支付方式';
    return false;
  }
  return true;
}

async function save() {
  error.value = '';
  if (!validate()) return;
  saving.value = true;
  try {
    applySettings(await saveSettings(settings));
    baseline.value = snapshot();
    notice.value = '设置已保存';
    window.setTimeout(() => (notice.value = ''), 2200);
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  notice.value = '';
  error.value = '';
  if (!validate(true)) return;
  testing.value = true;
  try {
    const result = await testAiConnection({
      ai_api_url: settings.ai_api_url,
      ai_api_key: settings.ai_api_key || undefined,
      ai_api_model: settings.ai_api_model,
      use_stored_api_key: settings.ai_api_key_set && !settings.clear_ai_api_key && !settings.ai_api_key,
    });
    const elapsed = result.latency_ms === null ? '' : ` · ${result.latency_ms} ms`;
    const model = result.model ? ` · ${result.model}` : '';
    if (result.ok) {
      notice.value = `连接成功${elapsed}${model}`;
      window.setTimeout(() => (notice.value = ''), 3500);
    } else {
      error.value = `${result.message}${elapsed}`;
    }
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    testing.value = false;
  }
}

async function testPaymentConnection() {
  notice.value = '';
  error.value = '';
  if (!settings.epay_base_url.trim() || !settings.epay_pid.trim() || (!settings.epay_key.trim() && !settings.epay_key_set)) {
    error.value = '请先填写易支付地址、商户 PID 和商户密钥';
    return;
  }
  paymentTesting.value = true;
  try {
    const result = await testEpayConnection({
      epay_base_url: settings.epay_base_url.trim(),
      epay_pid: settings.epay_pid.trim(),
      epay_key: settings.epay_key.trim() || undefined,
      use_stored_epay_key: settings.epay_key_set && !settings.clear_epay_key && !settings.epay_key,
    });
    Object.assign(settings, {
      epay_merchant_name: result.merchant_name || '',
      epay_merchant_account: result.settlement_account || '',
      epay_merchant_active: Boolean(result.merchant_active),
      epay_merchant_balance: result.merchant_balance || '0.00',
      epay_merchant_settlement_type: result.settlement_type || 0,
      epay_merchant_orders: result.orders || 0,
      epay_merchant_orders_today: result.orders_today || 0,
      epay_merchant_orders_lastday: result.orders_lastday || 0,
      epay_verified_pid: result.merchant_pid || settings.epay_pid,
      epay_verified_at: result.verified_at || 0,
    });
    if (result.ok) {
      notice.value = `${result.message}${result.merchant_name ? ` · 结算姓名：${result.merchant_name}` : ''}${result.latency_ms ? ` · ${result.latency_ms} ms` : ''}`;
    } else {
      error.value = result.message;
    }
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    paymentTesting.value = false;
  }
}
</script>

<template>
  <div class="settings-page">
    <div class="toast-stack"><Message v-if="notice" type="success" :content="notice" /><Message v-if="error" type="warning" :content="error" /></div>
    <header class="page-header"><div><h1>系统设置</h1><p>Agent 调度、P2P 和 AI 接口</p></div><Button type="primary" :loading="saving" @click="save"><Save :size="17" /> 保存</Button></header>
    <AerialPanel title="后端运行状态" description="连接、队列与存储">
      <div v-if="system" class="system-grid">
        <div v-for="item in systemItems" :key="item[0]"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong></div>
      </div>
      <p v-else class="muted">正在读取后端状态</p>
    </AerialPanel>
    <AerialPanel title="客户端更新" description="电视端与手机端版本发布">
      <div class="fields release-fields">
        <div class="current-releases">
          <div v-for="platform in releasePlatforms" :key="platform">
            <span>{{ platform === 'tv' ? '电视端' : '手机端' }}</span>
            <strong v-if="latestFor(platform)">v{{ latestFor(platform)?.version_name }}（{{ latestFor(platform)?.version_code }}）</strong>
            <strong v-else>尚未发布</strong>
          </div>
        </div>
        <Form layout="vertical">
          <FormItem label="发布目标"><Select v-model="releaseTarget" :options="releaseOptions" /></FormItem>
          <div class="release-version-row">
            <FormItem label="版本名称"><Input v-model="releaseVersionName" placeholder="例如 0.9.0" /></FormItem>
            <FormItem label="版本码"><Input v-model="releaseVersionCode" inputmode="numeric" placeholder="必须递增" /></FormItem>
          </div>
          <FormItem label="APK 文件">
            <label class="release-file"><UploadCloud :size="20" /><span>{{ releaseFile?.name || '选择 APK' }}</span><input type="file" accept=".apk,application/vnd.android.package-archive" @change="chooseReleaseFile" /></label>
          </FormItem>
          <FormItem label="更新说明"><Textarea v-model="releaseNotes" :rows="3" placeholder="本次修复和新增内容" /></FormItem>
          <FormItem label="重要更新"><Switch v-model="releaseMandatory" checked-children="提示更新" un-checked-children="普通更新" /></FormItem>
          <div v-if="releaseProgress" class="release-progress"><span :style="{ width: `${releaseProgress}%` }"></span></div>
          <p v-if="releaseStage" class="muted">{{ releaseStage }}<template v-if="releaseProgress < 100"> · {{ releaseProgress }}%</template></p>
          <Button type="primary" :loading="releaseBusy" :disabled="!releaseFile" @click="publishClientUpdate"><PackageCheck :size="17" /> 上传并发布</Button>
        </Form>
      </div>
    </AerialPanel>
    <Form layout="vertical">
      <AerialPanel title="P2P 与控制通道" description="只对具备 WebRTC 视频能力的新版 Agent 发起直连">
        <div class="fields">
          <AerialRow label="允许 P2P 直连" description="公网遥控优先使用认证后的 UDP 点对点通道，仍传 JPEG 快照；失败时按下方设置回退">
            <Switch v-model="settings.p2p_enabled" aria-label="允许 P2P 直连" checked-children="开启" un-checked-children="关闭" />
          </AerialRow>
          <AerialRow label="允许控制通道回退" description="直连不可用或协商失败时，继续通过加密 WebSocket 发送遥控指令；后端不转发视频">
            <Switch v-model="settings.relay_enabled" aria-label="允许控制通道回退" checked-children="允许" un-checked-children="禁用" />
          </AerialRow>
          <AerialRow label="会员销售系统" description="开启后普通用户可创建会员订单和使用兑换码；管理员直接授权不受影响">
            <Switch v-model="settings.sales_enabled" aria-label="会员销售系统" checked-children="开启" un-checked-children="关闭" />
          </AerialRow>
        </div>
      </AerialPanel>
      <AerialPanel title="易支付收款" description="支付宝、微信支付与异步回调">
        <div class="fields">
          <FormItem label="启用在线购买" extra="需同时开启会员销售系统；未启用时电视本地配置页不会显示购买入口"><Switch v-model="settings.payment_enabled" checked-children="开启" un-checked-children="关闭" /></FormItem>
          <FormItem label="支付方式" extra="只会向电视和手机显示后台已启用的付款入口">
            <div class="payment-methods">
              <div v-for="method in paymentMethods" :key="method.value" class="payment-method">
                <div><strong>{{ method.label }}</strong><small>{{ method.description }}</small></div>
                <Switch :model-value="paymentMethodEnabled(method.value)" checked-children="启用" un-checked-children="停用" @update:model-value="setPaymentMethod(method.value, Boolean($event))" />
              </div>
            </div>
          </FormItem>
          <FormItem label="接口根地址"><Input v-model="settings.epay_base_url" autocomplete="url" placeholder="https://pay.id0.cn" /></FormItem>
          <FormItem label="商户 PID"><Input v-model="settings.epay_pid" autocomplete="off" placeholder="商户 ID" /></FormItem>
          <FormItem label="商户密钥" :extra="settings.clear_epay_key ? '保存后清除已配置的密钥' : settings.epay_key_set ? '密钥已配置；留空不会覆盖' : '密钥尚未配置'">
            <div class="secret-field">
              <Input v-model="settings.epay_key" type="password" autocomplete="new-password" :disabled="settings.clear_epay_key" :placeholder="settings.epay_key_set ? '输入新密钥可替换' : '商户 KEY'" @update:model-value="settings.clear_epay_key = false"><template #prefix><KeyRound :size="16" /></template></Input>
              <Button v-if="settings.epay_key_set" type="text" class="clear-key" @click="toggleClearEpayKey"><Trash2 v-if="!settings.clear_epay_key" :size="16" />{{ settings.clear_epay_key ? '取消清除' : '清除密钥' }}</Button>
            </div>
          </FormItem>
          <FormItem label="异步回调地址" extra="留空时自动使用当前后端公网地址 /api/vip/payments/epay/notify"><Input v-model="settings.epay_notify_url" autocomplete="url" placeholder="自动生成" /></FormItem>
          <FormItem label="支付完成跳转" extra="可留空"><Input v-model="settings.epay_return_url" autocomplete="url" placeholder="可选" /></FormItem>
          <Button :loading="paymentTesting" :disabled="saving" @click="testPaymentConnection"><CreditCard :size="17" /> 验证商户配置</Button>
          <div v-if="settings.epay_verified_at" class="merchant-result">
            <div class="merchant-title">
              <span><BadgeCheck :size="18" /> 已验证商户</span>
              <strong :class="settings.epay_merchant_active ? 'active' : 'disabled'">{{ settings.epay_merchant_active ? '商户正常' : '商户封禁' }}</strong>
            </div>
            <dl>
              <div><dt>结算姓名</dt><dd>{{ settings.epay_merchant_name || '未返回' }}</dd></div>
              <div><dt>结算账号</dt><dd>{{ settings.epay_merchant_account || '未返回' }}</dd></div>
              <div><dt>结算方式</dt><dd>{{ settlementTypeLabel(settings.epay_merchant_settlement_type) }}</dd></div>
              <div><dt>商户余额</dt><dd>¥{{ settings.epay_merchant_balance || '0.00' }}</dd></div>
              <div><dt>订单统计</dt><dd>共 {{ settings.epay_merchant_orders }} · 今日 {{ settings.epay_merchant_orders_today }} · 昨日 {{ settings.epay_merchant_orders_lastday }}</dd></div>
              <div><dt>验证信息</dt><dd>PID {{ settings.epay_verified_pid }} · {{ dateTime(settings.epay_verified_at) }}</dd></div>
            </dl>
          </div>
        </div>
      </AerialPanel>
      <AerialPanel title="AI API 配置" description="OpenAI 兼容接口参数">
        <div class="fields">
          <FormItem label="启用 AI"><Switch v-model="settings.ai_enabled" checked-children="开启" un-checked-children="关闭" /></FormItem>
          <FormItem label="API URL" extra="填写接口根地址，不要把 API Key 放在地址中">
            <Input v-model="settings.ai_api_url" allow-clear autocomplete="url" placeholder="https://api.example.com/v1" />
          </FormItem>
          <FormItem label="API Key" :extra="settings.clear_ai_api_key ? '保存后清除已配置的 Key' : settings.ai_api_key_set ? 'Key 已配置；留空不会覆盖' : 'Key 尚未配置'">
            <div class="secret-field">
              <Input
                v-model="settings.ai_api_key"
                type="password"
                autocomplete="new-password"
                :disabled="settings.clear_ai_api_key"
                :placeholder="settings.ai_api_key_set ? '输入新 Key 可替换' : 'sk-...'"
                @update:model-value="settings.clear_ai_api_key = false"
              >
                <template #prefix><KeyRound :size="16" /></template>
              </Input>
              <Button v-if="settings.ai_api_key_set" type="text" class="clear-key" @click="toggleClearApiKey">
                <Trash2 v-if="!settings.clear_ai_api_key" :size="16" />
                {{ settings.clear_ai_api_key ? '取消清除' : '清除 Key' }}
              </Button>
            </div>
          </FormItem>
          <FormItem label="API Model"><Input v-model="settings.ai_api_model" allow-clear placeholder="例如：gpt-4.1-mini" /></FormItem>
          <div class="ai-actions">
            <Button :loading="testing" :disabled="saving" @click="testConnection"><Activity :size="17" /> 测试连接</Button>
          </div>
        </div>
      </AerialPanel>
    </Form>
    <div v-if="dirty" class="aerial-savebar" role="status">
      <span>有未保存的修改</span>
      <div class="aerial-savebar__actions">
        <Button @click="revert">撤销</Button>
        <Button type="primary" :loading="saving" @click="save"><Save :size="17" /> 保存</Button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="less">
.settings-page { max-width: 900px; }
.settings-band { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 30px; padding: 24px 0; border-top: 1px solid #d7dedb; }
.settings-band:last-child { border-bottom: 1px solid #d7dedb; }
.settings-band h2 { margin: 0; font-size: 16px; }
.settings-band p { margin: 6px 0 0; color: var(--animal-text-color-secondary); font-size: 12px; }
.fields { max-width: 520px; }
.system-band { border-bottom: 0; }
.system-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 22px; }
.system-grid div { min-width: 0; padding: 8px 0; border-bottom: 1px solid #e1e6e3; }
.system-grid span, .system-grid strong { display: block; letter-spacing: 0; }
.system-grid span { color: var(--animal-text-color-secondary); font-size: 11px; }
.system-grid strong { margin-top: 4px; overflow-wrap: anywhere; font-size: 13px; }
.secret-field { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; }
.clear-key { color: var(--animal-error-color); }
.payment-methods { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.payment-method { min-height: 64px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid #d6dfda; border-radius: 7px; background: #fff; }
.payment-method strong, .payment-method small { display: block; letter-spacing: 0; }
.payment-method strong { font-size: 13px; }
.payment-method small { margin-top: 3px; color: var(--animal-text-color-secondary); font-size: 11px; }
.merchant-result { margin-top: 14px; padding: 14px; border: 1px solid #b8d9ca; border-radius: 7px; background: #f5fbf8; }
.merchant-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.merchant-title span { display: flex; align-items: center; gap: 7px; font-size: 13px; font-weight: 700; }
.merchant-title strong { font-size: 12px; }
.merchant-title .active { color: #17854f; }
.merchant-title .disabled { color: var(--animal-error-color); }
.merchant-result dl { margin: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px 18px; }
.merchant-result dl div { min-width: 0; }
.merchant-result dt, .merchant-result dd { margin: 0; letter-spacing: 0; }
.merchant-result dt { color: var(--animal-text-color-secondary); font-size: 11px; }
.merchant-result dd { margin-top: 3px; overflow-wrap: anywhere; font-size: 12px; }
.ai-actions { display: flex; justify-content: flex-start; }
.current-releases { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-bottom: 18px; }
.current-releases div { padding: 10px 0; border-bottom: 1px solid #dfe5e2; }
.current-releases span, .current-releases strong { display: block; letter-spacing: 0; }
.current-releases span { color: var(--animal-text-color-secondary); font-size: 11px; }
.current-releases strong { margin-top: 4px; font-size: 13px; }
.release-version-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, .7fr); gap: 10px; }
.release-file { min-height: 72px; display: flex; align-items: center; justify-content: center; gap: 9px; border: 1px dashed #8ca096; border-radius: 8px; background: #fff; cursor: pointer; }
.release-file input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.release-progress { height: 5px; margin: -4px 0 14px; overflow: hidden; border-radius: 3px; background: #dce5e0; }
.release-progress span { display: block; height: 100%; background: #278a59; transition: width .2s ease; }
.aerial-savebar { position: sticky; bottom: 16px; z-index: 5; display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 20px; padding: 14px 20px; border: 1px solid var(--aerial-hairline); border-radius: 999px; background: var(--aerial-glass); backdrop-filter: blur(24px); box-shadow: var(--aerial-shadow); }
.aerial-savebar__actions { display: flex; gap: 10px; }
@media (max-width: 700px) { .settings-band { grid-template-columns: 1fr; gap: 14px; } }
@media (max-width: 520px) { .release-version-row, .current-releases { grid-template-columns: 1fr; } }
@media (max-width: 520px) { .payment-methods, .merchant-result dl { grid-template-columns: 1fr; } }
@media (max-width: 460px) { .secret-field { grid-template-columns: 1fr; align-items: stretch; } }
@media (max-width: 460px) { .system-grid { grid-template-columns: 1fr; } }
</style>
