<script setup lang="ts">
/**
 * One setting row: label and explanation on the left, the control on the right, separated by a
 * hairline. Mirrors Aerial's row rhythm (Headline 15 / Callout 13). The control keeps its own
 * aria-label, because the component kit renders switches as buttons, not as labelled inputs.
 */
const props = defineProps<{
  label: string;
  description?: string;
}>();
</script>

<template>
  <div class="aerial-row">
    <div class="aerial-row__text">
      <div class="aerial-row__label">{{ props.label }}</div>
      <p v-if="props.description" class="aerial-row__hint">{{ props.description }}</p>
    </div>
    <div class="aerial-row__control">
      <slot />
    </div>
  </div>
</template>

<style scoped lang="less">
.aerial-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--aerial-space-4, 16px);
  align-items: center;
  padding: 14px 0;
  border-bottom: 1px solid var(--aerial-hairline, rgba(255, 255, 255, 0.1));
}

.aerial-row:last-of-type {
  border-bottom: 0;
}

.aerial-row__label {
  display: block;
  font-size: var(--aerial-text-headline, 15px);
  font-weight: 600;
  color: var(--aerial-label, #fff);
}

.aerial-row__hint {
  margin: 4px 0 0;
  max-width: 42em;
  font-size: var(--aerial-text-callout, 13px);
  line-height: 1.5;
  color: var(--aerial-label-2, rgba(235, 235, 245, 0.6));
}

.aerial-row__control {
  display: flex;
  align-items: center;
  gap: var(--aerial-space-2, 8px);
}

@media (max-width: 640px) {
  .aerial-row {
    grid-template-columns: 1fr;
    align-items: start;
  }
}
</style>
