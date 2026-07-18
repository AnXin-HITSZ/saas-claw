<template>
  <router-view v-slot="{ Component }">
    <transition name="page" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
  <AppToast />
</template>

<script setup>
import AppToast from "./components/ui/AppToast.vue";
</script>

<style>
/* ── Design Tokens: Deep Space Console ── */
:root {
  --bg-abyss: #05070c;
  --bg-deep: #0a0e16;
  --bg-surface: #10151f;
  --bg-raised: #161c29;
  --bg-hover: #1b2230;
  --border: #232b3a;
  --border-light: #2e3850;
  --text-primary: #e6eaf2;
  --text-secondary: #8b96ab;
  --text-muted: #5b6579;
  --accent: #f5a83d;
  --accent-soft: #d98f2b;
  --accent-2: #4dd0e1;
  --accent-3: #8b7cf6;
  --gradient-aurora: linear-gradient(135deg, #f5a83d, #e0637c 45%, #8b7cf6);
  --accent-glow: rgba(245, 168, 61, 0.14);
  --accent-glow-strong: rgba(245, 168, 61, 0.28);
  --glow-accent: 0 0 24px rgba(245, 168, 61, 0.25);
  --glow-cyan: 0 0 24px rgba(77, 208, 225, 0.18);
  --success: #3fce6c;
  --danger: #ff5c5c;
  --warning: #e0a832;
  --info: #4dd0e1;
  --radius-sm: 8px;
  --radius: 14px;
  --radius-lg: 18px;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.35);
  --shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  --shadow-glow: var(--glow-accent);
  --ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  --ease-in-out: cubic-bezier(0.65, 0, 0.35, 1);
  --ease-spring: cubic-bezier(0.34, 1.56, 0.64, 1);
  --font-display: "Space Grotesk", "Inter", -apple-system, sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, monospace;
  --topbar-height: 56px;
  --content-max-width: 1100px;
  --content-gutter: 48px;
  --card-padding: 24px;
  --section-gap: 24px;
  /* Backward-compatible aliases（旧页面未改完前不报错） */
  --bg-primary: var(--bg-deep);
  --bg-secondary: var(--bg-surface);
  --bg-tertiary: var(--bg-raised);
  --border-color: var(--border);
  --accent-hover: var(--accent-soft);
}

* { margin: 0; padding: 0; box-sizing: border-box; }

body {
  font-family: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
  background-color: var(--bg-abyss);
  color: var(--text-primary);
  line-height: 1.6;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

/* 全局深空背景：细网格 + 顶部极光光晕 */
body::before {
  content: "";
  position: fixed;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  background:
    radial-gradient(ellipse 60% 40% at 70% -5%, rgba(245, 168, 61, 0.10), transparent 60%),
    radial-gradient(ellipse 50% 35% at 15% 0%, rgba(139, 124, 246, 0.08), transparent 60%),
    radial-gradient(ellipse 45% 30% at 45% 110%, rgba(77, 208, 225, 0.05), transparent 60%),
    linear-gradient(rgba(255, 255, 255, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.025) 1px, transparent 1px),
    var(--bg-abyss);
  background-size: auto, auto, auto, 44px 44px, 44px 44px, auto;
}

a { color: var(--accent); text-decoration: none; transition: color 0.2s var(--ease-out); }
a:hover { color: var(--accent-soft); }
button { cursor: pointer; font-family: inherit; }
input, textarea, select { font-family: inherit; }

::selection { background: rgba(245, 168, 61, 0.28); }

/* ── Scrollbar ── */
::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
::-webkit-scrollbar-thumb:hover { background: var(--border-light); }

/* ── Page transition ── */
.page-enter-active { transition: opacity 0.22s var(--ease-out), transform 0.22s var(--ease-out); }
.page-leave-active { transition: opacity 0.14s var(--ease-out), transform 0.14s var(--ease-out); }
.page-enter-from { opacity: 0; transform: translateY(10px); }
.page-leave-to { opacity: 0; transform: translateY(-4px); }

/* ── Staggered card enter ── */
.stagger-enter-active { transition: opacity 0.4s var(--ease-out), transform 0.4s var(--ease-out); }
.stagger-enter-from { opacity: 0; transform: translateY(16px); }

/* ── Shared base styles ── */
.page { max-width: 1000px; }
.page-header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-header h1 {
  font-family: var(--font-display);
  font-size: 24px; font-weight: 700; letter-spacing: -0.02em; flex: 1;
}

.btn-primary {
  padding: 9px 22px; font-size: 14px; font-weight: 600; color: #0a0e14;
  background: linear-gradient(135deg, var(--accent), var(--accent-soft));
  border: none; border-radius: 10px;
  transition: all 0.22s var(--ease-out);
  position: relative; overflow: hidden;
}
.btn-primary::after {
  content: ""; position: absolute; inset: 0;
  background: linear-gradient(135deg, transparent 40%, rgba(255, 255, 255, 0.16) 50%, transparent 60%);
  transform: translateX(-100%); transition: transform 0.45s var(--ease-out);
}
.btn-primary:hover::after { transform: translateX(100%); }
.btn-primary:hover { transform: translateY(-1px); box-shadow: var(--glow-accent); }
.btn-primary:active { transform: translateY(0); }
.btn-primary:disabled { opacity: 0.4; pointer-events: none; }

.btn-secondary {
  padding: 9px 22px; font-size: 14px; color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.03); border: 1px solid var(--border); border-radius: 10px;
  transition: all 0.22s var(--ease-out);
}
.btn-secondary:hover { background: var(--bg-raised); border-color: var(--border-light); color: var(--text-primary); }

