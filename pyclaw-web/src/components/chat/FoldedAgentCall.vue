<template>
  <div class="folded-call">
    <!-- AGENT_CALL_EVENT — expandable chip -->
    <div
      v-if="event.messageType === 'AGENT_CALL_EVENT'"
      class="call-chip"
      @click="expanded = !expanded"
    >
      <span class="call-chip-icon">{{ expanded ? '▼' : '▶' }}</span>
      <span class="call-chip-text">
        调用了 {{ meta.targetRoleKey || 'Agent' }}
        <span class="call-chip-status" :class="meta.status?.toLowerCase()">{{ meta.status }}</span>
      </span>
    </div>

    <!-- TOOL_RESULT_DETAIL — shown inside the fold when expanded -->
    <div v-if="expanded && event.messageType === 'TOOL_RESULT_DETAIL'" class="call-detail">
      <div class="call-detail-label">{{ event.roleKey || 'Agent' }} 的结果：</div>
      <div class="call-detail-content">{{ event.content }}</div>
    </div>

    <!-- TOOL_APPROVAL_CARD — inline approve/reject -->
    <div v-if="event.messageType === 'TOOL_APPROVAL_CARD'" class="approval-inline">
      <div class="approval-inline-header">
        <AppTag :tone="riskTone" size="sm">
          {{ meta.risk || 'medium' }}
        </AppTag>
        <span class="approval-inline-agent">
          {{ meta.executingRoleKey || event.roleKey || 'Agent' }} 请求执行 {{ meta.toolName || '工具' }}
        </span>
      </div>
      <div v-if="meta.callingRoleKey" class="approval-inline-source">
        来源：由 {{ meta.callingRoleKey }} 调用
      </div>
      <div class="approval-inline-intent" v-if="meta.intent">
        {{ meta.intent }}
      </div>
      <div class="approval-inline-actions" v-if="meta.status === 'PENDING'">
        <input
          v-model="rejectReason"
          class="approval-inline-reason"
          placeholder="拒绝原因（可选）"
          :disabled="resolving"
        />
        <AppButton variant="ghost" size="sm" :disabled="resolving" @click="handleReject">
          拒绝
        </AppButton>
        <AppButton variant="primary" size="sm" :loading="resolving" @click="handleApprove">
          批准
        </AppButton>
      </div>
      <div v-if="error" class="approval-inline-error">{{ error }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from "vue";
import AppTag from "../ui/AppTag.vue";
import AppButton from "../ui/AppButton.vue";

const props = defineProps({
  event: { type: Object, required: true },
  clawId: { type: String, required: true },
});

const emit = defineEmits(["approve", "reject"]);

const expanded = ref(false);
const resolving = ref(false);
const rejectReason = ref("");
const error = ref("");

const meta = computed(() => {
  if (!props.event.metadataJson) return {};
  try {
    return JSON.parse(props.event.metadataJson);
  } catch {
    return {};
  }
});

const riskTone = computed(() => {
  const risk = meta.value.risk || "medium";
  if (risk === "high") return "danger";
  if (risk === "medium") return "warning";
  return "info";
});

async function handleApprove() {
  resolving.value = true;
  error.value = "";
  try {
    emit("approve", props.event);
  } catch (e) {
    error.value = "审批失败: " + e.message;
  } finally {
    resolving.value = false;
  }
}

async function handleReject() {
  resolving.value = true;
  error.value = "";
  try {
    emit("reject", props.event, rejectReason.value);
  } catch (e) {
    error.value = "拒绝失败: " + e.message;
  } finally {
    resolving.value = false;
  }
}
</script>

<style scoped>
.folded-call {
  margin-top: 4px;
}

.call-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: rgba(245, 168, 61, 0.08);
  border: 1px solid rgba(245, 168, 61, 0.2);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 12px;
  transition: background 0.15s var(--ease-out);
}
.call-chip:hover {
  background: rgba(245, 168, 61, 0.14);
}
.call-chip-icon {
  font-size: 10px;
  color: var(--accent);
}
.call-chip-text {
  color: var(--text-secondary);
}
.call-chip-status {
  margin-left: 6px;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
}
.call-chip-status.completed {
  background: rgba(0, 200, 83, 0.15);
  color: #00c853;
}
.call-chip-status.pending_approval {
  background: rgba(245, 168, 61, 0.15);
  color: var(--accent);
}

.call-detail {
  margin-top: 6px;
  padding: 8px 12px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 13px;
}
.call-detail-label {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  margin-bottom: 4px;
}
.call-detail-content {
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.approval-inline {
  margin-top: 6px;
  padding: 10px 12px;
  background: var(--bg-deep);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 13px;
}
.approval-inline-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.approval-inline-agent {
  color: var(--text-primary);
  font-weight: 600;
  font-size: 12px;
}
.approval-inline-source {
  color: var(--text-muted);
  font-size: 11px;
  margin-bottom: 4px;
}
.approval-inline-intent {
  color: var(--text-secondary);
  font-size: 12px;
  margin-bottom: 8px;
}
.approval-inline-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.approval-inline-reason {
  flex: 1;
  padding: 4px 8px;
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 12px;
  font-family: inherit;
  min-width: 0;
}
.approval-inline-reason:focus {
  outline: none;
  border-color: var(--accent);
}
.approval-inline-reason:disabled {
  opacity: 0.5;
}
.approval-inline-error {
  color: var(--danger);
  font-size: 11px;
  margin-top: 6px;
}
</style>
