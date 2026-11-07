# 事件原包合同草案（未冻结）

状态：PR1 草案。Kotlin / djifly **尚未**验证本文件与 `golden-vectors.json`。
[djifly#19](https://github.com/UavPlatform/djifly/pull/19) 的 `96ea023` 只核对了一个合成夹具（`fixtureIdentity`），不能当作本身份、本载荷或本哈希的验收。

服务端自行重算哈希，不信任客户端回显。收到的原始 bytes 原样保存。同包相等看规范内容的 `contentDigest`，原始 bytes 的 SHA-256 不是 `contentDigest`。

## 端点

- `POST /dispatch/pilot/orders/{orderId}/event-packets`
- `POST /dispatch/pilot/orders/{orderId}/event-reconciliation`

正文是 `application/json` 原始字节。带 charset 时必须是 UTF-8。不经 Jackson 绑定。响应仍是现有 `Result` 信封。

## 身份

`chainIdentity` 的字段集合，按 JCS 键序，恰好是：

`assignmentVersion`, `chainId`, `hashVersion`, `modelCode`, `orderId`, `recordingSourceId`, `schemaVersion`, `serialNumber`, `serialSource`

`schemaVersion` 与 `hashVersion` 放进锚点是本草案的明确选择。展示名不是字段，未知根字段整包拒绝。

同一 `(orderId, assignmentVersion, recordingSourceId, serialNumber, serialSource, modelCode)` 只对应一条链。`recordingSourceId` 只作追溯，本草案不锁单来源排他。序列号区分大小写，不走旧的 `SerialNumbers.normalize`。`FLIGHT_CONTROLLER` 与 `PRODUCT` 是示例来源令牌，不是已核实的 SDK 枚举，也不互相别名。飞控 SN 与 Product SN 不是同一机身。

## 哈希

整数剖面、UTF-8 无 BOM、不做 Unicode normalization。先做严格解析，再做 JCS。

- `C = JCS(完整包去掉 chainHash 字段，保留 prevChainHash 和事件原顺序)`
- `H0 = SHA256(UTF8("UAV-EVENT-ANCHOR-v1\n") || JCS(chainIdentity))`
- `Hn = SHA256(UTF8("UAV-EVENT-PACKET-v1\n") || raw32(prevChainHash) || C)`
- `contentDigest = SHA256(JCS(完整包，含 chainHash))`

首包 `height = 1`，`prevPacketId = null`，`prevChainHash = H0`。缺字段和 JSON `null` 不是一回事；首包的 `prevPacketId: null` 是列明的例外。

数字只接受整数 token：拒绝前导零、指数、小数、`-0`、绝对值大于 `2^53-1`。UUID 小写。哈希 64 位小写十六进制。重复键、非法 UTF-8、孤立代理项、未知字段在 JCS 之前拒绝。生产路径不使用 erdtman JCS 1.1 做解析；该库只在测试里对照合法整数的规范输出。

黄金向量：`spec/event-contract/golden-vectors.json`。Node 复核脚本：`spec/event-contract/verify-golden.mjs`。Java 断言：`EventGoldenVectorTest`。

## 事件

根对象恰好 15 个键：`schemaVersion`, `hashVersion`, `orderId`, `assignmentVersion`, `recordingSourceId`, `chainId`, `serialNumber`, `serialSource`, `modelCode`, `packetId`, `prevPacketId`, `height`, `prevChainHash`, `chainHash`, `events`。

事件恰好 `eventId`, `capturedAt`, `type`, `payload`。`capturedAt` 是 epoch 毫秒。`events` 不得为空。同包重复 `eventId` 整包拒绝，不落确认。

候选类型与草案单位（**不是**已核实的 DJI SDK 量纲）：

| type | payload | 范围 |
| --- | --- | --- |
| POSITION | `latitudeDegE7`, `longitudeDegE7` | 纬度 [-900000000, 900000000]，经度 [-1800000000, 1800000000] |
| ALTITUDE | `altitudeMm` | [-10000000, 100000000]，基准面未核实 |
| VELOCITY | `speedMmps` | [0, 2000000]，轴向未核实 |
| BATTERY | `chargePermille` | [0, 1000] |
| ACTUAL_FLYING_STATE | `flying` | bool |
| ACTUAL_MOTOR_STATE | `motorOn` | bool |
| OPERATION_INTENT | `operation`, `clientNonce` | 令牌 + 小写 UUID |
| OPERATION_RESULT | 同上再加 `succeeded` | bool |
| RECORDING_CUTOFF | `cutoffId` | 小写 UUID，且不得等于本事件 `eventId` |

缺值就不发该事件，不补 0 或 false。未知类型且 payload 为对象时，该条 `UNKNOWN_EVENT_TYPE`，包仍确认。可解析但语义不合法的事件逐条 `REJECTED`，留在原哈希里。

`POSITION` / `ALTITUDE` / `VELOCITY` / `BATTERY` 同链同类型间隔必须 ≥ 2000ms，相等允许。实际状态与操作不限频。间隔按先入库者计算，不可交换，且不回写已发出的 receipt。`capturedAt` 须落在服务端当前时间的过去 7 天到未来 10 分钟内，这是技术窗口，不是产品规则。

## receipt 与 progress

receipt 固定：`receiptVersion`, `packetId`, `contentDigest`, `chainHash`, `durability=RAW_AND_RESULTS_COMMITTED`, `decidedAt`, `results[{eventId, outcome, reason, sourcePacketId}]`。

progress 动态：`validationVersion`, `contiguousHeight`, `contiguousHash`, `highestReceivedHeight`, `missingRanges`, `missingCursor`, `integrity`（`OPEN` 或 `FORKED`）, `projectionRevision`。本阶段 `projectionRevision` 保持 0。连续高度不越洞。最高收到高度不是连续高度。

同 `packetId`、同一规范内容：返回最初 receipt，`ACCEPTED` 不会变成 `DUPLICATE`。空白不同但规范相同的重放也不替换原始 bytes。同 ID 异内容：409，持久冲突证据，不覆盖。

跨包同一 `eventId`、同一归属、同一内容：`DUPLICATE`，`sourcePacketId` 指向原包，不第二份投影。异内容或异归属：该条 `REJECTED` / `EVENT_ID_CONFLICT`，新包原文仍保存，原事实不改。

格式、schema、自哈希错误没有完整包确认。暂缺前包不是永久事件拒绝。补洞只根据已存后包原文续链，不回写 receipt。分叉留下证据，不覆盖历史。

完整确认是原包、逐事件固定结果和已接受事实的同一次提交。投影可以晚于确认。

## 对账

`POST event-reconciliation` 的键全部必填：`schemaVersion`, `chainId`, `validationVersion`, `fromHeight`, `toHeight`, `cursor`, `missingCursor`, `limit`, `prefix`。`validationVersion`、两个 cursor、`prefix` 可以是 JSON `null`。`null` 版本表示按当前版本读。版本不一致返回 409，`message` 含 `RECONCILIATION_VERSION_CHANGED current=<n>`，不返回残页。

`prefix` 为 null 时 `commonPrefixHeight` 为 null。提供了前缀且第一项就不匹配时为 0。否则为已连续匹配的最后高度。正常路径直接给出请求区间，不必每次二分。

## 技术上限

这些是工程上限，不是价格：正文 262144 字节；事件与数组 500；深度 8；字符串 1024 个码元；对象键 32；高度 1..1000000；每链 2000 包；对账跨度 500；`limit` 1..200；前缀最多 64；上传响应里的缺洞最多 50 段；上传与对账各 600 次 / 60 秒。`schemaVersion`、`hashVersion`、`receiptVersion` 目前都是 1。

401 用原包重试。429 带 `Retry-After`（秒）后退避。408 与 5xx 保留原包重试。提交前失败会整笔回滚，不留下半份 receipt。

## 授权

新包要求调用者是订单当前 `assignedPilotId`，且包内 `assignmentVersion` 等于订单当前轮次。不允许把缺失或 null 当成 0。`CANCELLED` 拒绝新包。不要求先到场，也不要求先 `connect`。

已经确认的包，原链飞手在放单或取消后仍可重放并拿到原来的 receipt。放单后的新包拒绝。换一轮 `assignmentVersion` 就是另一条链。从未确认的包在改派之后补传，本草案不接受。

上传与对账先锁订单行，再按 `chain_id` 锁定该订单的链。后续结单必须复用这把锁。

## 数据库

Flyway `V27`（Java 迁移）分开保存：`flight_event_chain`、`flight_event_packet`（原始 bytes、规范 UTF-8、两种哈希）、`flight_event_receipt`、`flight_event_result`、`flight_event_fact`、`flight_event_conflict`。

序列号、来源、机型用 `VARBINARY`。UUID 用 `utf8mb4_bin`。`order_id` 沿用 `dispatch_order.id` 的排序规则，以便外键合法。不用 `INSERT IGNORE`。不改 V1–V26 的校验和。

现有 180 天清理只删除 `dispatch_flight_sample`。它不会删除事件原包。本阶段不新增自动清理，也不宣称永久保存已经批准。补洞所需的后包原文必须留到续链完成之后；删除策略未定。

## 本阶段明确不做

不改 `DISTANCE_V1`、定金或抵扣。不把飞行时长做成价格。不删除 `/live/*`、设备 WS、`connect` / `close` / `activeSerial`。不扇出新事件，不更新 `executed_*`。不实现截止结单。轨迹、最新状态、遥测段和空中时长仍是以后的重算投影；空中时长只认实际飞行事实，电机累计时间、点击和首尾遥测都不能代替。
