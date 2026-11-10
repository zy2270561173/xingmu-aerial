# 星投屏 Web 控制台

Vue 3 + TypeScript + Vite + Pinia + `animal-island-ui-vue`。页面直接使用后端 REST 与首帧认证 WebSocket。

```powershell
npm install
npm run dev
```

开发地址：`http://127.0.0.1:15173`。Vite 会把 `/api`、`/ws`、`/static`、播放器和配网页代理到 `127.0.0.1:18000`。可通过 `VITE_DEV_PORT`、`VITE_DEV_BACKEND` 覆盖本地端口。
