# 手机端（星幕遥控）能力缺口

对比后端 `backend/api/*.py` 的全部路由与手机端 `phone/.../network/` 的实际调用，记录"后端已提供、手机端未接"的部分。只读审查结论，不含猜测。

## 一、已补齐（本轮）

| 功能 | 后端接口 | 修复 |
|---|---|---|
| 扫码兑换会员 | `POST /api/vip/redeem` + `redeem_uri` | 「我的 → 扫码兑换」原来只打开会员弹窗，现在真正启动扫码器 |
| 扫码绑定后的落地页 | `POST /api/pairing/{code}/claim` | claim 已自动 approved，原来跳"审批设备"空页；现在跳设备列表并立即刷新，新设备马上出现 |

## 二、仍缺（按优先级）

| 优先级 | 功能 | 后端接口 | 现状 |
|---|---|---|---|
| 高 | 人工创建订单（在线支付关闭时的兜底） | `POST /api/vip/orders` | `MainViewModel.createVipOrder` 已实现，界面无入口 |
| 高 | 设备事件时间线 | `GET /api/device/{id}/events` | `BackendClient.listEvents`、`DeviceEvent` 已写好，无调用 |
| 高 | 指令历史与结果 | `GET /api/device/{id}/commands` | `listCommands` 是死代码；界面只保留本次会话 8 条 |
| 中 | 设备编辑（改名/启停） | `PATCH /api/device/{id}` | 无封装、无入口 |
| 中 | 删除头像 | `DELETE /api/auth/avatar` | 只能换不能删 |
| 中 | 已上传 APK 列表复用 | `GET /api/upload/apk` | 每次都要重新选本地文件 |
| 低 | 控制通道实时事件 | WS `approval_requested` / `device_updated` / `settings_updated` | 手机只连 `/ws/control/{device_id}`，不连 `*`，审批角标/VIP 变化靠 8 秒轮询 |

## 三、界面里存在但不可达

| 入口 | 实际 |
|---|---|
| `DeviceDetailDialog` | 定义完整但从未调用（死 UI），其中的"打开电视无线调试""查看快照并遥控""删除旧设备"都不可达 |
| 「设为主控」 | 只改本地 `primaryDeviceId`，与后端 owner/primary 无关，容易误解 |

## 四、管理类接口（属 web-console 职责，不计入手机端缺陷）

用户管理、系统设置、支付/AI 测试、套餐与订单运营、兑换码生成、权益授予、版本发布、绑定成员管理——均在 `web-console` 覆盖。

## 五、建议顺序

1. 人工下单入口（在线支付未开时用户无路可走）；
2. 事件/指令历史（排障必需，接口和模型都现成）；
3. 设备改名（owner 自助改，后端已支持）；
4. 控制通道改连 `*`，让审批与会员状态实时。
