> **来源：自制项目（星投屏）**
> 本目录代码由仓库所有者自行开发，此前未公开发布；本仓库是首次公开版本。
# 星投屏控制中心

FastAPI + SQLite 控制面。它处理 Agent 认证、状态、控制命令、APK、P2P 协商信令，以及可选的 DPlayer 媒体字节中继。

## 启动

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:TVCAST_ADMIN_PASSWORD='请设置强密码'
.\.venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 18000
```

- API 文档：`http://127.0.0.1:18000/docs`
- 健康检查：`http://127.0.0.1:18000/api/health`
- 默认本地开发账号：`admin / admin123`。公网部署前必须通过环境变量修改密码和 JWT 密钥。

## WebSocket 认证

凭据不放 URL。连接建立后 10 秒内发送首帧：

```json
{"type":"auth","device_id":"tv_xxx","token":"设备令牌"}
```

控制端连接 `/ws/control/{device_id}` 后发送：

```json
{"type":"auth","token":"用户 JWT"}
```

Agent 指令具有 `pending / delivered / started / completed / failed` 五个阶段。断线时仅重新排队尚未开始执行的指令；普通投屏媒体不经过本服务，只有显式选择后端 DPlayer 时才启用字节中继。

电视页面预览通过瞬时 WebSocket 消息传递。多个手机或 Web 页面同时查看同一电视时，请求会按设备合并，同一帧广播给所有控制端；图片不会写入事件历史数据库。

## 后端 DPlayer

- 客户端通过已认证的 `POST /api/player/sessions` 提交 HTTP/HTTPS 媒体地址，后端返回 30 分钟有效的随机播放票据。
- 电视只打开 `/player/{ticket}`，原始媒体地址不会进入电视打开的 URL 或播放器 HTML；返回给电视的是相对 `player_path`，由电视按照自身保存的后端地址解析，避免把服务器的 `127.0.0.1` 错当成电视地址。
- 已提供用户要求的兼容入口 `/palyer?url=视频链接`，也可使用拼写正确的 `/player?url=视频链接`。服务会验证媒体地址、创建短时票据并 `307` 跳转到 `/player/{ticket}`；调用方应对视频链接进行 URL 编码。`/player?ticket=` 和 `/palyer?ticket=` 仍保留兼容。
- 页面使用本地 DPlayer 1.25.1 和 hls.js 0.14.17 ES5 资源，兼容旧 Android WebView，不依赖公网 CDN。
- `/player/{ticket}/media` 支持 Range 透传；每次跳转都会重新解析目标并拒绝私网、环回、链路本地和非公网地址，防止借中继访问内网。
- 当前只中继媒体字节，`cloud_transcode=false`。FFmpeg 硬件/软件转码和第三方平台解析需要单独部署受控转码工作进程，不能由 DPlayer 或云服务器自动获得“云端硬解”能力。

## 扫码绑定

- `POST /api/device/register` 会在设备未绑定且待审批时返回可复用的 `pairing` 扫码凭证；Agent 重连不会刷新二维码。`POST /api/pairing/device` 仅用于旧版客户端兼容。
- `GET /api/device-approvals` 与 `POST /api/device-approvals/{device_id}/approve|reject` 是扫码后独立的设备审批接口；旧 `/api/approvals` 路径继续兼容滚动升级。
- 手机 App 登录或注册后扫描 `starlight-tvcast://pair?...`，再以当前用户身份调用 `POST /api/pairing/{code}/claim`。
- 浏览器打开 `/pair/{code}` 后可登录或注册、绑定电视，并上传 APK 下发安装。票据有效期 10 分钟，只保存 SHA-256 摘要，使用一次或电视刷新后失效。
- 绑定只授予该账号经后端访问这台设备的权限；公网直连仍取决于双方 UDP NAT 映射和防火墙，不能由扫码本身自动保证。
- 电视同时提供 `http://电视局域网地址:8765/?token=一次性本地令牌`。后端离线时只显示这张本地配置二维码，仍可修改后端/账号/ADB 配置并直接上传 APK 安装；后端恢复心跳后才重新生成在线绑定二维码。

## P2P 边界

远程控制沿用现有 JPEG 快照流，不引入新的实时视频协议。手机先通过已登录账号向后端申请短时、分角色的会话令牌；后端通过电视 Agent WebSocket 下发认证材料，双方再向 UDP 会合端口登记公网端点并尝试直接通信。直接包使用 HMAC-SHA256 验签，JPEG 按小包分片并由手机重组；后端只负责认证和会合，不转发直连画面或按键。

公网部署需要同时开放 API/WebSocket 的 TCP 端口和会合服务的 UDP 端口（默认 `18000/tcp`、`18001/udp`），并设置 `TVCAST_PUBLIC_BASE_URL`、`TVCAST_P2P_PUBLIC_HOST` 和 `TVCAST_P2P_PUBLIC_PORT`。UDP 打洞受双方 NAT、运营商 CGNAT 和防火墙影响；无法直连时客户端自动继续使用原有 WebSocket JPEG/控制通道。因此“公网可用”需要在实际两张外网下验收，不能仅凭局域网结果保证所有网络都能直连。

## 会员销售

- `sales_enabled` 为总开关，默认关闭；关闭时保留已开通会员状态，但不开放购买入口。
- 后台可维护套餐、生成兑换码、审核订单，并直接调整用户会员到期时间和五种徽章样式。
- 手机端可查看套餐和订单、提交开通申请、手动输入或扫码兑换。兑换码只在创建结果中返回一次明文，数据库只保存 SHA-256 摘要。