.btn-back {
  padding: 6px 14px; font-size: 13px; color: var(--text-muted);
  background: transparent; border: 1px solid var(--border); border-radius: 10px;
  transition: all 0.2s var(--ease-out);
}
.btn-back:hover { color: var(--text-secondary); border-color: var(--border-light); background: var(--bg-surface); }

.card {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.02), transparent 40%), var(--bg-surface);
  border: 1px solid var(--border); border-radius: var(--radius);
  padding: var(--card-padding); transition: all 0.28s var(--ease-out);
  position: relative; box-shadow: var(--shadow-sm);
}
.card::before {
  content: ""; position: absolute; top: 0; left: 14px; right: 14px; height: 1px;
  background: var(--gradient-aurora);
  opacity: 0; transition: opacity 0.3s var(--ease-out);
}
.card:hover { border-color: var(--border-light); transform: translateY(-2px); box-shadow: var(--shadow); }
.card:hover::before { opacity: 0.7; }

.card h3 { font-size: 15px; font-weight: 600; margin-bottom: 16px; letter-spacing: -0.01em; }

.status-tag { font-size: 11px; padding: 3px 11px; border-radius: 999px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
.status-tag.active { background: rgba(63, 206, 108, 0.12); color: var(--success); }
.status-tag.inactive { background: rgba(255, 92, 92, 0.1); color: var(--danger); }

.loading, .no-data { text-align: center; padding: 48px; color: var(--text-muted); font-size: 14px; }
.error-msg { text-align: center; padding: 48px; color: var(--danger); font-size: 14px; }

.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 6px; font-size: 13px; color: var(--text-secondary); font-weight: 500; }
.form-group input, .form-group textarea, .form-group select {
  width: 100%; padding: 10px 14px; background: var(--bg-deep); border: 1px solid var(--border);
  border-radius: 10px; color: var(--text-primary); font-size: 14px;
  transition: border-color 0.2s var(--ease-out), box-shadow 0.2s var(--ease-out);
}
.form-group input:focus, .form-group textarea:focus, .form-group select:focus {
  outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow);
}

.modal-overlay {
  position: fixed; inset: 0; background: rgba(3, 5, 9, 0.72); display: flex;
  align-items: center; justify-content: center; z-index: 100;
  backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px);
}
.modal {
  background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-lg);
  padding: 32px; width: 480px; max-width: 90vw;
  box-shadow: var(--shadow), 0 0 40px rgba(139, 124, 246, 0.08);
  animation: modal-enter 0.28s var(--ease-spring);
}
.modal h2 { margin-bottom: 20px; font-family: var(--font-display); font-weight: 700; letter-spacing: -0.02em; }
.modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }

