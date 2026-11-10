# Aerial Lite + 星投屏（合并版）

Aerial Lite 不只是桌面了：这个模块同时是电视端的 星投屏 Agent。一个 APK 里同时有
「tvOS 风格桌面」和「注册 / 心跳 / 遥控 / DLNA / 画面回传」，不再需要单独装 tv-app。

代码在 `lite/src/main/java/app/orchard/tv/lite/cast/`，桌面 UI 只动了 `Panels.kt`（控制中心）
和 `HomeActivity.kt`（启动服务）。

## 部件

| 文件 | 作用 |
|---|---|
| `CastConfig.kt` | 后端地址、设备 ID/token、设备名、ADB 端口；独立于桌面设置，重装桌面设置不丢 token |
| `CastService.kt` | 前台服务（`dataSync`），持有通知；Agent 与 DLNA 都挂在它下面 |
| `CastAgent.kt` | 注册（`POST /api/device/register`）、WebSocket（`/ws/device`）、5 秒心跳、命令分发、快照/预览 |
| `CastBootReceiver.kt` | `BOOT_COMPLETED` 时拉起服务（当桌面被固件抢走时仍然能上线） |
| `CastA11yService.kt` | 无障碍：只做返回/主页/最近任务/通知栏四个全局动作，**不读屏幕内容** |
| `CastNetwork.kt` | 局域网 IPv4 |
| `KeyExecutor.kt` | 按键统一入口：全局键走无障碍，其余走 adbd |
| `LegacyAdbClient.java` | 从 tv-app 原样搬来的 ADB 客户端（127.0.0.1:5555，RSA 授权） |
| `P2pChannel.kt` | UDP 打洞 + HMAC 信封，公网遥控时按键直连，不经后端中继 |
| `DlnaRenderer.kt` | SSDP + 设备描述 + AVTransport，手机投屏推流 |

## 桌面上的入口

控制中心（右上角状态胶囊）→ **投屏**：

- 主面板一行显示 `投屏 · 已连接 / 等待审核 / 连接异常 / 未配置后端`
- 点进去是「投屏设置」：状态、停止/启动、无障碍状态（未开启可直接跳到系统设置页）、
  ADB 端口、后端地址、设备名、重新绑定设备、设备 ID

## 后端契约

- `POST /api/device/register`：`{device_id, name, model, app_version, lan_ip}` → `device_token`
- `WS /ws/device`：首帧 `{type:"auth", device_id, token}`；之后每 5 秒
  `{type:"heartbeat", device_id, ts, data:{name, model, app_version, lan_ip, metadata:{udp_p2p_transport:"ready", nat_type}}}`  
  （`data.metadata` 是后端判定「支持 UDP 直连」的唯一依据）
- 命令：`{type:"command", command:{id, type, payload}}`，回
  `{type:"command_result", device_id, ts, data:{command_id, status, success, message}}`
- `p2p_start`：`{session_id, token, channel_key, rendezvous_host, rendezvous_port, expires_at}`
- 快照：`snapshot` 命令 → 以二进制 JPEG 帧回传；`preview_start/stop` 控制预览流

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17       # 或本机 JDK 17+
./gradlew :lite:assembleRelease                     # 正常包（app.xingmu.aerial）
./gradlew :lite:assembleRelease -PcastTestApk=true  # 旁路测试包（app.xingmu.aerial.cast）
./gradlew :lite:assembleRelease -PcastBackendUrl=http://192.168.1.10:18000
```

- `-PcastTestApk` 用于**不想卸载官方 Lite** 的电视：两个包名不同，可以并存。
- `-PcastBackendUrl` 只是把后端地址编进 `BuildConfig` 做首次引导；真正配置在电视上改。
- 不支持 `-Pnohome` 之外的其他变体组合；`-Pnohome` 的语义没变。

## 已验证（Android 15，`192.168.31.112`）

| 项 | 证据 |
|---|---|
| 注册 / 心跳 / 在线 | 设备 `lite-509408b38316`，`status=pending → approved`，`online=true`，`connected_agents=1` |
| 命令通道 | 发 `app/launch com.android.settings` → 指令 `completed`，电视前台变成 Settings |
| 遥控（ADB） | `down → ok → back`：焦点移动、打开图库、退回桌面 |
| 遥控（无障碍） | 发 `back` 关闭 Settings；未开启时如实返回「未开启」 |
| P2P | `selected_mode=udp_jpeg`，peer 建立后 UDP 发 `home`，`key_ack success=true`，前台从 Settings 回到桌面 |
| P2P 滑动 TTL | 40 秒内 `expires_at` 前进 31 秒（设备 15 秒 keepalive） |
| DLNA | `M-SEARCH` 收到 3 条应答；`SetAVTransportURI`+`Play` → 电视打开推过去的媒体 |
| 画面回传 | control WS 收到 88 KB JPEG，内容与电视当前界面一致 |
| 兼容性 | `./gradlew :lite:lintRelease` → 0 error（修掉了 `performGlobalAction`/`Notification#build` 两处 API 16 调用） |

