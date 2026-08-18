# saas-claw frontend 生产镜像
# 多阶段：node 构建 dist/ -> nginx 静态托管 + 同源反代 /api、/v1 到网关。
# VITE_* 仅 dev proxy 用，不进产物；prod 走同源相对路径。

# ---- build stage ----
FROM node:22-alpine AS build
WORKDIR /app

RUN corepack enable

# 先拷锁文件装依赖（含 devDependencies，vite/vue-tsc 构建期需要）
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

# 再拷源码构建（pnpm build = vue-tsc --noEmit && vite build）
COPY . .
RUN pnpm build

# ---- runtime stage ----
FROM nginx:alpine AS runtime

# 自定义 nginx 配置（SPA fallback + /api、/v1 反代 + SSE 关缓冲）
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 静态产物
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
