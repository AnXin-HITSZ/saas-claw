import { createRouter, createWebHistory } from "vue-router";
import { api } from "../api/client.js";

const routes = [
  {
    path: "/",
    name: "welcome",
    component: () => import("../views/WelcomePage.vue"),
  },
  {
    path: "/login",
    name: "login",
    component: () => import("../views/LoginPage.vue"),
  },
  {
    path: "/register",
    name: "register",
    component: () => import("../views/RegisterPage.vue"),
  },
  {
    path: "/workspace",
    component: () => import("../views/WorkspaceLayout.vue"),
    meta: { requiresAuth: true },
    children: [
      {
        path: "",
        redirect: "/workspace/claws",
      },
      {
        path: "claws",
        name: "claws",
        component: () => import("../views/ClawListPage.vue"),
      },
      {
        path: "claws/:id",
        name: "claw-detail",
        component: () => import("../views/ClawDetailPage.vue"),
        props: true,
      },
      {
        path: "agents",
        name: "agents",
        component: () => import("../views/AgentConfigPage.vue"),
      },
      {
        path: "providers",
        name: "providers",
        component: () => import("../views/ProviderPage.vue"),
      },
      {
        path: "playground",
        name: "playground",
        component: () => import("../views/PlaygroundPage.vue"),
      },
      {
        path: "tools",
        name: "tools",
        component: () => import("../views/ToolCatalogPage.vue"),
      },
      {
        path: "tokens",
        name: "tokens",
        component: () => import("../views/TokenPage.vue"),
      },
      {
        path: "pods",
        name: "pods",
        component: () => import("../views/PodStatusPage.vue"),
      },
      {
        path: "claws/:id/files",
        name: "workspace-files",
        component: () => import("../views/WorkspaceFilesPage.vue"),
        props: true,
      },
      {
        path: "secrets",
        name: "secrets",
        component: () => import("../views/SecretPage.vue"),
      },
      {
        path: "admin/channels",
        name: "admin-channels",
        component: () => import("../views/admin/ChannelPage.vue"),
        meta: { authority: "channel:manage" },
      },
      {
        path: "admin/users",
        name: "admin-users",
        component: () => import("../views/admin/UserManagePage.vue"),
        meta: { authority: "user:manage" },
      },
      {
        path: "admin/audit",
        name: "admin-audit",
        component: () => import("../views/admin/AuditLogPage.vue"),
        meta: { authority: "audit:read" },
      },
      {
        path: "admin/usage",
        name: "admin-usage",
        component: () => import("../views/admin/UsagePage.vue"),
        meta: { authority: "audit:read" },
      },
    ],
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

function getStoredAuthorities() {
  try {
    const user = JSON.parse(localStorage.getItem("pyclaw.user") || "null");
    if (user && user.authorities) {
      if (Array.isArray(user.authorities)) return user.authorities;
      if (typeof user.authorities === "string") return user.authorities.split(",").map(s => s.trim());
    }
  } catch {
    // ignore parse errors
  }
  return [];
}

router.beforeEach(async (to, from, next) => {
  if (to.meta.requiresAuth || to.meta.authority) {
    const token = localStorage.getItem("pyclaw.token");
    if (!token) {
      return next("/login");
    }
    const user = JSON.parse(localStorage.getItem("pyclaw.user") || "null");
    if (!user) {
      try {
        const me = await api.get("/api/auth/me");
        localStorage.setItem("pyclaw.user", JSON.stringify(me));
      } catch {
        localStorage.removeItem("pyclaw.token");
        return next("/login");
      }
    }
    // Check route-level authority
    const requiredAuthority = to.meta.authority;
    if (requiredAuthority) {
      const authorities = getStoredAuthorities();
      if (!authorities.includes(requiredAuthority)) {
        return next("/workspace");
      }
    }
  }
  next();
});

export default router;
