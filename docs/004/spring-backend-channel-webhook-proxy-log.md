# Spring Backend Channel Webhook Proxy 实现记录

本文记录方案 B 的实现：外部平台 webhook 统一进入 Spring Backend，再由 Spring Backend 转发到 K3s 集群内的 pyclaw-api。这样公网只暴露 `api.anxin-hitsz.com` 对应的 Spring Backend，不需要直接暴露 pyclaw-api。

## 1. 目标架构

```text
微信 / 飞书
  -> https://api.anxin-hitsz.com/api/webhooks/<platform>
  -> spring-backend
  -> http://pyclaw.pyclaw.svc.cluster.local:8000/v1/channels/<platform>/webhook
  -> pyclaw-api
  -> MySQL ingress_queue
  -> worker / sync dispatcher
```

其中 `<platform>` 当前支持：

```text
wechat
feishu
```

外部平台配置的回调地址为：

```text
https://api.anxin-hitsz.com/api/webhooks/wechat
https://api.anxin-hitsz.com/api/webhooks/feishu
```

## 2. 为什么选择方案 B

方案 B 的核心是：公网入口只保留 Spring Backend。

这样做的好处：

1. 统一公网入口。外部浏览器、前端、第三方平台 webhook 都访问 Spring Backend 暴露的域名。
2. pyclaw-api 继续保持集群内服务。pyclaw-api 不直接暴露公网，减少攻击面。
3. Spring Backend 可以继续承担认证、审计、限流、代理、配置管理等边界职责。
4. 微信 / 飞书 webhook 的平台签名校验仍由 pyclaw-api 的 channel adapter 完成，不把平台协议逻辑分散到 Spring Backend。

## 3. 本次修改文件

### 3.1 SecurityConfig

文件：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/config/SecurityConfig.java
```

修改点：将以下 webhook 路径加入 `permitAll()`：

```text
/api/webhooks/wechat
/api/webhooks/feishu
```

原因是微信和飞书平台回调通常不会携带系统自己的 JWT 或 API Token。如果这些路径仍然要求 Spring 登录态，则平台回调会在进入 pyclaw-api 前被 Spring Security 拦截。

注意：这里的 `permitAll()` 只表示 Spring Backend 不要求本系统登录态，并不表示 webhook 没有安全校验。真正的平台安全校验仍在 pyclaw-api 中完成：

```text
微信：signature + timestamp + nonce + token
飞书：verification token / sign secret
```

### 3.2 PyclawClient

文件：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/pyclaw/PyclawClient.java
```

新增方法：

```java
forwardChannelWebhook(channel, queryString, body, method, incomingHeaders)
```

职责：把 Spring Backend 收到的 webhook 请求转发到 pyclaw-api 内部地址：

```text
${PYCLAW_BASE_URL}/v1/channels/{channel}/webhook
```

其中 `PYCLAW_BASE_URL` 在 K3s 中通常为：

```text
http://pyclaw.pyclaw.svc.cluster.local:8000
```

转发内容包括：

```text
HTTP method
query string
raw body bytes
部分请求头
```

保留 raw body 的原因：平台签名校验通常依赖原始请求内容。如果在 Spring Backend 中先解析 JSON 再重新序列化，可能导致字段顺序、空白字符、编码细节变化，从而影响签名校验。

### 3.3 过滤 hop-by-hop headers

代理时不会转发以下请求头：

```text
connection
content-length
expect
host
keep-alive
proxy-authenticate
proxy-authorization
te
trailer
transfer-encoding
upgrade
authorization
```

原因：

1. `Host` 应该由内部请求目标重新确定。
2. `Content-Length` 应该由 Java HttpClient 根据 body 自动计算。
3. `Connection`、`Transfer-Encoding` 等属于连接级头，不应该跨代理边界透传。
4. 外部 `Authorization` 不应该透传给 pyclaw-api，避免把前端用户 JWT 或第三方平台自定义鉴权混入内部服务调用。

Spring Backend 会重新注入内部调用使用的：

```text
Authorization: Bearer ${PYCLAW_API_TOKEN}
```

### 3.4 ChannelWebhookProxyController

新增文件：

```text
spring-backend/src/main/java/com/anxin/pyclaw/backend/channel/ChannelWebhookProxyController.java
```

新增路由：

