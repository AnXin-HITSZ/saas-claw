# pyclaw Vue Frontend K3s Technical Design

本文记录 `pyclaw-web` 前端控制台的实现与部署方式。该前端是独立 Vue 应用，不再依赖 Spring Boot `static/index.html` 承载页面。

## 1. 目标

`pyclaw-web` 提供一个面向浏览器的管理控制台，对接 `spring-backend` 已实现的 HTTP API：

- 登录与当前用户信息
- Agent Playground
- API Token 管理
- 用户管理
- Provider 配置
- Channel 配置
- 审计日志
- 用量记录

部署形态与 `pyclaw-api`、`spring-backend` 保持一致：

```text
GitHub push
  -> GitHub Actions 构建前端镜像
  -> 推送到阿里云 ACR
  -> SSH 到 ECS
  -> helm upgrade --install pyclaw-web
  -> K3s 运行 pyclaw-web Pod
```

## 2. 目录结构

```text
pyclaw-web/
  package.json
  index.html
  vite.config.js
  Dockerfile
  nginx.conf.template
  src/
    main.js
    App.vue
  helm/
    Chart.yaml
    values.yaml
    templates/
      _helpers.tpl
      deployment.yaml
      service.yaml
      ingress.yaml
      NOTES.txt

pyclaw-web-values-k3s.yaml
.github/workflows/deploy-pyclaw-web.yml
```

## 3. 前端技术栈

```text
Vue 3       页面和状态
Vite        本地开发与生产构建
Nginx       容器内静态资源服务与 API 反向代理
Helm        K3s 部署模板
GitHub Actions 自动构建和部署
```

当前没有引入 Vue Router、Pinia、Element Plus 等额外框架，原因是第一版控制台页面较集中，使用单文件组件即可降低部署复杂度。

## 4. API 访问方式

浏览器默认访问同源 API：

```text
/api/auth/login
/api/auth/me
/api/agent/run
...
```

在 K3s 中，前端容器里的 Nginx 将 `/api/` 代理到 Spring Backend：

```nginx
location /api/ {
  proxy_pass http://${BACKEND_UPSTREAM}/api/;
}
```

默认 upstream：

```text
pyclaw-spring-backend.pyclaw.svc.cluster.local:8080
```

因此浏览器访问的是：

```text
https://pyclaw.anxin-hitsz.com/api/auth/me
```

但真实请求会在集群内部转发到：

```text
http://pyclaw-spring-backend.pyclaw.svc.cluster.local:8080/api/auth/me
```

这样可以避免浏览器 CORS 配置。

## 5. 健康检查

前端自身健康检查：

```text
GET /healthz
```

返回：

```text
ok
```

后端健康检查代理：

```text
GET /backend-healthz
```

该请求会转发到 Spring Backend 的：

```text
GET /healthz
```

前端概览页使用 `/backend-healthz` 判断后端状态。

## 6. 本地开发

进入前端目录：

```cmd
cd /d D:\project\pyclaw\pyclaw-web
```

安装依赖：

```cmd
npm install
```

启动开发服务器：

```cmd
npm run dev
```

默认访问：

```text
http://localhost:5173
```

本地 Vite 代理配置：

```js
server: {
  proxy: {
    "/api": "http://localhost:8080",
    "/backend-healthz": {
      target: "http://localhost:8080",
      rewrite: () => "/healthz"
    }
  }
}
```

因此本地开发前，需要先启动 Spring Backend 到 `localhost:8080`。

## 7. Docker 镜像

`pyclaw-web/Dockerfile` 使用两阶段构建：

```text
node:22-alpine
  -> npm install
  -> npm run build
  -> 生成 dist

nginx:1.27-alpine
  -> 复制 dist
  -> 复制 nginx.conf.template
  -> 暴露 80 端口
```

本地构建：

```cmd
cd /d D:\project\pyclaw
docker build -t pyclaw-web:dev .\pyclaw-web
```

本地运行：

