<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Button, Form, FormItem, Input, Message } from 'animal-island-ui-vue';
import { RadioTower } from '@lucide/vue';
import { readableError } from '../api';
import { useAuthStore } from '../stores/auth';

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const form = reactive({ username: 'admin', password: '' });
const error = ref('');

async function submit() {
  error.value = '';
  if (!form.username.trim() || !form.password) {
    error.value = '请输入账号和密码';
    return;
  }
  try {
    await auth.login(form.username.trim(), form.password);
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/devices';
    await router.replace(redirect);
    window.location.reload();
  } catch (reason) {
    error.value = readableError(reason);
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <header>
        <span class="login-mark"><RadioTower :size="26" /></span>
        <div><h1>星投屏控制中心</h1><p>设备、信令与 Agent 管理</p></div>
      </header>
      <Message v-if="error" type="warning" :content="error" />
      <Form layout="vertical" @submit.prevent="submit">
        <FormItem label="账号"><Input v-model="form.username" size="large" allow-clear autocomplete="username" /></FormItem>
        <FormItem label="密码"><Input v-model="form.password" size="large" type="password" autocomplete="current-password" @keyup.enter="submit" /></FormItem>
        <Button type="primary" size="large" block :loading="auth.loading" html-type="submit">登录</Button>
      </Form>
    </section>
  </main>
</template>

<style scoped lang="less">
.login-page { min-height: 100vh; display: grid; place-items: center; padding: 24px; background: #e9eeea; }
.login-panel { width: min(420px, 100%); padding: 28px; border: 1px solid #d4ddd8; border-radius: 8px; background: white; box-shadow: 0 12px 34px rgba(31, 49, 42, .1); }
header { display: flex; align-items: center; gap: 13px; margin-bottom: 26px; }
.login-mark { width: 44px; height: 44px; display: grid; place-items: center; border-radius: 8px; color: #114e45; background: #9dd6ca; }
h1 { margin: 0; font-size: 21px; letter-spacing: 0; }
p { margin: 4px 0 0; color: var(--animal-text-color-secondary); font-size: 13px; }
:deep(.animal-message) { margin-bottom: 16px; }
</style>