```text
GET  /api/webhooks/wechat
POST /api/webhooks/wechat
POST /api/webhooks/feishu
```

微信需要 `GET` 是因为公众号服务器配置阶段会发送 URL 验证请求，通常包含：

```text
signature
timestamp
nonce
echostr
```

微信消息事件使用 `POST`。

飞书事件订阅主要使用 `POST`。

## 4. 请求处理流程

以微信 URL 验证为例：

```text
微信服务器
  -> GET /api/webhooks/wechat?signature=...&timestamp=...&nonce=...&echostr=...
  -> Spring Security permitAll
  -> ChannelWebhookProxyController.wechatGet()
  -> PyclawClient.forwardChannelWebhook("wechat", queryString, emptyBody, "GET", headers)
  -> pyclaw-api /v1/channels/wechat/webhook
  -> 微信 adapter 校验签名
  -> 返回 echostr 或错误
  -> Spring Backend 原样返回给微信服务器
```

以飞书事件为例：

```text
飞书服务器
  -> POST /api/webhooks/feishu
  -> Spring Security permitAll
  -> ChannelWebhookProxyController.feishuPost()
  -> PyclawClient.forwardChannelWebhook("feishu", queryString, rawBody, "POST", headers)
  -> pyclaw-api /v1/channels/feishu/webhook
  -> 飞书 adapter 校验 verification token / sign secret
  -> 写入 MySQL ingress_queue
  -> worker 或同步 dispatcher 处理消息
```

## 5. K3s 配置要求

Spring Backend 需要能够访问 pyclaw-api 的集群内 Service，因此 Spring Backend Deployment 中应配置：

```text
PYCLAW_BASE_URL=http://pyclaw.pyclaw.svc.cluster.local:8000
PYCLAW_API_TOKEN=<与 pyclaw-api 一致的内部 token>
```

pyclaw-api 需要配置 Channel 相关变量，例如：

```text
OPENCLAW_INGRESS_QUEUE_BACKEND=mysql
OPENCLAW_INGRESS_QUEUE_DSN=<MySQL DSN Secret>
OPENCLAW_CHANNEL_PROVIDER=openai
OPENCLAW_CHANNEL_TOOL_PROFILE=messaging
OPENCLAW_CHANNEL_WEBHOOK_SYNC=false
```

微信至少需要：

```text
OPENCLAW_WECHAT_ENABLED=true
OPENCLAW_WECHAT_TOKEN=<微信后台配置的 Token>
OPENCLAW_WECHAT_ACCOUNT_ID=<账号标识>
OPENCLAW_WECHAT_APP_ID=<AppID>
OPENCLAW_WECHAT_APP_SECRET=<AppSecret>
```

飞书至少需要：

```text
OPENCLAW_FEISHU_ENABLED=true
OPENCLAW_FEISHU_APP_ID=<App ID>
OPENCLAW_FEISHU_APP_SECRET=<App Secret>
OPENCLAW_FEISHU_VERIFICATION_TOKEN=<Verification Token>
OPENCLAW_FEISHU_SIGN_SECRET=<Encrypt Key / Sign Secret，按平台实际配置>
```

## 6. 验证方式

部署后先验证 Spring Backend 健康状态：

```bash
curl -i https://api.anxin-hitsz.com/healthz
```

再验证 webhook 路径不再被 Spring Security 拦截：

```bash
curl -i "https://api.anxin-hitsz.com/api/webhooks/wechat?signature=test&timestamp=1&nonce=1&echostr=hello"
```

预期结果：

```text
不应该返回 Spring Security 的 401 / 403
可能返回 pyclaw-api 的平台签名校验失败
```

如果返回签名失败，说明请求已经进入 pyclaw-api，Spring 代理路径是通的；后续需要在微信 / 飞书后台填入真实 token、secret，并使用平台真实请求验证。

## 7. 本地测试覆盖

本次更新了 Spring Backend 的安全冒烟测试：

```text
spring-backend/src/test/java/com/anxin/pyclaw/backend/SecuritySmokeTest.java
```

新增测试点：

```text
/api/webhooks/wechat 不需要登录态
请求会进入 ChannelWebhookProxyController
Controller 会调用 PyclawClient.forwardChannelWebhook()
```

测试中使用 mock 的 `PyclawClient`，避免依赖本地真实启动 pyclaw-api。
