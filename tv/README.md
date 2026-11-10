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

## 已验证 / 未验证

- Android 15 真机（`192.168.31.112`）：注册→审批→在线、命令、遥控、P2P、DLNA、
  应用清单上报、关于本机，全部通过。
- **Android 4.4 实机未验证**，目前只有 Lint 级 API 检查与静态加固（TLS 1.2、API 16 守卫、
  截图 base64 回退）。
