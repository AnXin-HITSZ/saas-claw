# OpenClaw Channel / 平台适配 / 入站消息 / Offset 存储实现技术文档

本文档面向将 OpenClaw TypeScript 通道实现迁移、改造为 Python 版本的场景，整理当前目录 `D:\learn\openclaw` 中与 Channel 抽象、平台适配、并发模型、统一消息格式和 offset 存储相关的源码位置与技术细节。

## 1. 总览对照

| 关注点 | 简化设计中的做法 | OpenClaw 当前实现 | 关键源码 |
| --- | --- | --- | --- |
| Channel 抽象 | `receive()` / `send()` 两个抽象方法 | `ChannelPlugin` 聚合 `config`、`outbound`、`message`、`lifecycle`、`status`、`gateway`、`directory` 等多类适配器；`message` 再拆成 send / receive / durable final / live message | [types.plugin.ts:66](D:/learn/openclaw/src/channels/plugins/types.plugin.ts:66), [types.ts:396](D:/learn/openclaw/src/channels/message/types.ts:396) |
| receive / send | 单一接口 | 出站由 `ChannelMessageSendAdapter` 的 `text/media/payload/poll` 承载；入站 ack 由 `ChannelMessageReceiveAdapterShape` 声明默认策略和支持策略 | [types.ts:307](D:/learn/openclaw/src/channels/message/types.ts:307), [types.ts:390](D:/learn/openclaw/src/channels/message/types.ts:390) |
| 生命周期 | 通常无生命周期钩子 | 插件根对象支持 `lifecycle`；消息发送也支持 `beforeSendAttempt`、`afterSendSuccess`、`afterSendFailure`、`afterCommit` | [types.plugin.ts:94](D:/learn/openclaw/src/channels/plugins/types.plugin.ts:94), [types.ts:292](D:/learn/openclaw/src/channels/message/types.ts:292) |
| 平台数量 | CLI、Telegram、Feishu 等少量平台 | 仓库中至少二十多个 `channel.ts` 平台插件：Telegram、Discord、Slack、Feishu/Lark、Matrix、Mattermost、WhatsApp、Signal、iMessage、LINE、IRC、MSTeams、QQ Bot、Zalo 等 | [telegram/channel.ts:698](D:/learn/openclaw/extensions/telegram/src/channel.ts:698), [discord/channel.ts:283](D:/learn/openclaw/extensions/discord/src/channel.ts:283), [slack/channel.ts:543](D:/learn/openclaw/extensions/slack/src/channel.ts:543), [feishu/channel.ts:649](D:/learn/openclaw/extensions/feishu/src/channel.ts:649) |
| 并发模型 | 每个 channel 一个线程 + 共享队列 | 核心层有 SQLite-backed durable ingress queue；平台层按平台语义加队列，例如 Discord inbound job queue key、Slack debounce queue、Feishu per-key sequential queue；网关/监控侧使用 async 生命周期 | [ingress-queue.ts:121](D:/learn/openclaw/src/channels/message/ingress-queue.ts:121), [discord/inbound-job.ts:25](D:/learn/openclaw/extensions/discord/src/monitor/inbound-job.ts:25), [slack/message-handler.ts:92](D:/learn/openclaw/extensions/slack/src/monitor/message-handler.ts:92), [feishu/sequential-queue.ts:42](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:42) |
| 消息格式 | `InboundMessage` dataclass | OpenClaw 没有单一 dataclass 名称，而是使用统一的 message adapter contract、receive context、send context、receipt、rendered batch、platform payload 类型组合表达消息生命周期 | [receive.ts:18](D:/learn/openclaw/src/channels/message/receive.ts:18), [types.ts:148](D:/learn/openclaw/src/channels/message/types.ts:148), [types.ts:84](D:/learn/openclaw/src/channels/message/types.ts:84) |
| Offset 存储 | 明文 offset 文件 | Telegram offset 是版本化状态对象，写入 plugin-state keyed store；旧文件迁移读取通过 JSON helper；SDK 还提供 atomic JSON 写入工具 | [update-offset-store.ts:12](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:12), [update-offset-store.ts:134](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:134), [plugin-state-store.types.ts:11](D:/learn/openclaw/src/plugin-state/plugin-state-store.types.ts:11), [json-store.ts:27](D:/learn/openclaw/src/plugin-sdk/json-store.ts:27) |

## 2. Channel 抽象与插件根对象

OpenClaw 的通道不是一个简单的抽象基类，而是一个插件根对象。`ChannelPlugin` 类型定义了完整平台能力面：`id`、`meta`、`capabilities`、`config` 是基础必需字段，之后可选接入 `setup`、`pairing`、`security`、`outbound`、`status`、`gateway`、`commands`、`lifecycle`、`message`、`messaging`、`directory`、`actions`、`heartbeat` 等能力。

