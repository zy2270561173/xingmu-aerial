<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { Avatar, Button, Empty, Input, Message, Modal, Switch, Tag } from 'animal-island-ui-vue';
import { Edit3, ImageUp, KeyRound, Plus, RefreshCw, Trash2, Users } from '@lucide/vue';
import { createUser, deleteUser, getUsers, readableError, updateUser, uploadUserAvatar } from '../api';
import type { User } from '../types';

const users = ref<User[]>([]);
const loading = ref(false);
const saving = ref(false);
const createOpen = ref(false);
const editUser = ref<User | null>(null);
const resetUser = ref<User | null>(null);
const deleteTarget = ref<User | null>(null);
const resetPassword = ref('');
const notice = ref('');
const error = ref('');
const createForm = reactive({ username: '', password: '', nickname: '', phone: '' });
const editForm = reactive({ nickname: '', phone: '' });

async function refresh() {
  loading.value = true;
  try { users.value = await getUsers(); }
  catch (reason) { error.value = readableError(reason); }
  finally { loading.value = false; }
}

onMounted(refresh);

function validPhone(phone: string) {
  return !phone || /^\+?[0-9]{6,20}$/.test(phone);
}

async function submitCreate() {
  error.value = '';
  if (!/^[A-Za-z0-9_.-]{3,64}$/.test(createForm.username.trim()) || createForm.password.length < 8) {
    error.value = '账号限 3-64 位字母、数字、点、横线或下划线，密码至少 8 位';
    return;
  }
  if (!validPhone(createForm.phone.trim())) { error.value = '手机号格式不正确'; return; }
  saving.value = true;
  try {
    await createUser({ ...createForm, username: createForm.username.trim(), nickname: createForm.nickname.trim(), phone: createForm.phone.trim() });
    Object.assign(createForm, { username: '', password: '', nickname: '', phone: '' });
    createOpen.value = false;
    notice.value = '用户账号已创建';
    await refresh();
  } catch (reason) { error.value = readableError(reason); }
  finally { saving.value = false; }
}

function openEdit(user: User) {
  editUser.value = user;
  editForm.nickname = user.nickname || '';
  editForm.phone = user.phone || '';
}

async function submitEdit() {
  if (!editUser.value || !validPhone(editForm.phone.trim())) { error.value = '手机号格式不正确'; return; }
  saving.value = true;
  try {
    Object.assign(editUser.value, await updateUser(editUser.value.id, { nickname: editForm.nickname.trim(), phone: editForm.phone.trim() }));
    editUser.value = null;
    notice.value = '用户资料已更新';
  } catch (reason) { error.value = readableError(reason); }
  finally { saving.value = false; }
}

async function toggleActive(user: User, active: boolean) {
  try { Object.assign(user, await updateUser(user.id, { active })); }
  catch (reason) { error.value = readableError(reason); }
}

async function selectAvatar(user: User, event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  saving.value = true;
  try {
    Object.assign(user, await uploadUserAvatar(user.id, file));
    notice.value = '头像已更新';
  } catch (reason) { error.value = readableError(reason); }
  finally { saving.value = false; }
}

async function submitReset() {
  if (!resetUser.value || resetPassword.value.length < 8) { error.value = '新密码至少 8 位'; return; }
  saving.value = true;
  try {
    await updateUser(resetUser.value.id, { password: resetPassword.value });
    resetUser.value = null;
    resetPassword.value = '';
    notice.value = '密码已重置';
  } catch (reason) { error.value = readableError(reason); }
  finally { saving.value = false; }
}

async function confirmDelete() {
  if (!deleteTarget.value) return;
  saving.value = true;
  try {
    const result = await deleteUser(deleteTarget.value.id);
    notice.value = result.unbound_device_ids.length ? `账号已删除，${result.unbound_device_ids.length} 台设备已解除绑定` : '账号已删除';
    deleteTarget.value = null;
    await refresh();
  } catch (reason) { error.value = readableError(reason); }
  finally { saving.value = false; }
}
</script>