## 回归（冒烟脚本）

`tools/cast_smoke.py` 会对着电视实际连着的后端把每条能力走一遍（打开应用、返回键、截图、
P2P 协商、DLNA 描述、在线状态），不通过就返回非 0：

```bash
python3 tools/cast_smoke.py --backend http://127.0.0.1:18000 \
    --admin admin --password '<后台密码>' --tv-ip 192.168.1.20
```

最近一次实机输出（Android 15，`192.168.31.112`）：

```
device: lite-1a1c5a88082  model: 23127PN0CC  app: 0.1.0-lite-cast
PASS  open an app  (completed)
PASS  remote BACK key  (completed)
PASS  screenshot round trip  (completed)
PASS  P2P session negotiates UDP  (udp_jpeg / direct_transport_ready)
PASS  DLNA description  (HTTP 200)
PASS  device online  (agents=1)
all checks passed
```

注意：脚本会真的打开电视上的「设置」、按一次返回、截一张图，并建立一个 P2P 会话（不发按键）。

## 排障记录（真机上踩到的）

- **`device already registered`（HTTP 409）**：清过 App 数据、或换过设备身份后，本机用同一个
  `device_id` 重新注册，后端会回 409。客户端现在会**换一个新身份重注册**（进入待审核），
  而不是每 5 秒重试同一个必然失败的请求。
- **待审核时不要每 5 秒重连**：现在是 15 秒退避，否则后端日志会被刷屏。
- **后台 `startActivity` 被静默丢弃**（Android 10+）：打开应用的指令改为**先走 adbd**
  （`monkey -p … LAUNCHER`），失败才退回 `startActivity`；否则会出现「指令 completed 但电视没反应」。
- **截图花屏**：`screencap -p` 经 adb shell 会被改写换行，改走 `screencap -p | base64`。
- **多实例 uvicorn**：同一端口上残留旧进程会让 WS 行为看起来「怪」（新代码没生效）。
  排查时先 `Get-NetTCPConnection -LocalPort 18000` 确认只有一个监听者。

## 边界（没做到的，别当成做到了）

- **Android 4.4 没有实机跑过**：只在 Android 15 上验证。Lint 的 API 级别检查覆盖了 4.0+，
  但 OkHttp/无障碍/adbd/DLNA 在 4.4 固件上的实际行为未知。
- **开机自启只到代码级**：接收器注册正确，但测试用的 MuMu 模拟器会拦截第三方自启、并在重启后
  把默认桌面换回自带 launcher，所以没能证明「开机直接进 Aerial Lite」。
- **截图依赖 ADB 或 Root**：普通 App 无法截别的应用，未开 ADB 时如实失败。
- **P2P 只承载按键**：画面仍走 WebSocket；UDP 分片/重传没做。
- **DLNA 没有事件订阅与音量控制**：`SUBSCRIBE` 只回 200 不发事件，部分手机 App 的进度条会不显示。
