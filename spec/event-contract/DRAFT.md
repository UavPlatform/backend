# 事件原包合同草案（未冻结）

状态：PR1 草案，合同仍未冻结。Kotlin 离线核对过 `a11d931` 的四条包向量、九字段身份、H0、`chainHash`、`contentDigest` 与非 ASCII fixture，并报告 JCS 33 / 存储 17 通过。那是离线核验，没有 HTTP 联通。
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

数字只接受整数 token：拒绝前导零、指数、小数、`-0`、绝对值大于 `2^53-1`。UUID 小写。哈希 64 位小写十六进制。重复键、非法 UTF-8、孤立代理项、未知字段在 JCS 之前拒绝。`\u` 转义的四位十六进制只接受 ASCII `0-9a-fA-F`；全角或其他非 ASCII 数字在严格解析时拒绝。字符串里的 U+2028 和 U+2029 按 RFC 8785 保留为 UTF-8，不转成 `\u2028` / `\u2029`。生产路径不使用 erdtman JCS 1.1 做解析；该库只在测试里对照合法整数的规范输出。

共享非 ASCII 夹具在 `golden-vectors.json` 的 `canonicalFixtures`。Node 脚本用自己的规范器重算这些字节，不调用本仓库的 Java。Kotlin 仍未独立验证。

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

未知根字段或事件字段由 `EventPacketSchema` 整包拒绝（`SCHEMA_FIELDS`），没有 receipt。已知类型的 payload 多了或少了字段，由 `EventPayloads` 把该条记为 `PAYLOAD_INVALID`，包和 receipt 仍固定。

`capturedAt` 是绝对 epoch 毫秒。服务端不把它按 Asia/Shanghai 或别的本地时区重解释。对人展示的时区不进入哈希。晚于服务器当前时间 10 分钟的采集是 `EVENT_TIME_WINDOW`。这 10 分钟只校验未来时钟，不扩大 `accepted_at` / `ended_at`。作业资格只看该轮已经写下的区间。过去多久才第一次上传，本身不是拒绝理由；这里不设 7 天或 30 天离线上限。

采样间隔是客户端的稀疏策略。服务端不按到达先后用 2 秒改写 receipt。状态跳变和操作同样不按这个间隔拒绝。若以后要降采样，只在可重算投影里做，不改原包和 receipt。

## receipt 与 progress

receipt 固定：`receiptVersion`, `packetId`, `contentDigest`, `chainHash`, `durability=RAW_AND_RESULTS_COMMITTED`, `decidedAt`, `results[{eventId, outcome, reason, sourcePacketId}]`。

progress 动态：`validationVersion`, `contiguousHeight`, `contiguousHash`, `highestReceivedHeight`, `missingRanges`, `missingCursor`, `integrity`（`OPEN` 或 `FORKED`）, `projectionRevision`。本阶段 `projectionRevision` 保持 0。连续高度不越洞。最高收到高度不是连续高度。

同 `packetId`、同一规范内容：返回最初 receipt，`ACCEPTED` 不会变成 `DUPLICATE`。空白不同但规范相同的重放也不替换原始 bytes。同 ID 异内容：409，持久冲突证据，不覆盖。

跨包同一 `eventId`、同一归属、同一内容：`DUPLICATE`，`sourcePacketId` 指向原包，不第二份投影。异内容或异归属：该条 `REJECTED` / `EVENT_ID_CONFLICT`，新包原文仍保存，原事实不改。

格式、schema、自哈希错误没有完整包确认。暂缺前包不是永久事件拒绝。补洞只根据已存后包原文续链，不回写 receipt。分叉留下证据，不覆盖历史。

完整确认是原包、逐事件固定结果和已接受事实的同一次提交。投影可以晚于确认。

## 对账

`POST event-reconciliation` 的键全部必填：`schemaVersion`, `chainId`, `validationVersion`, `fromHeight`, `toHeight`, `cursor`, `missingCursor`, `limit`, `prefix`。`validationVersion`、两个 cursor、`prefix` 可以是 JSON `null`。`null` 版本表示按当前版本读。版本不一致返回 409，`message` 含 `RECONCILIATION_VERSION_CHANGED current=<n>`，不返回残页。

`prefix` 为 null 时 `commonPrefixHeight` 为 null。共同前缀只沿连续已验证历史计算：从高度 1 起每次加 1，且不得超过 `contiguousHeight`。洞上或分叉之后的候选即使哈希相等也不计入。没有已验证历史、第一项不是高度 1、或哈希不匹配时为 0。正常路径直接给出请求区间，不必每次二分。

## 技术上限