核心类型入口：

- [types.plugin.ts:66](D:/learn/openclaw/src/channels/plugins/types.plugin.ts:66)：`ChannelPlugin<ResolvedAccount, Probe, Audit>` 根类型。
- [types.plugin.ts:69](D:/learn/openclaw/src/channels/plugins/types.plugin.ts:69)：`capabilities` 描述平台能力。
- [types.plugin.ts:94](D:/learn/openclaw/src/channels/plugins/types.plugin.ts:94)：`lifecycle?: ChannelLifecycleAdapter`。
- [types.plugin.ts:102](D:/learn/openclaw/src/channels/plugins/types.plugin.ts:102)：`message?: ChannelMessageAdapterShape`。

这意味着 Python 版本如果只设计：

```python
class Channel(ABC):
    async def receive(self): ...
    async def send(self, message): ...
```

会覆盖基础收发，但会丢失 OpenClaw 已经抽象出来的配置解析、账号解析、平台状态、目录查询、权限、绑定关系、生命周期维护、消息动作等能力。更合适的 Python 结构是：

```python
@dataclass
class ChannelPlugin:
    id: str
    meta: ChannelMeta
    capabilities: ChannelCapabilities
    config: ChannelConfigAdapter
    message: ChannelMessageAdapter | None = None
    outbound: ChannelOutboundAdapter | None = None
    lifecycle: ChannelLifecycleAdapter | None = None
    status: ChannelStatusAdapter | None = None
    actions: ChannelActionAdapter | None = None
```

这样能先保持 OpenClaw 的插件化结构，再逐步移植各平台。

## 3. Message Adapter：send / receive / durable / live

核心消息契约在 [src/channels/message/types.ts](D:/learn/openclaw/src/channels/message/types.ts:1)。这个文件把消息通道能力拆成多个层次。

### 3.1 出站发送

`ChannelMessageSendAdapter` 定义了平台可实现的发送能力：

- [types.ts:307](D:/learn/openclaw/src/channels/message/types.ts:307)：`ChannelMessageSendAdapter`。
- [types.ts:311](D:/learn/openclaw/src/channels/message/types.ts:311)：`text?: (...) => Promise<TSendResult>`。
- [types.ts:312](D:/learn/openclaw/src/channels/message/types.ts:312)：`media?: (...) => Promise<TSendResult>`。
- [types.ts:313](D:/learn/openclaw/src/channels/message/types.ts:313)：`payload?: (...) => Promise<TSendResult>`。
- [types.ts:314](D:/learn/openclaw/src/channels/message/types.ts:314)：`poll?: (...) => Promise<TSendResult>`。
- [types.ts:315](D:/learn/openclaw/src/channels/message/types.ts:315)：`lifecycle?: ChannelMessageSendLifecycleAdapter`。

对应的上下文类型包括：

- [types.ts:169](D:/learn/openclaw/src/channels/message/types.ts:169)：`ChannelMessageSendTextContext`。
- [types.ts:185](D:/learn/openclaw/src/channels/message/types.ts:185)：`ChannelMessageSendMediaContext`。
- [types.ts:197](D:/learn/openclaw/src/channels/message/types.ts:197)：`ChannelMessageSendPayloadContext`。
- [types.ts:210](D:/learn/openclaw/src/channels/message/types.ts:210)：`ChannelMessageSendPollContext`。

发送结果统一归一到 `ChannelMessageSendResult`，其中包含 `receipt` 和可选 `messageId`：

- [types.ts:220](D:/learn/openclaw/src/channels/message/types.ts:220)：`ChannelMessageSendResult`。
- [types.ts:84](D:/learn/openclaw/src/channels/message/types.ts:84)：`MessageReceipt`。
- [types.ts:74](D:/learn/openclaw/src/channels/message/types.ts:74)：`MessageReceiptPart`。

### 3.2 接收 ack 策略

接收侧的重点不是 `receive()` 函数本身，而是“什么时候 ack”。OpenClaw 用 `ChannelMessageReceiveAckPolicy` 表达 ack 时机：

- [types.ts:375](D:/learn/openclaw/src/channels/message/types.ts:375)：ack policy 枚举。
- [types.ts:382](D:/learn/openclaw/src/channels/message/types.ts:382)：稳定顺序的策略列表。
- [types.ts:390](D:/learn/openclaw/src/channels/message/types.ts:390)：`ChannelMessageReceiveAdapterShape`。

策略包括：

- `after_receive_record`：入站记录成功后 ack。
- `after_agent_dispatch`：成功派发给 agent 后 ack。
- `after_durable_send`：完成可靠回复发送后 ack。
- `manual`：平台或插件自行 ack。

