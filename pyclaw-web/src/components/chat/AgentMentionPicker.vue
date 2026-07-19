<template>
  <div class="mention-picker" v-if="filteredAgents.length">
    <div
      v-for="agent in filteredAgents"
      :key="agent.agentInstanceId || agent.roleKey"
      class="mention-item"
      :class="{ active: activeIndex === agent.agentInstanceId }"
      @click="$emit('select', agent)"
      @mouseenter="activeIndex = agent.agentInstanceId"
    >
      <span class="mention-name">{{ agent.displayName }}</span>
      <span class="mention-role">@{{ agent.roleKey }}</span>
      <span v-if="agent.defaultRole" class="mention-default">默认</span>
    </div>
  </div>
  <div v-else class="mention-picker mention-empty">
    <span class="mention-empty-text">无匹配的 Agent</span>
  </div>
</template>

<script setup>
import { computed, ref } from "vue";

const props = defineProps({
  agents: { type: Array, default: () => [] },
  filter: { type: String, default: "" },
});

defineEmits(["select", "close"]);

const activeIndex = ref(null);

const filteredAgents = computed(() => {
  if (!props.filter) return props.agents;
  const f = props.filter.toLowerCase();
  return props.agents.filter(
    a =>
      a.displayName?.toLowerCase().includes(f) ||
      a.roleKey?.toLowerCase().includes(f)
  );
});
</script>

<style scoped>
.mention-picker {
  position: absolute;
  bottom: 100%;
  left: 0;
  right: 0;
  margin-bottom: 8px;
  background: rgba(14, 17, 23, 0.95);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  max-height: 200px;
  overflow-y: auto;
  z-index: 10;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.5);
}

.mention-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  cursor: pointer;
  transition: background 0.15s var(--ease-out);
  font-size: 13px;
}
.mention-item:hover,
.mention-item.active {
  background: var(--accent-glow);
}
.mention-name {
  font-weight: 600;
  color: var(--text-primary);
}
.mention-role {
  color: var(--accent);
  font-size: 12px;
}
.mention-default {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 10px;
  background: var(--accent-glow);
  color: var(--accent);
  margin-left: auto;
}
.mention-empty {
  padding: 12px 14px;
}
.mention-empty-text {
  color: var(--text-muted);
  font-size: 12px;
}
</style>