```cmd
docker run --rm -p 8081:80 ^
  -e BACKEND_UPSTREAM=host.docker.internal:8080 ^
  pyclaw-web:dev
```

访问：

```text
http://localhost:8081
```

## 8. Helm 部署

Chart 位置：

```text
pyclaw-web/helm
```

线上 values 文件：

```text
pyclaw-web-values-k3s.yaml
```

关键配置：

```yaml
image:
  repository: crpi-li78f6lp5zheaj11.cn-shenzhen.personal.cr.aliyuncs.com/pyclaw/pyclaw-web
  tag: 0.1.0

imagePullSecrets:
  - name: aliyun-acr-pull-secret

backend:
  upstream: pyclaw-spring-backend.pyclaw.svc.cluster.local:8080

ingress:
  enabled: true
  className: traefik
  hosts:
    - host: pyclaw.anxin-hitsz.com
```

手动部署命令：

```bash
cd /opt/pyclaw
sudo KUBECONFIG=/etc/rancher/k3s/k3s.yaml helm upgrade --install pyclaw-web ./pyclaw-web/helm \
  -n pyclaw \
  --create-namespace \
  -f pyclaw-web-values-k3s.yaml
```

查看资源：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw get pods
sudo /usr/local/bin/k3s kubectl -n pyclaw get svc
sudo /usr/local/bin/k3s kubectl -n pyclaw get ingress
```

查看日志：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw logs deployment/pyclaw-web --tail=100
```

端口转发测试：

```bash
sudo /usr/local/bin/k3s kubectl -n pyclaw port-forward svc/pyclaw-web 8081:80
curl http://localhost:8081/healthz
```

## 9. GitHub Actions

Workflow：

```text
.github/workflows/deploy-pyclaw-web.yml
```

触发条件：

```text
push 到 main，并且修改以下路径：
- pyclaw-web/**
- pyclaw-web-values-k3s.yaml
- .github/workflows/deploy-pyclaw-web.yml
```

执行过程：

```text
1. Checkout 仓库
2. 登录阿里云 ACR
3. 使用 pyclaw-web/Dockerfile 构建镜像
4. 推送镜像到 ACR
5. SSH 到 ECS
6. cd /opt/pyclaw && git pull
7. helm upgrade --install pyclaw-web
8. 通过 --set image.repository 和 --set image.tag 覆盖本次镜像
```

需要沿用已有 GitHub Secrets：

```text
ACR_REGISTRY
ACR_USERNAME
ACR_PASSWORD
ECS_HOST
ECS_USER
ECS_SSH_KEY
```

## 10. DNS 与访问入口

当前前端 values 默认域名：

```text
pyclaw.anxin-hitsz.com
```

需要在 DNS 中添加解析到 ECS 公网 IP。

Spring Backend 仍可继续使用：

```text
api.anxin-hitsz.com
```

推荐最终形态：

```text
pyclaw.anxin-hitsz.com  -> pyclaw-web
api.anxin-hitsz.com      -> spring-backend
```

不过前端默认通过同源 `/api` 调用后端，所以日常使用只需要打开 `pyclaw.anxin-hitsz.com`。

## 11. 安全注意事项

前端做了以下处理：

- JWT 存储在 `localStorage` 的 `pyclaw.console.token`
- 登录后通过 `/api/auth/me` 获取当前用户与权限
- 菜单根据 authorities 隐藏不可访问页面
- 用户列表不展示 `passwordHash`
- Token 列表不展示 `tokenHash`
- 新建 API Token 后，明文 token 只在弹窗中显示一次

需要注意：

前端隐藏菜单只是用户体验控制，真正的权限仍由 Spring Security 的 `@PreAuthorize` 和后端鉴权负责。

## 12. 后续可升级项

- 引入 Vue Router，让每个页面拥有独立 URL
- 引入 Pinia 管理登录态和全局数据
- 接入组件库或设计系统
- 增加分页、筛选、搜索
- 增加 HTTPS TLS Secret
- 增加前端独立错误上报
- 增加登录过期自动跳转与刷新 token 机制