`createMessageReceiveContext` 则把 ack/nack 做成幂等状态机：

- [receive.ts:18](D:/learn/openclaw/src/channels/message/receive.ts:18)：`MessageReceiveContext`。
- [receive.ts:37](D:/learn/openclaw/src/channels/message/receive.ts:37)：`shouldAckMessageAfterStage`。
- [receive.ts:59](D:/learn/openclaw/src/channels/message/receive.ts:59)：`createMessageReceiveContext`。
- [receive.ts:81](D:/learn/openclaw/src/channels/message/receive.ts:81)：ack 回调必须幂等。

`defineChannelMessageAdapter` 还会给没有声明 receive 的 adapter 默认补 `manual` ack：

- [adapter.ts:12](D:/learn/openclaw/src/channels/message/adapter.ts:12)：默认 manual receive adapter。
- [adapter.ts:25](D:/learn/openclaw/src/channels/message/adapter.ts:25)：`defineChannelMessageAdapter`。

### 3.3 durable final delivery 与生命周期钩子

OpenClaw 发送不只是调用平台 API。它有 durable final delivery 能力声明和未知发送结果 reconciliation：

- [types.ts:17](D:/learn/openclaw/src/channels/message/types.ts:17)：`durableFinalDeliveryCapabilities`。
- [types.ts:319](D:/learn/openclaw/src/channels/message/types.ts:319)：`ChannelMessageDurableFinalAdapter`。
- [types.ts:276](D:/learn/openclaw/src/channels/message/types.ts:276)：未知发送结果可返回 `sent` / `not_sent` / `unresolved`。

消息发送生命周期钩子：

- [types.ts:292](D:/learn/openclaw/src/channels/message/types.ts:292)：`ChannelMessageSendLifecycleAdapter`。
- [types.ts:298](D:/learn/openclaw/src/channels/message/types.ts:298)：`beforeSendAttempt`。
- [types.ts:299](D:/learn/openclaw/src/channels/message/types.ts:299)：`afterSendSuccess`。
- [types.ts:302](D:/learn/openclaw/src/channels/message/types.ts:302)：`afterSendFailure`。
- [types.ts:303](D:/learn/openclaw/src/channels/message/types.ts:303)：`afterCommit`。

`sendDurableMessageBatch` 是实际的可靠发送管线入口之一：

- [send.ts:135](D:/learn/openclaw/src/channels/message/send.ts:135)：`DurableMessageSendContextParams`。
- [send.ts:153](D:/learn/openclaw/src/channels/message/send.ts:153)：`DurableMessageSendContext`。
- [send.ts:162](D:/learn/openclaw/src/channels/message/send.ts:162)：`withDurableMessageSendContext`。
- [send.ts:202](D:/learn/openclaw/src/channels/message/send.ts:202)：`ctx.send(rendered)` 调用 `deliverOutboundPayloadsInternal`。
- [send.ts:224](D:/learn/openclaw/src/channels/message/send.ts:224)：将平台结果归一成 `MessageReceipt`。
- [send.ts:342](D:/learn/openclaw/src/channels/message/send.ts:342)：`sendDurableMessageBatch`。

Python 迁移建议：不要把 `send()` 设计成只返回平台 message id。建议返回：

```python
@dataclass
class MessageReceipt:
    primary_platform_message_id: str | None
    platform_message_ids: list[str]
    parts: list[MessageReceiptPart]
    thread_id: str | None = None
    reply_to_id: str | None = None
    sent_at: float = field(default_factory=time.time)
```

并保留 `before_send_attempt`、`after_send_success`、`after_send_failure`、`after_commit` 钩子。

## 4. 平台适配入口

### 4.1 Telegram

Telegram 的插件入口：

- [telegram/channel.ts:247](D:/learn/openclaw/extensions/telegram/src/channel.ts:247)：`telegramMessageAdapter`。
- [telegram/channel.ts:264](D:/learn/openclaw/extensions/telegram/src/channel.ts:264)：Telegram 显式声明 `receive`。
- [telegram/channel.ts:698](D:/learn/openclaw/extensions/telegram/src/channel.ts:698)：`telegramPlugin = createChatChannelPlugin(...)`。
- [telegram/channel.ts:843](D:/learn/openclaw/extensions/telegram/src/channel.ts:843)：生命周期相关配置。
- [telegram/channel.ts:888](D:/learn/openclaw/extensions/telegram/src/channel.ts:888)：插件根对象挂载 `message: telegramMessageAdapter`。

Telegram 监控与 offset：

