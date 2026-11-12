<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Button, Input, Message, Select, Switch, Tabs, Tag, Textarea } from 'animal-island-ui-vue';
import { Check, Copy, Edit3, Plus, RefreshCw, Save, TicketPlus, Trash2, X } from '@lucide/vue';
import {
  createVipCodes, createVipOrder, createVipPlan, getUsers, getVipCodes,
  getVipGrants, getVipOrders, getVipPlans, readableError, reviewVipOrder, revokeVipGrant, updateVipPlan,
} from '../api';
import type { CreatedVipCode, User, VipCode, VipEntitlements, VipGrant, VipOrder, VipPlan } from '../types';

const plans = ref<VipPlan[]>([]);
const orders = ref<VipOrder[]>([]);
const codes = ref<VipCode[]>([]);
const users = ref<User[]>([]);
const generated = ref<CreatedVipCode[]>([]);
const grants = ref<VipGrant[]>([]);
const loading = ref(false);
const savingPlan = ref(false);
const error = ref('');
const notice = ref('');
const activeTab = ref('overview');
const editingPlanId = ref<number | null>(null);
const codePlan = ref(0);
const codeCount = ref('1');
const grantUser = ref(0);
const grantPlan = ref(0);
const styles = ['sky', 'mint', 'gold', 'rose', 'violet'] as const;
const entitlementLabels: Array<[keyof VipEntitlements, string]> = [
  ['remote_cast', '公网远程投屏'],
  ['remote_control', '公网远程控制'],
  ['screen_view', '远程电视画面'],
  ['app_install', '远程安装应用'],
  ['p2p_access', 'P2P 点对点通道'],
  ['relay_access', '后端回退通道'],
];
const planForm = reactive({
  name: '',
  days: '30',
  price: '9.90',
  tag_style: 'sky',
  description: '',
  device_limit: '1',
  benefits_text: '',
  entitlements: {
    remote_cast: true, remote_control: true, screen_view: true,
    app_install: true, p2p_access: true, relay_access: true,
  } as VipEntitlements,
  badge_text: '',
  accent_color: '#2F80ED',
  recommended: false,
  active: true,
  sort_order: '0',
});

const pending = computed(() => orders.value.filter((item) => item.status === 'pending'));
const paid = computed(() => orders.value.filter((item) => item.status === 'paid'));
const grantableUsers = computed(() => users.value.filter((item) => item.active !== false));
const activePlans = computed(() => plans.value.filter((item) => item.active));
const tabItems = computed(() => [
  { key: 'overview', label: '快捷开通' },
  { key: 'plans', label: '套餐配置' },
  { key: 'orders', label: `支付账单 ${orders.value.length}` },
  { key: 'codes', label: '兑换码' },
]);
const grantUserOptions = computed(() => [
  { label: grantableUsers.value.length ? '请选择账号' : '暂无可用账号', value: 0, disabled: true },
  ...grantableUsers.value.map((user) => ({
    label: `${userLabel(user)}${user.role === 'admin' ? ' · 管理员' : ''}`,
    value: user.id,
  })),
]);
const activePlanOptions = computed(() => activePlans.value.map((plan) => ({
  label: `${plan.name} · ${plan.days} 天 · ${plan.device_limit} 台`,
  value: plan.id,
})));
const codePlanOptions = computed(() => activePlans.value.map((plan) => ({
  label: `${plan.name} · ${plan.device_limit} 台`,
  value: plan.id,
})));
const styleOptions = styles.map((style) => ({ label: style, value: style }));
const selectedGrantUser = computed(() => grantableUsers.value.find((item) => item.id === grantUser.value));
const selectedGrantPlan = computed(() => activePlans.value.find((item) => item.id === grantPlan.value));
const selectedUserGrants = computed(() => grants.value.filter((item) => item.user_id === grantUser.value));

async function refresh() {
  loading.value = true;
  error.value = '';
  try {
    [plans.value, orders.value, codes.value, users.value, grants.value] = await Promise.all([
      getVipPlans(), getVipOrders(), getVipCodes(), getUsers(), getVipGrants(),
    ]);
    if (!activePlans.value.some((plan) => plan.id === codePlan.value)) codePlan.value = activePlans.value[0]?.id || 0;
    if (!activePlans.value.some((plan) => plan.id === grantPlan.value)) grantPlan.value = activePlans.value[0]?.id || 0;
    if (!grantableUsers.value.some((user) => user.id === grantUser.value)) grantUser.value = grantableUsers.value[0]?.id || 0;
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    loading.value = false;
  }
}

