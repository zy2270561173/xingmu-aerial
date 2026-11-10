# 星幕 Aerial（电视端）

Aerial 风格的 Android TV 桌面，并把「星投屏」的电视端 Agent 合并了进来：
一个 APK 同时是桌面和投屏接收端（注册/心跳、遥控、P2P 直连、DLNA、画面回传、
应用清单上报、关于本机）。

包名 `app.xingmu.aerial`，旁路测试包 `app.xingmu.aerial.cast`。

## 来源与署名

- **派生自 [Aerial TV](https://github.com/pengchujin/aerial-tv)**（作者 [pengchujin](https://github.com/pengchujin)，MIT 许可）。
  桌面、控制中心、动效、图标体系与 Lite 模块结构来自上游；上游原始说明见
  [`README.upstream.md`](README.upstream.md) / [`README.upstream.en.md`](README.upstream.en.md)。
- 本目录在其 Lite 模块基础上**合并了投屏 Agent、重做界面与包名**，属于重制版；
  重制部分与「星幕」品牌由本仓库维护者完成。
- 图标库中部分磁贴图来自 [le5inioo](https://github.com/le5inioo)（上游 #706）；
  字体 Inter 使用 SIL Open Font License，见 [`FONT-LICENSE-OFL.txt`](FONT-LICENSE-OFL.txt)。

许可证沿用上游 MIT，见仓库根目录 `LICENSE`。

## 构建

```bash
./gradlew :lite:assembleDebug                 # 无签名也能编译
./gradlew :lite:assembleRelease               # 需要 local/signing.properties + keystore（不进仓库）
./gradlew :lite:lintRelease                   # 0 error 为通过
```

后端地址首次引导：`-PcastBackendUrl=http://<host>:18000`。

## 模块说明

| 路径 | 作用 |
|---|---|
| `lite/` | 电视端全部代码（桌面 + `cast/` 投屏 Agent） |
| `lite/src/main/java/app/xingmu/aerial/cast/` | 注册/心跳、WebSocket 命令、P2P UDP 通道、DLNA、截图、按键 |
| `docs/CAST.md`（仓库 `docs/`） | 投屏接入说明、后端契约、排障记录 |
| `app/` | 上游完整版 Aerial 的源码（本仓库仅 Lite 参与构建），共享源码由 `copySources` 生成 |

## 已验证

| 平台 | 范围 | 结果 |
|---|---|---|
| Android 15 真机（`192.168.31.112`） | 注册→审批→在线、命令、遥控、P2P 直连、DLNA、应用清单上报、关于本机 | 全部通过 |
| **Android 4.4.2 真机**（Hi3798MV200 / M08，`192.168.31.242`） | 安装、桌面渲染、注册、认证、心跳、打开应用、按键、应用清单、截图、DLNA 描述 | 全部通过，详见 [`docs/VERIFY-4.4.md`](../docs/VERIFY-4.4.md) |

4.4 的坑（已兼容）：该固件 `screencap -p` 实际输出 **JPEG**、且没有 `base64` / `which` 命令，
截图自动回退到写文件路径。

仍未验证：开机自启、设为默认桌面（会替换用户现有桌面，需明确同意）、4.4 上的 P2P 会合。
