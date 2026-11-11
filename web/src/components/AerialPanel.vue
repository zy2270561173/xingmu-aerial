<script setup lang="ts">
/**
 * Aerial sheet: a 28px glass plate with a title column on the left and the controls on the right,
 * the same two-column rhythm as Aerial's Personalize sheet. Falls back to a solid plate where
 * backdrop-filter is unavailable (old WebViews, remote consoles).
 */
defineProps<{
  title: string;
  description?: string;
}>();
</script>

<template>
  <section class="aerial-panel">
    <header class="aerial-panel__head">
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
    </header>
    <div class="aerial-panel__body">
      <slot />
    </div>
  </section>
</template>

<style scoped lang="less">
.aerial-panel {
  position: relative;
  display: grid;
  grid-template-columns: minmax(200px, 280px) minmax(0, 1fr);
  gap: var(--aerial-space-6, 28px);
  align-items: start;
  margin-top: var(--aerial-space-4, 16px);
  padding: var(--aerial-space-6, 26px);
  overflow: hidden;
  border: 1px solid var(--glass-border, rgba(255, 255, 255, 0.15));
  border-radius: var(--radius-panel, 24px);
  background:
    linear-gradient(155deg, rgba(255, 255, 255, 0.07) 0%, transparent 42%),
    var(--glass-bg, rgba(10, 17, 24, 0.6));
  box-shadow: var(--glass-highlight, inset 0 1px 0 rgba(255, 255, 255, 0.1)), var(--glass-shadow, 0 22px 60px rgba(0, 0, 0, 0.42));
  -webkit-backdrop-filter: blur(var(--glass-blur-strong, 28px)) saturate(145%);
  backdrop-filter: blur(var(--glass-blur-strong, 28px)) saturate(145%);
}

.aerial-panel::before {
  content: "";
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.34), transparent);
}

.aerial-panel__head h2 {
  margin: 0;
  font-size: var(--aerial-text-title, 19px);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text, #f3fafc);
}

.aerial-panel__head p {
  margin: 6px 0 0;
  font-size: var(--aerial-text-callout, 12px);
  line-height: 1.55;
  color: var(--text-dim, rgba(226, 239, 244, 0.66));
}

.aerial-panel__body {
  min-width: 0;
}

@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .aerial-panel {
    background: var(--glass-bg-strong, rgba(8, 14, 20, 0.86));
  }
}

@media (max-width: 820px) {
  .aerial-panel {
    grid-template-columns: 1fr;
    gap: var(--aerial-space-4, 16px);
    padding: var(--aerial-space-4, 18px);
    border-radius: var(--radius-card, 20px);
  }
}
</style>
