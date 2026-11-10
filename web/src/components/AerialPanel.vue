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
  display: grid;
  grid-template-columns: minmax(200px, 280px) minmax(0, 1fr);
  gap: var(--aerial-space-6, 28px);
  align-items: start;
  margin-top: var(--aerial-space-4, 16px);
  padding: var(--aerial-space-6, 28px);
  border: 1px solid var(--aerial-hairline, rgba(255, 255, 255, 0.1));
  border-radius: var(--aerial-radius-sheet, 28px);
  background: var(--aerial-glass-soft, rgba(28, 28, 30, 0.72));
  box-shadow: var(--aerial-shadow, 0 18px 48px rgba(0, 0, 0, 0.55));
  backdrop-filter: blur(28px) saturate(150%);
}

.aerial-panel__head h2 {
  margin: 0;
  font-size: var(--aerial-text-title, 19px);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--aerial-label, #fff);
}

.aerial-panel__head p {
  margin: 6px 0 0;
  font-size: var(--aerial-text-callout, 13px);
  line-height: 1.5;
  color: var(--aerial-label-2, rgba(235, 235, 245, 0.6));
}

.aerial-panel__body {
  min-width: 0;
}

@supports not (backdrop-filter: blur(1px)) {
  .aerial-panel {
    background: var(--aerial-glass, rgba(32, 32, 34, 0.9));
  }
}

@media (max-width: 820px) {
  .aerial-panel {
    grid-template-columns: 1fr;
    gap: var(--aerial-space-4, 16px);
    padding: var(--aerial-space-4, 16px);
    border-radius: var(--aerial-radius-panel, 24px);
  }
}
</style>
