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

## 来源：哪些是自制的，哪些是派生的

| 部分 | 来源 | 说明 |
|---|---|---|
| `phone/` `backend/` `web/` `deploy/` | **自制项目（星投屏）** | 由仓库所有者自行开发，此前**未公开发布**；本仓库是它的首次公开版本。手机端包名改为 `app.xingmu.remote`、应用名改为「星幕遥控」 |
| `tv/` | **派生自 Aerial TV** | 上游作者 [pengchujin](https://github.com/pengchujin)，[aerial-tv](https://github.com/pengchujin/aerial-tv)，MIT 许可。本仓库在其 Lite 模块上合并投屏 Agent、重做界面与包名（`app.xingmu.aerial`），属重制版；上游原说明保留为 `tv/README.upstream.md` |

`docs/` 下的接入说明与构建文档由本仓库维护者撰写。

## 许可

- `tv/`：沿用上游 **MIT**，原 `LICENSE` 见仓库根目录；上游版权声明保留。
- `phone/` `backend/` `web/` `deploy/`：自制部分同样以 **MIT** 发布，除非作者后续另行说明。

## 第三方

- 图标库部分磁贴图来自 [le5inioo](https://github.com/le5inioo)（上游 #706）；
- 字体 Inter 使用 SIL Open Font License（`tv/FONT-LICENSE-OFL.txt`）；
- 手机端依赖 Coil / OkHttp / ZXing（Apache-2.0），后端依赖 FastAPI / Starlette（MIT/BSD）。

**星幕重制版**：界面重制、四端合并、包名替换与文档由本项目维护者完成。