这些是工程上限，不是价格，也不是作业时长：单请求正文 262144 字节；事件与数组 500；深度 8；字符串 1024 个码元；对象键 32；高度 1..1000000；对账跨度 500；`limit` 1..200；前缀最多 64；上传响应里的缺洞最多 50 段；上传与对账各 600 次 / 60 秒。`schemaVersion`、`hashVersion`、`receiptVersion` 目前都是 1。没有整单或整链累计包数上限。

`limit` 先按 long 检查 1..200，再转成 int。`2147483648` 与 `4294967297` 都是 LIMIT，不能先窄化成 int。

未验证乱序跨度是 2000 个高度：新高度可以超前连续前缀，但超前距离不能超过这个跨度。这只限制还没接上的积压，连续链在前缀追上之后可以继续长过 2000 包。已经落在当前最高已收高度以内的洞始终可补，不因为积压已满而拒绝。高度 1000000 在前缀还是 0 时会留下补不上的空洞，拒绝。`(chain_id, height)` 唯一索引支撑按高度的有界读取。每次续链只读一页链元数据（高度、前后包、两个哈希），不把后继原包全文读进来；这一页接上之后继续下一页，直到遇到洞、分叉或后继读完。原包仍原样保存，续链不重写它。

401 用原包重试。429 带 `Retry-After`（秒）后退避。408 与 5xx 保留原包重试。提交前失败会整笔回滚，不留下半份 receipt。

## 授权

新包按采集时的历史授权核验，不看订单当前 stage，也不要求调用者仍是当前 `assignedPilotId`。证据只用已有的 `dispatch_assignment` 行：`pilot_id`、`accepted_at`、`ended_at`。`accepted_at` 在接单时写入。放单、货主取消、完成都会写入 `ended_at`。没有对应行（例如版本 0 或从未接过的版本）拒绝，不编造作业区间。`assignmentVersion` 缺失或 0 仍是 schema 错误。

调用者必须是该行的 `pilot_id`。其他人是 403。不要求先到场，也不要求先 `connect`。

作业窗口就是这一行的 `accepted_at` 与 `ended_at`，不再向两侧加 10 分钟。两端都按数据库时间列读出的 epoch 毫秒与包内 `capturedAt` 比较，不把 `capturedAt` 改写成某个时区的本地时间。已结束的轮次是 `[accepted_at, ended_at)`：起始包含，结束不包含，避免放单时刻同时属于新旧两名飞手。尚未结束的轮次没有作业上界；未来 10 分钟仍由独立的 `EVENT_TIME_WINDOW` 校验。窗口外的事件逐条 `ASSIGNMENT_WINDOW`，原包和 receipt 仍提交，不因后来的放单、改派或取消改写。窗口内的迟到包由当时的飞手补传，即使订单已经放单、改派或取消，也无论离线多久。

已经确认的包，原链飞手在放单或取消后重放，拿到的是最初 receipt。换一轮 `assignmentVersion` 就是另一条链：旧飞手不能往新轮次写，新飞手不能往旧轮次写。

上传与对账先锁订单行，再按 `chain_id` 锁定该订单的链。后续结单必须复用这把锁。

## 数据库

Flyway `V27`（Java 迁移）分开保存：`flight_event_chain`、`flight_event_packet`（原始 bytes、规范 UTF-8、两种哈希）、`flight_event_receipt`、`flight_event_result`、`flight_event_fact`、`flight_event_conflict`。

序列号、来源、机型用 `VARBINARY`。UUID 用 `utf8mb4_bin`。`order_id` 沿用 `dispatch_order.id` 的排序规则，以便外键合法。不用 `INSERT IGNORE`。不改 V1–V26 的文件，也不改它们已经写入的 checksum。V21 与 V27 都是 Java 迁移，沿用 Flyway `BaseJavaMigration` 的 null checksum：历史表里这两行的 checksum 是 NULL、type 是 JDBC、success 是 1。SQL 迁移的 checksum 必须非空，并且在 26→27 之后保持原值。null 不是失败，也不把 NULL 读成 0。

现有 180 天清理只删除 `dispatch_flight_sample`。它不会删除事件原包。本阶段不新增自动清理，也不宣称永久保存已经批准。补洞所需的后包原文必须留到续链完成之后；删除策略未定。

## 本阶段明确不做

不改 `DISTANCE_V1`、定金或抵扣。不把飞行时长做成价格。不删除 `/live/*`、设备 WS、`connect` / `close` / `activeSerial`。不扇出新事件，不更新 `executed_*`。不实现截止结单，也不实现丢记录清理。轨迹、最新状态、遥测段、降采样和空中时长仍是以后的重算投影；空中时长只认实际飞行事实，电机累计时间、点击和首尾遥测都不能代替。