- [monitor.ts:202](D:/learn/openclaw/extensions/telegram/src/monitor.ts:202)：注入 `deleteTelegramUpdateOffset`、`readTelegramUpdateOffset`、`writeTelegramUpdateOffset`。
- [monitor.ts:235](D:/learn/openclaw/extensions/telegram/src/monitor.ts:235)：读取持久化 offset。
- [monitor.ts:267](D:/learn/openclaw/extensions/telegram/src/monitor.ts:267)：写入最新 update offset。

### 4.2 Discord

Discord 的插件入口：

- [discord/channel.ts:87](D:/learn/openclaw/extensions/discord/src/channel.ts:87)：`discordMessageAdapter`。
- [discord/channel.ts:283](D:/learn/openclaw/extensions/discord/src/channel.ts:283)：`discordPlugin`。
- [discord/channel.ts:390](D:/learn/openclaw/extensions/discord/src/channel.ts:390)：挂载 `message: discordMessageAdapter`。
- [discord/channel.ts:710](D:/learn/openclaw/extensions/discord/src/channel.ts:710)：启动 `monitorDiscordProvider`。

Discord 出站适配：

- [outbound-adapter.ts:107](D:/learn/openclaw/extensions/discord/src/outbound-adapter.ts:107)：`discordOutbound`。
- [outbound-adapter.ts:145](D:/learn/openclaw/extensions/discord/src/outbound-adapter.ts:145)：`durableFinal` 能力。
- [outbound-adapter.ts:170](D:/learn/openclaw/extensions/discord/src/outbound-adapter.ts:170)：`sendText`。
- [outbound-adapter.ts:212](D:/learn/openclaw/extensions/discord/src/outbound-adapter.ts:212)：`sendMedia`。

Discord 入站并发：

- [inbound-job.ts:25](D:/learn/openclaw/extensions/discord/src/monitor/inbound-job.ts:25)：`DiscordInboundJob` 包含 `queueKey`、`payload`、`runtime`。
- [inbound-job.ts:32](D:/learn/openclaw/extensions/discord/src/monitor/inbound-job.ts:32)：`resolveDiscordInboundJobQueueKey`，同一个 key 用于 run-queue 序列化。
- [inbound-job.ts:46](D:/learn/openclaw/extensions/discord/src/monitor/inbound-job.ts:46)：`buildDiscordInboundJob`。
- [inbound-job.ts:89](D:/learn/openclaw/extensions/discord/src/monitor/inbound-job.ts:89)：`materializeDiscordInboundJob`。
- [listeners.queue.ts:54](D:/learn/openclaw/extensions/discord/src/monitor/listeners.queue.ts:54)：slow listener 检测与日志。

### 4.3 Slack

Slack 的插件入口：

- [slack/channel.ts:523](D:/learn/openclaw/extensions/slack/src/channel.ts:523)：`slackMessageAdapter`。
- [slack/channel.ts:543](D:/learn/openclaw/extensions/slack/src/channel.ts:543)：`slackPlugin`。
- [slack/channel.ts:684](D:/learn/openclaw/extensions/slack/src/channel.ts:684)：挂载 `message: slackMessageAdapter`。
- [slack/channel.ts:763](D:/learn/openclaw/extensions/slack/src/channel.ts:763)：启动 `monitorSlackProvider`。

Slack 出站能力：

- [slack/channel.ts:433](D:/learn/openclaw/extensions/slack/src/channel.ts:433)：`durableFinal`。
- [slack/channel.ts:449](D:/learn/openclaw/extensions/slack/src/channel.ts:449)：`sendPayload`。
- [slack/channel.ts:478](D:/learn/openclaw/extensions/slack/src/channel.ts:478)：`sendText`。
- [slack/channel.ts:493](D:/learn/openclaw/extensions/slack/src/channel.ts:493)：`sendMedia`。

Slack 入站处理：

- [events/messages.ts:155](D:/learn/openclaw/extensions/slack/src/monitor/events/messages.ts:155)：注册 Slack message/app_mention 事件。
- [events/messages.ts:222](D:/learn/openclaw/extensions/slack/src/monitor/events/messages.ts:222)：处理 `message` 事件。
- [events/messages.ts:256](D:/learn/openclaw/extensions/slack/src/monitor/events/messages.ts:256)：处理 `app_mention`。
- [message-handler.ts:92](D:/learn/openclaw/extensions/slack/src/monitor/message-handler.ts:92)：`createSlackMessageHandler`。
- [message-handler.ts:335](D:/learn/openclaw/extensions/slack/src/monitor/message-handler.ts:335)：入站消息进入 debouncer。
- [message-handler/types.ts:12](D:/learn/openclaw/extensions/slack/src/monitor/message-handler/types.ts:12)：`PreparedSlackMessage`。

### 4.4 Feishu / Lark