onMounted(refresh);

function resetPlan() {
  editingPlanId.value = null;
  Object.assign(planForm, {
    name: '', days: '30', price: '9.90', tag_style: 'sky', description: '',
    device_limit: '1', benefits_text: '', badge_text: '', accent_color: '#2F80ED',
    recommended: false, active: true, sort_order: '0',
  });
  planForm.entitlements = {
    remote_cast: true, remote_control: true, screen_view: true,
    app_install: true, p2p_access: true, relay_access: true,
  };
}

function editPlan(plan: VipPlan) {
  editingPlanId.value = plan.id;
  Object.assign(planForm, {
    name: plan.name,
    days: String(plan.days),
    price: (plan.price_cents / 100).toFixed(2),
    tag_style: plan.tag_style,
    description: plan.description,
    device_limit: String(plan.device_limit),
    benefits_text: plan.benefits.join('\n'),
    badge_text: plan.badge_text,
    accent_color: plan.accent_color,
    recommended: plan.recommended,
    active: plan.active,
    sort_order: String(plan.sort_order),
  });
  planForm.entitlements = { ...plan.entitlements };
  activeTab.value = 'plans';
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

async function savePlan() {
  const days = Number(planForm.days);
  const price = Number(planForm.price);
  const deviceLimit = Number(planForm.device_limit);
  if (!planForm.name.trim() || !Number.isInteger(days) || days < 1 || !Number.isFinite(price) || price < 0 || !Number.isInteger(deviceLimit) || deviceLimit < 1) {
    error.value = '请填写套餐名称、有效天数、价格和至少 1 台授权设备';
    return;
  }
  savingPlan.value = true;
  error.value = '';
  try {
    const payload = {
      name: planForm.name.trim(),
      days,
      price_cents: Math.round(price * 100),
      tag_style: planForm.tag_style as VipPlan['tag_style'],
      description: planForm.description.trim(),
      device_limit: deviceLimit,
      benefits: planForm.benefits_text.split(/\r?\n/).map((item) => item.trim()).filter(Boolean),
      entitlements: { ...planForm.entitlements },
      badge_text: planForm.badge_text.trim(),
      accent_color: planForm.accent_color.toUpperCase(),
      recommended: planForm.recommended,
      active: planForm.active,
      sort_order: Number(planForm.sort_order) || 0,
    };
    if (editingPlanId.value) {
      await updateVipPlan(editingPlanId.value, payload);
      notice.value = '套餐已更新，历史订单保持原购买快照';
    } else {
      await createVipPlan(payload as Omit<VipPlan, 'id'>);
      notice.value = '套餐已创建';
    }
    resetPlan();
    await refresh();
  } catch (reason) {
    error.value = readableError(reason);
  } finally {
    savingPlan.value = false;
  }
}

async function togglePlan(plan: VipPlan, value: boolean) {
  try {
    Object.assign(plan, await updateVipPlan(plan.id, { active: value }));
  } catch (reason) {
    error.value = readableError(reason);
  }
}

async function makeCodes() {
  try {
    generated.value = await createVipCodes(codePlan.value, Number(codeCount.value));
    notice.value = `已生成 ${generated.value.length} 个兑换码，仅本次显示完整内容`;
    await refresh();
  } catch (reason) {
    error.value = readableError(reason);
  }
}

async function review(order: VipOrder, value: 'paid' | 'cancelled') {
  try {
    await reviewVipOrder(order.id, value);
    notice.value = value === 'paid' ? `已为 ${userOrderLabel(order)} 发放会员` : '订单已取消';
    await refresh();
  } catch (reason) {
    error.value = readableError(reason);
  }
}

async function directGrant() {
  const user = selectedGrantUser.value;
  const plan = selectedGrantPlan.value;
  if (!user || !plan) return;
  try {
    const order = await createVipOrder(plan.id, user.id);
    await reviewVipOrder(order.id, 'paid');
    notice.value = `已为 ${userLabel(user)} 开通 ${plan.name}`;
    await refresh();
  } catch (reason) {
    error.value = readableError(reason);
  }
}

async function removeGrant(grant: VipGrant) {
  const target = users.value.find((item) => item.id === grant.user_id);
  if (!window.confirm(`确认从 ${target ? userLabel(target) : grant.username} 移除“${grant.plan_name}”？该套餐天数和独有权益会同时撤销。`)) return;
  try {
    await revokeVipGrant(grant.user_id, grant.id);
    notice.value = `已移除 ${grant.plan_name}，组合权益已重新计算`;
    await refresh();
  } catch (reason) {
    error.value = readableError(reason);
  }
}

function money(cents: number) { return `¥${(cents / 100).toFixed(2)}`; }
function date(ts?: number) { return ts ? new Date(ts * 1000).toLocaleString() : '-'; }
function userLabel(user: User) {
  return [user.nickname && user.nickname !== user.username ? user.nickname : '', `账号 ${user.username}`, user.phone ? `手机 ${user.phone}` : '']
    .filter(Boolean).join(' · ');
}
function userOrderLabel(order: VipOrder) {
  return [order.nickname, `账号 ${order.username}`, order.phone ? `手机 ${order.phone}` : ''].filter(Boolean).join(' · ');
}
function paymentLabel(order: VipOrder) {
  if (order.payment_status === 'paid') return '已支付';
  if (order.payment_status === 'failed') return '支付失败';
  return order.payment_method ? '待支付' : '人工订单';
}
function sourceLabel(source: VipOrder['source']) {
  return ({ manual: '后台人工', web: '网页', mobile: '手机', tv: '电视' } as const)[source] || source;
}
async function copy(value: string) {
  await navigator.clipboard.writeText(value);
  notice.value = '兑换码已复制';
}
</script>

<template>
  <div class="sales-page">
    <div class="toast-stack"><Message v-if="notice" type="success" :content="notice" /><Message v-if="error" type="warning" :content="error" /></div>
    <header class="page-header">
      <div><h1>会员与账单</h1><p>账号授权、自定义套餐、在线支付订单与兑换码统一管理</p></div>
      <Button :loading="loading" @click="refresh"><RefreshCw :size="17" /> 刷新</Button>
    </header>

    <Tabs v-model:active-key="activeTab" class="sales-tabs" :items="tabItems" />

    <template v-if="activeTab === 'overview'">
      <section class="band">
        <div class="section-title"><div><h2>给账号开通会员</h2><p>先确认目标账号，再选择套餐</p></div><span>管理员人工授权</span></div>
        <div class="grant-grid">
          <label class="field"><span>目标账号</span><Select v-model="grantUser" :options="grantUserOptions" :disabled="!grantableUsers.length" placeholder="请选择账号" /></label>
          <label class="field"><span>会员套餐</span><Select v-model="grantPlan" :options="activePlanOptions" placeholder="请选择套餐" /></label>
          <Button type="primary" :disabled="!selectedGrantUser || !selectedGrantPlan" @click="directGrant"><Check :size="16" /> 确认开通</Button>
        </div>
        <div v-if="selectedGrantUser && selectedGrantPlan" class="grant-confirm">
          <strong>{{ userLabel(selectedGrantUser) }}</strong>
          <span>将叠加 {{ selectedGrantPlan.name }} 的 {{ selectedGrantPlan.days }} 天；不同套餐功能取并集，设备额度取较高值。</span>
        </div>
        <p v-else class="empty-hint">没有可选账号时，请先到“账号”创建用户，或让用户在手机 App 注册。</p>
        <div v-if="selectedGrantUser" class="active-package-box">
          <div class="section-title compact"><div><h3>当前生效套餐</h3><p>按套餐逐项撤销，撤销后自动重算组合权益和剩余天数</p></div><span>{{ selectedUserGrants.length }} 个</span></div>
          <div v-if="selectedUserGrants.length" class="active-package-list">
            <div v-for="grant in selectedUserGrants" :key="grant.id" class="active-package" :style="{ '--plan-color': grant.accent_color }">
              <div><strong>{{ grant.plan_name }}</strong><small>{{ grant.days }} 天 · {{ grant.device_limit }} 台 · 到期 {{ date(grant.expires_at) }}</small></div>
              <Button size="small" danger title="撤销这个套餐" @click="removeGrant(grant)"><Trash2 :size="15" /> 移除</Button>
            </div>
          </div>
          <p v-else class="empty-hint">该账号当前没有生效套餐。</p>
        </div>
      </section>
      <section class="metrics">
        <div><span>在售套餐</span><strong>{{ activePlans.length }}</strong></div>
        <div><span>待处理订单</span><strong>{{ pending.length }}</strong></div>
        <div><span>已完成账单</span><strong>{{ paid.length }}</strong></div>
        <div><span>未使用兑换码</span><strong>{{ codes.filter((item) => item.status === 'unused').length }}</strong></div>
      </section>
    </template>

    <template v-if="activeTab === 'plans'">
      <section class="band plan-editor">
        <div class="section-title"><div><h2>{{ editingPlanId ? '编辑套餐' : '新建套餐' }}</h2><p>价格、时长、设备数、展示权益和实际功能权限均可独立配置</p></div><Button v-if="editingPlanId" type="text" @click="resetPlan"><X :size="16" /> 取消编辑</Button></div>
        <div class="form-grid">
          <label class="field"><span>套餐名称</span><Input v-model="planForm.name" placeholder="例如：家庭高级会员" /></label>
          <label class="field"><span>价格（元）</span><Input v-model="planForm.price" inputmode="decimal" placeholder="69.00" /></label>
          <label class="field"><span>有效天数</span><Input v-model="planForm.days" inputmode="numeric" placeholder="365" /></label>
          <label class="field"><span>授权设备数</span><Input v-model="planForm.device_limit" inputmode="numeric" placeholder="3" /></label>
          <label class="field"><span>会员样式</span><Select v-model="planForm.tag_style" :options="styleOptions" /></label>
          <label class="field"><span>主题色</span><div class="color-field"><input v-model="planForm.accent_color" type="color"><Input v-model="planForm.accent_color" maxlength="7" /></div></label>
          <label class="field"><span>会员角标</span><Input v-model="planForm.badge_text" maxlength="16" placeholder="超值 / 家庭 / 限时" /></label>
          <label class="field"><span>排序</span><Input v-model="planForm.sort_order" type="number" /></label>
          <label class="field wide"><span>套餐说明</span><Input v-model="planForm.description" maxlength="240" placeholder="一句话介绍使用场景" /></label>
          <label class="field wide"><span>权益文案（每行一项，可自由增减）</span><Textarea v-model="planForm.benefits_text" :rows="5" placeholder="公网远程投屏&#10;最多 3 台电视&#10;远程安装应用" /></label>
        </div>
        <div class="entitlements">
          <label v-for="[key, label] in entitlementLabels" :key="key"><span>{{ label }}</span><Switch v-model="planForm.entitlements[key]" checked-children="包含" un-checked-children="不含" /></label>
          <label><span>推荐套餐</span><Switch v-model="planForm.recommended" checked-children="推荐" un-checked-children="普通" /></label>
          <label><span>销售状态</span><Switch v-model="planForm.active" checked-children="销售" un-checked-children="停用" /></label>
        </div>
        <div class="editor-actions"><Button type="primary" :loading="savingPlan" @click="savePlan"><Save v-if="editingPlanId" :size="16" /><Plus v-else :size="16" />{{ editingPlanId ? '保存套餐' : '创建套餐' }}</Button></div>
      </section>
      <section class="band">
        <div class="section-title"><div><h2>现有套餐</h2><p>已产生的订单保留原套餐快照</p></div><span>{{ plans.length }} 个</span></div>
        <div class="plan-grid">
          <article v-for="plan in plans" :key="plan.id" class="plan-item" :style="{ '--plan-color': plan.accent_color }">
            <div class="plan-top"><div><span v-if="plan.badge_text" class="plan-badge">{{ plan.badge_text }}</span><strong>{{ plan.name }}</strong></div><b>{{ money(plan.price_cents) }}</b></div>
            <p>{{ plan.description || '暂无说明' }}</p>
            <div class="plan-facts"><span>{{ plan.days }} 天</span><span>{{ plan.device_limit }} 台设备</span><span>{{ plan.benefits.length }} 项权益</span></div>
            <ul><li v-for="benefit in plan.benefits.slice(0, 4)" :key="benefit">{{ benefit }}</li></ul>
            <div class="plan-actions"><Button size="small" @click="editPlan(plan)"><Edit3 :size="15" /> 编辑</Button><Switch v-model="plan.active" checked-children="销售" un-checked-children="停用" @update:model-value="(value: boolean) => togglePlan(plan, value)" /></div>
          </article>
        </div>
      </section>
    </template>

    <section v-if="activeTab === 'orders'" class="band">
      <div class="section-title"><div><h2>支付与授权账单</h2><p>目标账号、设备、渠道和发放状态完整留痕</p></div><span>{{ orders.length }} 条</span></div>
      <div class="table-scroll"><table class="island-table orders-table"><thead><tr><th>订单</th><th>开通对象</th><th>套餐</th><th>设备</th><th>渠道</th><th>付款 / 权益</th><th>金额</th><th>时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="order in orders" :key="order.id">
          <td class="mono">{{ order.order_no }}<small v-if="order.provider_trade_no">{{ order.provider_trade_no }}</small></td>
          <td><strong>{{ order.nickname || order.username }}</strong><small>账号 {{ order.username }}{{ order.phone ? ` · ${order.phone}` : '' }}</small></td>
          <td><Tag :class="`vip-${order.tag_style}`">{{ order.plan_name }}</Tag><small>{{ order.days }} 天 · {{ order.device_limit }} 台</small></td>
          <td>{{ order.device_name || '账号级授权' }}<small v-if="order.device_id" class="mono">{{ order.device_id }}</small></td>
          <td>{{ sourceLabel(order.source) }}<small>{{ order.payment_method === 'alipay' ? '支付宝' : order.payment_method === 'wxpay' ? '微信支付' : '人工' }}</small></td>
          <td><Tag :color="order.payment_status === 'paid' ? 'mint' : order.payment_status === 'failed' ? 'pink' : 'blue'">{{ paymentLabel(order) }}</Tag><small>{{ order.status === 'paid' ? '权益已发放' : order.status === 'cancelled' ? '订单已取消' : '待发放' }}</small></td>
          <td>{{ money(order.amount_cents) }}</td><td>{{ date(order.created_at) }}</td>
          <td class="actions"><template v-if="order.status === 'pending'"><Button size="small" type="primary" title="人工确认已收款并发放" @click="review(order, 'paid')"><Check :size="15" /> 生效</Button><Button size="small" danger @click="review(order, 'cancelled')"><X :size="15" /></Button></template><span v-else>-</span></td>
        </tr>
        <tr v-if="!orders.length"><td colspan="9" class="empty">暂无账单</td></tr>
      </tbody></table></div>
    </section>

    <template v-if="activeTab === 'codes'">
      <section class="band">
        <div class="section-title"><div><h2>生成兑换码</h2><p>兑换后发放套餐完整权益与设备额度</p></div><span>完整兑换码仅显示一次</span></div>
        <div class="code-form"><select v-model.number="codePlan" aria-label="选择套餐"><option :value="0" disabled>选择套餐</option><option v-for="option in codePlanOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><Input v-model="codeCount" inputmode="numeric" placeholder="数量 1-100" /><Button type="primary" :disabled="!codePlan" @click="makeCodes"><TicketPlus :size="16" /> 生成</Button></div>
        <div v-if="generated.length" class="code-list"><Button v-for="item in generated" :key="item.id" @click="copy(item.code)"><span>{{ item.code }}</span><Copy :size="15" /></Button></div>
      </section>
      <section class="band"><div class="section-title"><div><h2>兑换记录</h2><p>查看使用状态和兑换账号</p></div><span>{{ codes.length }} 条</span></div><div class="table-scroll"><table class="island-table"><thead><tr><th>兑换码</th><th>套餐</th><th>设备额度</th><th>状态</th><th>兑换账号</th><th>创建时间</th></tr></thead><tbody><tr v-for="code in codes" :key="code.id"><td class="mono">{{ code.code_hint }}</td><td>{{ code.plan_name }}</td><td>{{ code.device_limit }} 台</td><td><Tag :color="code.status === 'unused' ? 'blue' : 'mint'">{{ code.status === 'unused' ? '未使用' : '已兑换' }}</Tag></td><td>{{ code.redeemed_username || '-' }}</td><td>{{ date(code.created_at) }}</td></tr><tr v-if="!codes.length"><td colspan="6" class="empty">暂无兑换码</td></tr></tbody></table></div></section>
    </template>
  </div>
</template>

<style scoped lang="less">
.active-package-box{margin-top:18px;padding-top:18px;border-top:1px solid #e2e8e5}.section-title.compact{margin-bottom:10px}.section-title.compact h3{margin:0;font-size:15px}.active-package-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:9px}.active-package{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 12px;border:1px solid #d8e0dc;border-left:4px solid var(--plan-color);border-radius:6px;background:#fff}.active-package strong,.active-package small{display:block}.active-package small{margin-top:4px;color:var(--animal-text-color-secondary);font-size:11px}
.sales-page{max-width:1380px}.sales-tabs{display:flex;gap:4px;margin:2px 0 18px;border-bottom:1px solid var(--animal-border-color);overflow-x:auto}.sales-tabs button{height:44px;padding:0 18px;border:0;border-bottom:3px solid transparent;background:transparent;color:var(--animal-text-color-secondary);font:inherit;font-weight:700;white-space:nowrap;cursor:pointer}.sales-tabs button.active{border-bottom-color:#278a59;color:#1e6545}.sales-tabs span{display:inline-flex;min-width:20px;height:20px;align-items:center;justify-content:center;margin-left:5px;border-radius:10px;background:#e5ece8;font-size:11px}.band{padding:20px 0;border-top:1px solid var(--animal-border-color)}.section-title{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin-bottom:16px}.section-title h2{margin:0;font-size:18px}.section-title p{margin:5px 0 0;color:var(--animal-text-color-secondary);font-size:12px}.section-title>span{color:var(--animal-text-color-secondary);font-size:12px}.field{display:grid;gap:6px;min-width:0}.field>span{font-size:12px;font-weight:700;color:var(--animal-text-color-secondary)}select,textarea{width:100%;border:1px solid var(--animal-border-color);border-radius:6px;background:#fff;color:inherit;font:inherit}select{height:40px;padding:0 10px}textarea{padding:10px 12px;line-height:1.5;resize:vertical}.grant-grid{display:grid;grid-template-columns:minmax(280px,1.6fr) minmax(230px,1.2fr) auto;gap:12px;align-items:end}.grant-confirm{display:grid;gap:5px;margin-top:14px;padding:12px 14px;border-left:4px solid #278a59;background:#edf7f1}.grant-confirm span,.empty-hint{font-size:13px;color:var(--animal-text-color-secondary)}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:20px;padding:24px 0;border-top:1px solid var(--animal-border-color)}.metrics div{border-left:3px solid #a9cabe;padding:4px 14px}.metrics span,.metrics strong{display:block}.metrics span{font-size:12px;color:var(--animal-text-color-secondary)}.metrics strong{margin-top:7px;font-size:25px}.form-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.field.wide{grid-column:1/-1}.color-field{display:grid;grid-template-columns:42px minmax(0,1fr);gap:8px}.color-field input[type=color]{width:42px;height:40px;padding:2px;border:1px solid var(--animal-border-color);border-radius:6px;background:#fff}.entitlements{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px 22px;margin-top:16px;padding:14px 0;border-top:1px solid #e2e8e5;border-bottom:1px solid #e2e8e5}.entitlements label{display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:13px}.editor-actions{display:flex;justify-content:flex-end;margin-top:16px}.plan-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(255px,1fr));gap:12px}.plan-item{position:relative;padding:15px;border:1px solid #d8e0dc;border-top:4px solid var(--plan-color);border-radius:8px;background:#fff}.plan-top{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.plan-top strong{display:block;margin-top:5px}.plan-badge{display:inline-block;padding:2px 7px;border-radius:4px;background:var(--plan-color);color:#fff;font-size:10px}.plan-item p{min-height:36px;margin:9px 0;color:var(--animal-text-color-secondary);font-size:12px}.plan-facts{display:flex;gap:10px;flex-wrap:wrap;font-size:11px;color:#405249}.plan-item ul{min-height:60px;margin:12px 0;padding-left:18px;font-size:12px;line-height:1.65}.plan-actions{display:flex;justify-content:space-between;align-items:center;border-top:1px solid #edf0ee;padding-top:11px}.code-form{display:grid;grid-template-columns:minmax(220px,1.5fr) minmax(120px,.6fr) auto;gap:10px;align-items:center}.code-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:8px;margin-top:12px}.code-list button{display:flex;justify-content:space-between;border:1px solid var(--animal-border-color);background:#fff;border-radius:6px;padding:11px;font-family:monospace;cursor:pointer}.table-scroll{overflow:auto;max-width:100%}.island-table{min-width:900px}.orders-table{min-width:1280px}.island-table td small{display:block;margin-top:4px;color:var(--animal-text-color-secondary);font-size:10px}.mono{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}.actions{display:flex;gap:6px;white-space:nowrap}.empty{text-align:center;color:var(--animal-text-color-secondary);padding:26px}.vip-sky{background:#e5f1ff;color:#1769c2}.vip-mint{background:#dff7ef;color:#13745f}.vip-gold{background:#fff0c9;color:#9a6100}.vip-rose{background:#ffe4ec;color:#a43257}.vip-violet{background:#eee6ff;color:#6243a7}@media(max-width:980px){.form-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.entitlements{grid-template-columns:repeat(2,minmax(0,1fr))}.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:760px){.grant-grid,.code-form{grid-template-columns:1fr}.band{padding:16px 0}}@media(max-width:560px){.form-grid,.entitlements,.metrics{grid-template-columns:1fr}.sales-tabs button{padding:0 12px}}
</style>
