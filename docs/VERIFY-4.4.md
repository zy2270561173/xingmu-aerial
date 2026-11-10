# Android 4.4 实机验证（2026-11-10）

在真实 4.x 电视盒上跑通了星幕 Aerial（含投屏 Agent），此前该平台只有静态加固、没有实机证据。

## 设备

| 项 | 值 |
|---|---|
| 型号 | `M08`（Hi3798MV200，海思方案电视盒） |
| 系统 | Android **4.4.2**（API 19），1920×1080 |
| 连接 | `adb connect 192.168.31.242:5555` |
| 安装包 | `app.xingmu.aerial.cast`（旁路包，与官方 `app.orchard.tv.lite` 并存，未卸载任何东西） |

## 结果

| 能力 | 结果 | 证据 |
|---|---|---|
| 安装（v1 签名、minSdk 14） | ✅ | `Success`，与官方 Aerial Lite 并存 |
| 启动 / 桌面渲染 | ✅ | `AerialLite: 23 apps in 865 ms`，`23 tiles made in 1599 ms` |
| 注册 | ✅ | `auth queued=true id=lite-b5be8fbacc6c tokenLen=43` |
| 审批 → 认证 | ✅ | 后端批准后 `AerialCast: authenticated by backend` |
| 心跳 | ✅ | 每 5 秒 `recv ack`，后端 `online=true` |
| Q：打开应用 | ✅ | 下发 `app/launch com.android.settings` → `mCurrentFocus=com.android.settings/.Settings` |
| 遥控按键 | ✅ | `key/down` 返回 `completed` |
| 应用清单上报 | ✅ | 后端事件中有 `app_inventory` |
| 截图回传 | ✅ | `snapshot` 返回 `completed`（走 adb `screencap` 回退路径） |
| DLNA 描述 | ✅ | `GET :8766/description.xml` → HTTP 200，含 `MediaRenderer` |

截图：`docs/screenshots/tv-4.4-home.jpg`（桌面网格与焦点框都在）。

## 这台固件的坑（已兼容）

1. **`screencap -p` 输出的是 JPEG，不是 PNG**：文件头 `FF D8 FF E0`。所以任何按扩展名解析的
   逻辑都会失败；`captureScreen` 用 `BitmapFactory` 解码字节流，正好不受影响。
2. **没有 `base64` 命令**（老 toolbox）：截图优先走 `screencap -p | base64` 的路径在 4.4 上会失败，
   自动回退到「写文件再读」的路径。
3. **没有 `which` 命令**：排查时不要用 `adb shell which xxx`，改用 `ls /system/bin/xxx`。
4. 焦点信息字段是 `mFocusedActivity`（不是新系统的 `mCurrentFocus` 唯一来源），
   `dumpsys window | grep mCurrentFocus` 在 4.4 上也可能命中。

## 默认桌面

4.4 没有 `cmd` 命令，`pm set-home-activity` 也不存在，只能用 Home 键触发的选择器：

```
adb shell input keyevent KEYCODE_HOME     # 弹出「选择主屏幕应用」
# 选中「星幕 Aerial」→ 点「始终」
```

之后按 HOME 直接进 `app.xingmu.aerial.cast/HomeActivity`，不再弹选择器。官方 Aerial Lite
仍在设备上（同一个选择器里列着），随时可以换回去。

## 开机自启

`adb reboot` 后不再依赖 adb：约 **75 秒**后后端显示该设备 `online=true`，心跳年龄 5 秒。
这台电视上星幕同时是默认桌面，所以「开机进星幕并上线」成立；`BOOT_COMPLETED` 接收器本身
是否被固件拦下，在已是默认桌面的情况下无法单独区分。

## P2P 直连：未通过

- 会话协商正常：`selected_mode=udp_jpeg`、`direct_transport_ready`，`device_notified=true`；
- 电视端确实收到了 `p2p_start`，并按会话 id 向会合点发了 hello，日志出现
  `P2P peer established`（拿到的对端是控制端地址）；
- 但控制端（PC 上的探测脚本）一直只收到 `{"type":"waiting"}`，收不到 `peer`，
  因此无法回 `ready`、也发不出按键。

已排除：会话 id 两侧一致、电视端 UDP 发送成功（`hello sent`）、同一脚本对 Android 15
设备可以正常配对。怀疑会合点的配对回执（`reverse peer`）在「设备与控制端几乎同时注册」
时丢掉了一侧，需要对 `p2p_rendezvous.py` 的 `store.register` 加日志进一步定位。
**结论：4.4 上的遥控目前走后端转发，P2P 直连未打通。**