Feishu 的插件入口：

- [feishu/channel.ts:171](D:/learn/openclaw/extensions/feishu/src/channel.ts:171)：`feishuMessageAdapter`。
- [feishu/channel.ts:173](D:/learn/openclaw/extensions/feishu/src/channel.ts:173)：`durableFinal`。
- [feishu/channel.ts:649](D:/learn/openclaw/extensions/feishu/src/channel.ts:649)：`feishuPlugin`。
- [feishu/channel.ts:1312](D:/learn/openclaw/extensions/feishu/src/channel.ts:1312)：动态导入 `monitorFeishuProvider`。
- [feishu/channel.ts:1328](D:/learn/openclaw/extensions/feishu/src/channel.ts:1328)：启动 Feishu monitor。
- [feishu/channel.ts:1340](D:/learn/openclaw/extensions/feishu/src/channel.ts:1340)：挂载 `message: feishuMessageAdapter`。

Feishu 出站能力：

- [send.ts:535](D:/learn/openclaw/extensions/feishu/src/send.ts:535)：`SendFeishuMessageParams`。
- [send.ts:599](D:/learn/openclaw/extensions/feishu/src/send.ts:599)：`sendMessageFeishu`。
- [send.ts:646](D:/learn/openclaw/extensions/feishu/src/send.ts:646)：`sendCardFeishu`。
- [send.ts:789](D:/learn/openclaw/extensions/feishu/src/send.ts:789)：`sendStructuredCardFeishu`。
- [send.ts:834](D:/learn/openclaw/extensions/feishu/src/send.ts:834)：`sendMarkdownCardFeishu`。

Feishu 入站传输：

- [monitor.transport.ts:220](D:/learn/openclaw/extensions/feishu/src/monitor.transport.ts:220)：WebSocket monitor。
- [monitor.transport.ts:340](D:/learn/openclaw/extensions/feishu/src/monitor.transport.ts:340)：Webhook monitor。
- [monitor.transport.ts:355](D:/learn/openclaw/extensions/feishu/src/monitor.transport.ts:355)：Webhook port/path/host 配置。
- [monitor.transport.ts:367](D:/learn/openclaw/extensions/feishu/src/monitor.transport.ts:367)：校验过的 webhook 请求会更新 transport health。

Feishu 入站顺序队列：

- [sequential-queue.ts:5](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:5)：按 key 串行执行任务。
- [sequential-queue.ts:7](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:7)：不同 key 可并发。
- [sequential-queue.ts:24](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:24)：`SequentialQueueOptions`。
- [sequential-queue.ts:42](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:42)：`createSequentialQueue`。
- [monitor.message-handler.ts:164](D:/learn/openclaw/extensions/feishu/src/monitor.message-handler.ts:164)：`createFeishuMessageReceiveHandler`。
- [monitor.message-handler.ts:187](D:/learn/openclaw/extensions/feishu/src/monitor.message-handler.ts:187)：创建 sequential queue。
- [monitor.message-handler.ts:215](D:/learn/openclaw/extensions/feishu/src/monitor.message-handler.ts:215)：按 sequential key 入队。

## 5. 并发与队列模型

OpenClaw 并不是“一个平台线程 + 一个共享 queue”的简单模型。更准确的描述是：

1. 核心层有 durable ingress queue，用于存储、claim、complete、fail、release 入站事件。
2. 平台层根据平台语义做局部队列，例如按 Discord conversation key 串行、Slack debounce、Feishu same-chat FIFO。
3. 网关和 monitor 以 async 生命周期运行，插件通过 `monitor*Provider` 接入。

### 5.1 核心 durable ingress queue

核心队列位置：[src/channels/message/ingress-queue.ts](D:/learn/openclaw/src/channels/message/ingress-queue.ts:1)。

关键类型和行为：

- [ingress-queue.ts:24](D:/learn/openclaw/src/channels/message/ingress-queue.ts:24)：`ChannelIngressQueueRecord`。
- [ingress-queue.ts:40](D:/learn/openclaw/src/channels/message/ingress-queue.ts:40)：被 worker claim 的 `ChannelIngressQueueClaim`。
- [ingress-queue.ts:60](D:/learn/openclaw/src/channels/message/ingress-queue.ts:60)：completed tombstone，用于重复检测。
- [ingress-queue.ts:70](D:/learn/openclaw/src/channels/message/ingress-queue.ts:70)：failed tombstone，用于重复检测和诊断。
- [ingress-queue.ts:121](D:/learn/openclaw/src/channels/message/ingress-queue.ts:121)：`ChannelIngressQueue` 接口。
- [ingress-queue.ts:351](D:/learn/openclaw/src/channels/message/ingress-queue.ts:351)：`createChannelIngressQueue`。
- [ingress-queue.ts:375](D:/learn/openclaw/src/channels/message/ingress-queue.ts:375)：enqueue 使用 `runOpenClawStateWriteTransaction`。
- [ingress-queue.ts:475](D:/learn/openclaw/src/channels/message/ingress-queue.ts:475)：claim 相关写事务。
- [ingress-queue.ts:605](D:/learn/openclaw/src/channels/message/ingress-queue.ts:605)：complete 相关写事务。

