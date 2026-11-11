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
        <div class="login-copy">
          <span class="login-eyebrow">AERIAL CONSOLE</span>
          <h1>星幕控制中心</h1>
          <p>设备、信令与 Agent 管理</p>
        </div>
      </header>
      <Message v-if="error" type="warning" :content="error" />
      <Form layout="vertical" @submit.prevent="submit">
        <FormItem label="账号"><Input v-model="form.username" size="large" allow-clear autocomplete="username" /></FormItem>
        <FormItem label="密码"><Input v-model="form.password" size="large" type="password" autocomplete="current-password" @keyup.enter="submit" /></FormItem>
        <Button type="primary" size="large" block :loading="auth.loading" html-type="submit">登录</Button>
      </Form>
      <p class="login-footnote">深色玻璃界面 · 管理会话加密传输</p>
    </section>
  </main>
</template>

<style scoped lang="less">
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: transparent;
}

.login-panel {
  width: min(440px, 100%);
  padding: 36px;
  border: 1px solid var(--glass-border, rgba(255, 255, 255, 0.15));
  border-radius: 28px;
  background:
    linear-gradient(155deg, rgba(255, 255, 255, 0.075) 0%, transparent 42%),
    linear-gradient(180deg, rgba(14, 24, 33, 0.78) 0%, rgba(7, 13, 19, 0.70) 100%);
  box-shadow:
    var(--glass-highlight, inset 0 1px 0 rgba(255, 255, 255, 0.1)),
    0 30px 80px rgba(0, 0, 0, 0.50);
  -webkit-backdrop-filter: blur(var(--glass-blur-strong, 28px)) saturate(150%);
  backdrop-filter: blur(var(--glass-blur-strong, 28px)) saturate(150%);
}

header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
}

.login-copy {
  min-width: 0;
}

.login-eyebrow {
  display: block;
  margin-bottom: 4px;
  color: var(--accent, #3fd0c9);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.login-mark {
  width: 50px;
  height: 50px;
  flex: 0 0 50px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.34);
  border-radius: 15px;
  color: #041518;
  background: var(--accent-gradient, linear-gradient(135deg, #3fd0c9, #2f80ed));
  box-shadow: 0 12px 30px rgba(47, 128, 237, 0.30), inset 0 1px 0 rgba(255, 255, 255, 0.48);
}

h1 {
  margin: 0;
  color: var(--text, #f3fafc);
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.025em;
}

p {
  margin: 5px 0 0;
  color: var(--text-dim, rgba(226, 239, 244, 0.66));
  font-size: 12px;
}

.login-footnote {
  margin-top: 18px;
  color: var(--text-faint, rgba(226, 239, 244, 0.42));
  font-size: 10px;
  letter-spacing: 0.06em;
  text-align: center;
}

:deep(.animal-message) {
  margin-bottom: 16px;
}

@media (max-width: 520px) {
  .login-panel {
    padding: 24px;
    border-radius: 24px;
  }

  h1 {
    font-size: 23px;
  }
}
</style>
