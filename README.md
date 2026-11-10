# 星幕 · Xingmu

Aerial 风格的电视桌面 **星幕 Aerial**，配一套手机遥控 **星幕遥控**、后端与 Web 控制台。
四端同仓：电视、手机、后端、前端。

## 目录

| 目录 | 内容 | 包名 / 入口 |
|---|---|---|
| `tv/` | 电视桌面（Aerial 重制 + 内建投屏 Agent）：桌面、控制中心、投屏设置、关于本机、DLNA 接收、P2P 直连 | `app.xingmu.aerial` |
| `phone/` | 手机遥控：设备/遥控/投屏/应用/我的，Bing 每日一图背景 | `app.xingmu.remote` |
| `backend/` | FastAPI + SQLite：设备注册、WebSocket 控制通道、P2P 会话、DLNA 无关的服务端能力 | `main:app` |
| `web/` | Web 控制台（设备、审批、指令、日志） | — |
| `deploy/` | 部署脚本与 compose 片段 | — |
| `docs/` | 电视端投屏接入说明、手机端 UI 规范 | — |

## 构建

```bash
# 电视端（Aerial 重制，Android 4.x / 5+）
cd tv && ./gradlew :lite:assembleRelease

# 手机端
cd phone && ./gradlew :app:assembleDebug

# 后端
cd backend && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
.venv/Scripts/python -m uvicorn main:app --host 0.0.0.0 --port 18000

# Web 控制台
cd web && npm install && npm run dev
```

电视端的后端地址可用 `-PcastBackendUrl=http://<host>:18000` 编进首次引导。

## 署名与许可

本项目是重制版，建立在两个开源项目之上：

- **Aerial TV** — 原作者 [pengchujin](https://github.com/pengchujin)，[github.com/pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)，
  MIT 许可。电视端的桌面、控制中心、动效与图标体系来自该项目，本项目在其 Lite 模块基础上
  合并了投屏 Agent 并重做了界面与包名。原始 `LICENSE` 见仓库根目录。
- **星投屏（tv-cast-ecosystem）** — 手机端、后端与 Web 控制台的原始实现与协议（设备注册、
  WebSocket 命令通道、P2P 会话、DLNA 控制）。

图标库中部分磁贴图来自 [le5inioo](https://github.com/le5inioo)（见 aerial-tv #706），
字体 Inter 使用 SIL Open Font License（见 `tv/FONT-LICENSE-OFL.txt`）。

**星幕重制版**：界面重制、四端合并、包名替换（`app.xingmu.aerial` / `app.xingmu.remote`）
与本文档由本项目维护者完成。

## 状态

- 电视端：注册/心跳、命令通道、遥控、P2P 直连、DLNA、应用清单上报、关于本机 —— 已在
  Android 15 真机验证；Android 4.4 尚未实机验证。
- 手机端：六页排版与主题改造完成，已在 Android 16 真机验证。
- 后端：P2P 会合、审批、命令队列等已修复若干安全问题，53 项测试通过。