该队列支持：

- pending / claimed / completed / failed 状态区分。
- duplicate detection。
- claim token 防止错误 worker 完成别人的任务。
- stale claim recovery。
- pending/completed/failed retention prune。
- `laneKey` / `blockedLaneKeys` 控制同一 lane 的串行处理。

Python 迁移建议：

- 先实现一个 `IngressQueue` 协议，底层用 SQLite。
- `enqueue(event_id, payload, lane_key=None)` 必须幂等。
- `claim_next(owner_id, blocked_lane_keys)` 返回 claim token。
- `complete(claim)` / `release(claim)` / `fail(claim)` 必须校验 claim token。
- 每个平台 handler 不直接调用 agent，而是先入 durable queue；worker 再从 queue 派发。

### 5.2 平台层局部队列

Discord 使用 `DiscordInboundJob.queueKey` 作为 run queue 序列化 key：

- [inbound-job.ts:25](D:/learn/openclaw/extensions/discord/src/monitor/inbound-job.ts:25)。
- [inbound-job.ts:32](D:/learn/openclaw/extensions/discord/src/monitor/inbound-job.ts:32)。

Slack 使用 debouncer 合并/去重短时间内的消息：

- [message-handler.ts:105](D:/learn/openclaw/extensions/slack/src/monitor/message-handler.ts:105)：基于 `buildSlackDebounceKey`。
- [message-handler.ts:335](D:/learn/openclaw/extensions/slack/src/monitor/message-handler.ts:335)：`debouncer.enqueue(...)`。

Feishu 使用 per-key sequential queue 保证同 chat 顺序：

- [sequential-queue.ts:42](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:42)：Map key -> Promise chain。
- [sequential-queue.ts:48](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:48)：取前一个同 key task。
- [sequential-queue.ts:51](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:51)：设置下一个 promise。
- [sequential-queue.ts:65](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:65)：`Promise.race([task(), timeoutPromise])`，超时后释放阻塞链。

## 6. 入站消息格式：没有单一 InboundMessage，但有统一生命周期上下文

OpenClaw 的入站消息不是一个全局统一的 `InboundMessage` dataclass。更接近的统一层是：

- `MessageReceiveContext<TMessage>`：包住平台原始消息、通道、账号、ack 状态。
- 平台自己的 prepared message 类型：例如 Slack 的 `PreparedSlackMessage`。
- turn kernel / dispatch 管线：把平台事件组装成可投递给 agent 的 channel turn。

关键位置：

- [receive.ts:18](D:/learn/openclaw/src/channels/message/receive.ts:18)：`MessageReceiveContext<TMessage>`。
- [message-handler/types.ts:12](D:/learn/openclaw/extensions/slack/src/monitor/message-handler/types.ts:12)：`PreparedSlackMessage`。
- [turn/kernel.ts:404](D:/learn/openclaw/src/channels/turn/kernel.ts:404)：`dispatchAssembledChannelTurn`。
- [turn/kernel.ts:655](D:/learn/openclaw/src/channels/turn/kernel.ts:655)：`runPreparedChannelTurn`。
- [turn/kernel.ts:840](D:/learn/openclaw/src/channels/turn/kernel.ts:840)：`runChannelInboundEvent`。

Python 迁移时建议分两层：

```python
@dataclass
class RawInboundEvent(Generic[T]):
    id: str
    channel: str
    account_id: str | None
    platform_payload: T
    received_at: float
    ack_policy: MessageAckPolicy

@dataclass
class PreparedInboundMessage:
    id: str
    channel: str
    account_id: str | None
    conversation_id: str
    sender_id: str
    text: str
    thread_id: str | None = None
    reply_to_id: str | None = None
    attachments: list[Attachment] = field(default_factory=list)
    raw: Mapping[str, Any] = field(default_factory=dict)
```

也就是说，`InboundMessage` 可以存在于 Python 版本中，但最好是“平台 payload -> prepared message -> agent turn”的中间产物，而不是直接等同于平台 webhook/update 对象。

## 7. Telegram Offset 存储

Telegram offset 实现位于 [extensions/telegram/src/update-offset-store.ts](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:1)。

### 7.1 状态结构

`TelegramUpdateOffsetState` 是版本化对象：