label.switch-line {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  margin: 4px 0 16px; color: var(--text-secondary); font-size: 13px; font-weight: 600; cursor: pointer;
}
.switch-label { min-width: 0; }
.switch-input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.switch-track {
  position: relative; width: 36px; height: 20px; flex: 0 0 auto; border-radius: 999px;
  background: var(--bg-deep); border: 1px solid var(--border-light);
  transition: background 0.18s var(--ease-out), border-color 0.18s var(--ease-out), box-shadow 0.18s var(--ease-out);
}
.switch-track::after {
  content: ""; position: absolute; top: 2px; left: 2px; width: 14px; height: 14px; border-radius: 999px;
  background: var(--text-secondary);
  transition: transform 0.18s var(--ease-out), background 0.18s var(--ease-out);
}
.switch-input:checked + .switch-track { background: var(--accent); border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }
.switch-input:checked + .switch-track::after { transform: translateX(16px); background: #fff; }
.switch-input:focus-visible + .switch-track { outline: 2px solid var(--accent); outline-offset: 2px; }

@keyframes modal-enter {
  from { opacity: 0; transform: scale(0.95) translateY(10px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

/* ── Skeleton shimmer ── */
@keyframes shimmer {
  0% { background-position: -200% 0; }
  100% { background-position: 200% 0; }
}
.skeleton {
  border-radius: var(--radius-sm);
  background: linear-gradient(90deg, var(--bg-raised) 25%, var(--bg-hover) 50%, var(--bg-raised) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.6s var(--ease-in-out) infinite;
}

/* ── Table base ── */
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th {
  text-align: left; padding: 11px 14px; background: var(--bg-surface);
  color: var(--text-muted); font-weight: 600; font-size: 11px;
  text-transform: uppercase; letter-spacing: 0.5px;
  border-bottom: 1px solid var(--border);
}
.data-table td { padding: 11px 14px; border-bottom: 1px solid var(--border); color: var(--text-primary); }
.data-table tbody tr { transition: background 0.15s var(--ease-out); }
.data-table tbody tr:hover { background: var(--bg-hover); }
.data-table tbody tr:nth-child(even) { background: rgba(255, 255, 255, 0.015); }
.data-table tbody tr:nth-child(even):hover { background: var(--bg-hover); }

/* ── Empty state ── */
.empty-state { text-align: center; padding: 64px 24px; }
.empty-state-icon { font-size: 44px; margin-bottom: 16px; opacity: 0.5; }
.empty-state h3 { font-size: 17px; font-weight: 600; margin-bottom: 6px; color: var(--text-secondary); }
.empty-state p { font-size: 14px; color: var(--text-muted); max-width: 420px; margin: 0 auto 20px; line-height: 1.6; }

/* ── Stat cards row ── */
.stat-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: var(--section-gap); }
.stat-card {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.02), transparent 50%), var(--bg-surface);
  border: 1px solid var(--border); border-radius: var(--radius);
  padding: 20px 24px; box-shadow: var(--shadow-sm);
  transition: all 0.22s var(--ease-out);
}
.stat-card:hover { border-color: var(--border-light); box-shadow: var(--shadow); transform: translateY(-2px); }
.stat-value { font-family: var(--font-display); font-size: 30px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1; font-variant-numeric: tabular-nums; }
.stat-label { font-size: 12px; color: var(--text-muted); margin-top: 4px; font-weight: 500; text-transform: uppercase; letter-spacing: 0.4px; }
.stat-card.accent .stat-value { color: var(--accent); }
.stat-card.success .stat-value { color: var(--success); }
.stat-card.danger .stat-value { color: var(--danger); }

/* ── Section divider ── */
.section-title {
  font-size: 13px; font-weight: 700; color: var(--text-muted);
  text-transform: uppercase; letter-spacing: 0.6px;
  margin-bottom: 16px; padding-bottom: 10px;
  border-bottom: 1px solid var(--border);
}

/* ── Reduced motion ── */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; }
}
</style>