<template>
  <div>
    <div class="toast-stack"><Message v-if="notice" type="success" :content="notice" /><Message v-if="error" type="warning" :content="error" /></div>
    <header class="page-header">
      <div><h1>用户管理</h1><p>管理资料、登录权限和设备归属；普通用户只能访问自己的电视</p></div>
      <div class="toolbar"><Button :loading="loading" @click="refresh"><RefreshCw :size="17" /> 刷新</Button><Button type="primary" @click="createOpen = true"><Plus :size="17" /> 新建用户</Button></div>
    </header>
    <div v-if="users.length" class="island-table-wrap">
      <table class="island-table users-table">
        <thead><tr><th>用户</th><th>手机号</th><th>会员</th><th>身份</th><th>设备</th><th>状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="user in users" :key="user.id">
          <td><div class="identity"><Avatar :src="user.avatar_url || undefined" :alt="user.nickname || user.username" size="default" /><div><strong>{{ user.nickname || user.username }}</strong><span>@{{ user.username }} · ID {{ user.id }}</span></div></div></td>
          <td class="mono">{{ user.phone || '未填写' }}</td>
          <td><Tag v-if="user.vip_active" :class="`vip-${user.vip_tag_style}`">{{ user.vip_plan_name || 'VIP' }}</Tag><span v-else class="muted">未开通</span></td>
          <td><Tag :color="user.role === 'admin' ? 'yellow' : 'blue'">{{ user.role === 'admin' ? '超级管理员' : '普通用户' }}</Tag></td>
          <td>{{ user.device_count || 0 }} 台<span v-if="user.pending_count" class="pending"> · {{ user.pending_count }} 待审批</span></td>
          <td><Switch :model-value="user.active !== false" :disabled="user.role === 'admin'" checked-children="允许" un-checked-children="停用" @update:model-value="(value: boolean) => toggleActive(user, value)" /></td>
          <td><div class="actions">
            <Button size="small" title="编辑资料" @click="openEdit(user)"><Edit3 :size="15" /></Button>
            <label class="icon-upload" title="上传头像"><ImageUp :size="15" /><input type="file" accept="image/png,image/jpeg,image/webp" @change="selectAvatar(user, $event)" /></label>
            <Button size="small" title="重置密码" @click="resetUser = user"><KeyRound :size="15" /></Button>
            <Button v-if="user.role !== 'admin'" size="small" danger title="删除账号" @click="deleteTarget = user"><Trash2 :size="15" /></Button>
          </div></td>
        </tr></tbody>
      </table>
    </div>
    <div v-else-if="!loading" class="empty-band"><Empty title="暂无用户" description="新建用户后即可分配电视设备" /></div>

    <Modal v-model:open="createOpen" title="新建用户" :closable="!saving">
      <div class="form-grid"><label>登录账号<Input v-model="createForm.username" placeholder="例如 family" /></label><label>手机号<Input v-model="createForm.phone" inputmode="tel" placeholder="可用于登录和设备绑定" /></label><label>昵称<Input v-model="createForm.nickname" placeholder="例如 爸爸" /></label><label>初始密码<Input v-model="createForm.password" type="password" placeholder="至少 8 位" /></label></div>
      <template #footer><Button :disabled="saving" @click="createOpen = false">取消</Button><Button type="primary" :loading="saving" @click="submitCreate"><Users :size="17" /> 创建</Button></template>
    </Modal>
    <Modal :open="Boolean(editUser)" title="编辑用户资料" :closable="!saving" @update:open="(value: boolean) => { if (!value) editUser = null }">
      <div class="form-grid"><label>昵称<Input v-model="editForm.nickname" /></label><label>手机号<Input v-model="editForm.phone" inputmode="tel" /></label></div>
      <template #footer><Button @click="editUser = null">取消</Button><Button type="primary" :loading="saving" @click="submitEdit">保存</Button></template>
    </Modal>
    <Modal :open="Boolean(resetUser)" title="重置用户密码" :closable="!saving" @update:open="(value: boolean) => { if (!value) resetUser = null }">
      <p>用户：<strong>{{ resetUser?.nickname || resetUser?.username }}</strong></p><div class="form-grid"><label>新密码<Input v-model="resetPassword" type="password" placeholder="至少 8 位" /></label></div>
      <template #footer><Button @click="resetUser = null">取消</Button><Button type="primary" :loading="saving" @click="submitReset">保存新密码</Button></template>
    </Modal>
    <Modal :open="Boolean(deleteTarget)" title="删除用户" :closable="!saving" @update:open="(value: boolean) => { if (!value) deleteTarget = null }">
      <p>确认删除 <strong>{{ deleteTarget?.nickname || deleteTarget?.username }}</strong>？该用户的电视会解除绑定并回到待审批，不会删除电视记录。</p>
      <template #footer><Button @click="deleteTarget = null">取消</Button><Button danger :loading="saving" @click="confirmDelete"><Trash2 :size="16" /> 删除用户</Button></template>
    </Modal>
  </div>
</template>

<style scoped lang="less">
.users-table { min-width: 980px; }
.identity { display: flex; align-items: center; gap: 10px; min-width: 190px; }
.identity div { display: grid; gap: 3px; }
.identity span, .pending { color: var(--animal-text-color-secondary); font-size: 12px; }
.actions { display: flex; gap: 6px; white-space: nowrap; }
.icon-upload { width: 34px; height: 32px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--animal-border-color); border-radius: 6px; cursor: pointer; color: var(--animal-text-color-secondary); }
.icon-upload:hover { color: var(--animal-primary-color); border-color: var(--animal-primary-color); }
.icon-upload input { display: none; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-grid label { display: grid; gap: 6px; color: var(--animal-text-color-secondary); font-size: 12px; font-weight: 700; }
:deep(.vip-sky) { background:#e5f1ff;color:#1769c2; } :deep(.vip-mint) { background:#dff7ef;color:#13745f; } :deep(.vip-gold) { background:#fff0c9;color:#9a6100; } :deep(.vip-rose) { background:#ffe4ec;color:#a43257; } :deep(.vip-violet) { background:#eee6ff;color:#6243a7; }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } }
</style>