- [update-offset-store.ts:7](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:7)：当前 `STORE_VERSION = 3`。
- [update-offset-store.ts:12](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:12)：`TelegramUpdateOffsetState`。
- [update-offset-store.ts:13](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:13)：`version`。
- [update-offset-store.ts:14](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:14)：`lastUpdateId`。
- [update-offset-store.ts:15](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:15)：`botId`。
- [update-offset-store.ts:16](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:16)：`tokenFingerprint`。

这比明文 offset 文件多了三类保护：

- 版本号用于迁移旧结构。
- `botId` 防止不同 bot 复用旧 offset。
- `tokenFingerprint` 识别 token rotation，避免误跳过新 bot 的 update。

### 7.2 Keyed store

Telegram 不直接 `fs.writeFile(offset.txt)`，而是打开 plugin-state keyed store：

- [update-offset-store.ts:9](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:9)：namespace `telegram.update-offsets`。
- [update-offset-store.ts:31](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:31)：`openUpdateOffsetStore`。
- [update-offset-store.ts:34](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:34)：`getTelegramRuntime().state.openKeyedStore<TelegramUpdateOffsetState>(...)`。

keyed store 的接口：

- [plugin-state-store.types.ts:11](D:/learn/openclaw/src/plugin-state/plugin-state-store.types.ts:11)：`PluginStateKeyedStore<T>`。
- [plugin-state-store.types.ts:12](D:/learn/openclaw/src/plugin-state/plugin-state-store.types.ts:12)：`register(key, value)`。
- [plugin-state-store.types.ts:19](D:/learn/openclaw/src/plugin-state/plugin-state-store.types.ts:19)：`lookup(key)`。
- [plugin-state-store.types.ts:21](D:/learn/openclaw/src/plugin-state/plugin-state-store.types.ts:21)：`delete(key)`。
- [plugin-state-store.ts:1](D:/learn/openclaw/src/plugin-state/plugin-state-store.ts:1)：插件状态 store 是持久化 per-plugin state operations。
- [plugin-state-store.ts:284](D:/learn/openclaw/src/plugin-state/plugin-state-store.ts:284)：异步 `register` 实现入口。
- [plugin-state-store.ts:319](D:/learn/openclaw/src/plugin-state/plugin-state-store.ts:319)：异步 `lookup`。
- [plugin-state-store.ts:337](D:/learn/openclaw/src/plugin-state/plugin-state-store.ts:337)：异步 `delete`。

### 7.3 读写与 rotation

读取 offset：

- [update-offset-store.ts:134](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:134)：`readTelegramUpdateOffset`。
- [update-offset-store.ts:143](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:143)：`lookup(key)`。
- [update-offset-store.ts:146](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:146)：`safeParseState`。
- [update-offset-store.ts:150](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:150)：检测 rotation 后返回 `null`。

写入 offset：

- [update-offset-store.ts:159](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:159)：`writeTelegramUpdateOffset`。
- [update-offset-store.ts:165](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:165)：校验 update id 必须是非负 safe integer。
- [update-offset-store.ts:168](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:168)：构造 version 3 payload。
- [update-offset-store.ts:174](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:174)：`register(accountId, payload)`。

删除 offset：

- [update-offset-store.ts:180](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:180)：`deleteTelegramUpdateOffset`。

旧文件迁移：

- [update-offset-store.ts:195](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:195)：`listTelegramLegacyUpdateOffsetEntries`。
- [update-offset-store.ts:200](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:200)：读取 legacy JSON 文件。

OpenClaw SDK 也提供 atomic JSON 写入工具：

- [json-store.ts:16](D:/learn/openclaw/src/plugin-sdk/json-store.ts:16)：`readJsonFileWithFallback`。
- [json-store.ts:27](D:/learn/openclaw/src/plugin-sdk/json-store.ts:27)：注释说明 secure permissions + atomic replacement。
- [json-store.ts:28](D:/learn/openclaw/src/plugin-sdk/json-store.ts:28)：`writeJsonFileAtomically`。

Python 迁移建议：

```python
@dataclass
class TelegramUpdateOffsetState:
    version: int
    last_update_id: int | None
    bot_id: str | None
    token_fingerprint: str | None

class KeyedStateStore(Protocol[T]):
    async def register(self, key: str, value: T, ttl_ms: int | None = None) -> None: ...
    async def lookup(self, key: str) -> T | None: ...
    async def delete(self, key: str) -> bool: ...
```

底层可以先用 SQLite，也可以用 JSON 文件。但如果用 JSON 文件，不建议写成单个 `offset.txt`，而应该：

1. 写完整版本化对象。
2. 先写临时文件。
3. `fsync` 后原子 rename 替换。
4. 记录 bot id/token fingerprint，token rotation 时丢弃旧 offset。

## 8. 推荐的 Python 迁移分层

### 8.1 包结构建议

```text
pyclaw/
  channels/
    core.py              # ChannelPlugin, capabilities, registry
    message/
      types.py           # receipt, send context, receive context
      adapter.py         # define_channel_message_adapter
      receive.py         # ack/nack state machine
      send.py            # durable send context
      ingress_queue.py   # SQLite durable ingress queue
  plugins/
    telegram/
      channel.py
      monitor.py
      offset_store.py
    discord/
      channel.py
      monitor.py
      outbound.py
    slack/
      channel.py
      monitor.py
    feishu/
      channel.py
      monitor.py
      sequential_queue.py
  state/
    plugin_state.py      # keyed JSON/SQLite store
```

### 8.2 最小可落地迁移顺序

1. 先移植核心类型：`ChannelPlugin`、`ChannelMessageAdapter`、`MessageReceipt`、`MessageReceiveContext`。
2. 实现 `PluginStateKeyedStore`，用于 Telegram offset 和后续平台状态。
3. 实现 durable ingress queue，底层优先 SQLite。
4. 迁移 Telegram：offset store + polling monitor + send text/media。
5. 迁移 Feishu：webhook/ws monitor + per-chat sequential queue。
6. 再迁移 Slack/Discord：因为它们的平台事件和 rich message/action 能力更复杂。
7. 最后补 durable final delivery、unknown send reconciliation、live preview/finalizer。

### 8.3 不建议省略的能力

- ack policy：否则 webhook/polling 平台在失败场景下容易重复或丢消息。
- receipt：否则后续 edit/delete/thread reply/durable retry 很难实现。
- keyed state store：否则 offset、绑定关系、去重记录会散落成平台私有文件。
- per-conversation queue/lane：否则同一会话内消息顺序可能错乱。
- lifecycle hooks：否则迁移、清理旧状态、启动维护任务会无处挂载。

## 9. 阅读源码顺序

建议按以下顺序阅读：

1. [src/channels/plugins/types.plugin.ts](D:/learn/openclaw/src/channels/plugins/types.plugin.ts:66)：理解插件根对象。
2. [src/channels/message/types.ts](D:/learn/openclaw/src/channels/message/types.ts:13)：理解 message contract。
3. [src/channels/message/receive.ts](D:/learn/openclaw/src/channels/message/receive.ts:18)：理解 ack/nack。
4. [src/channels/message/send.ts](D:/learn/openclaw/src/channels/message/send.ts:162)：理解 durable send。
5. [src/channels/message/ingress-queue.ts](D:/learn/openclaw/src/channels/message/ingress-queue.ts:121)：理解 durable ingress queue。
6. [extensions/telegram/src/update-offset-store.ts](D:/learn/openclaw/extensions/telegram/src/update-offset-store.ts:12)：理解 offset 版本化状态。
7. [extensions/telegram/src/channel.ts](D:/learn/openclaw/extensions/telegram/src/channel.ts:247)：看一个消息 adapter 和插件挂载示例。
8. [extensions/discord/src/outbound-adapter.ts](D:/learn/openclaw/extensions/discord/src/outbound-adapter.ts:107)：看复杂出站 adapter。
9. [extensions/slack/src/monitor/message-handler.ts](D:/learn/openclaw/extensions/slack/src/monitor/message-handler.ts:92)：看入站去重/合并。
10. [extensions/feishu/src/sequential-queue.ts](D:/learn/openclaw/extensions/feishu/src/sequential-queue.ts:42)：看 per-key 顺序队列。

## 10. 结论

OpenClaw 的 Channel 系统已经从“平台收发接口”演进为“插件化消息运行时”。它的核心价值不只是接入 Telegram、Discord、Slack、Feishu 等平台，而是把消息收发中的可靠性问题拆成了可组合模块：

- 平台插件负责平台 SDK、鉴权、monitor、send。
- message contract 负责 send/receive/lifecycle/durable/live 的统一抽象。
- ingress queue 负责入站事件可靠调度和重复检测。
- plugin-state keyed store 负责平台状态、offset、迁移状态的持久化。
- 平台局部队列负责保持同一会话/同一 sender 的顺序与防抖。

Python 版本 `pyclaw` 如果要成为可维护的开源项目，建议保留这些边界，只把 TypeScript 类型和 async 运行时替换为 Python 的 `dataclass` / `Protocol` / `asyncio` / SQLite 实现。这样既能加入自己的业务逻辑，也不会把平台差异、可靠投递、offset、ack、重试全部揉进一个难以演化的 `Channel.receive()` / `Channel.send()` 基类中。
