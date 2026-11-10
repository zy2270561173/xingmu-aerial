# 构建与验证

四端各自独立构建；CI（`.github/workflows/ci.yml`）在每次 push / PR 上跑同样的四条命令。

## 电视端（`tv/`，`app.xingmu.aerial`）

要求：JDK 17、Android SDK（compileSdk 36）。

```bash
cd tv
./gradlew :lite:assembleDebug                  # 无签名密钥也能编译
./gradlew :lite:assembleRelease                # 发布包，需要 local/signing.properties + keystore
./gradlew :lite:lintRelease                    # API 级别检查（0 error 为通过）
```

- 发布签名**不进仓库**：`local/`、`*.jks`、`local.properties` 都在 `.gitignore` 里。
- 首次引导的后端地址：`-PcastBackendUrl=http://<host>:18000`。
- 旁路测试包（与正式包并存，便于在旧电视上试）：`-PcastTestApk=true` → `app.xingmu.aerial.cast`。
- 共享源码（GlyphPaths / TileRecipes / Cities / AppNames / CustomIcons …）在构建时从 `app/`
  复制到 Lite 包并改写包名，见 `lite/build.gradle.kts` 的 `copySources`。

## 手机端（`phone/`，`app.xingmu.remote`）

```bash
cd phone
./gradlew :app:assembleDebug
```

debug 构建默认后端为 `http://192.168.31.111:18000`（`BuildConfig.DEFAULT_BACKEND_URL`），
真机调试时改成自己局域网的地址即可，也可以在登录页连点「开发者」10 次改后端地址。

## 后端（`backend/`）

```bash
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt      # Linux/macOS: .venv/bin/pip
TVCAST_ADMIN_PASSWORD=<password> .venv/Scripts/python -m uvicorn main:app --host 0.0.0.0 --port 18000
.venv/Scripts/python -m pytest tests -q
```

环境变量（常用）：

| 变量 | 作用 |
|---|---|
| `TVCAST_DATA_DIR` | SQLite 与上传文件目录 |
| `TVCAST_ADMIN_PASSWORD` | 首次初始化的管理员密码 |
| `TVCAST_P2P_UDP_PORT` / `TVCAST_P2P_PUBLIC_HOST` / `TVCAST_P2P_PUBLIC_PORT` | P2P 会合端点 |
| `TVCAST_TRUSTED_PROXIES` | 反向代理后取真实 IP 用 |

## Web 控制台（`web/`）

```bash
cd web
npm ci
npm run build      # vue-tsc + vite build
npm run dev        # 本地开发
```

## 本机验证记录（2026-11-10）

| 项 | 结果 |
|---|---|
| 后端测试（仓库内 `backend/tests`） | `53 passed` |
| 电视端 `assembleDebug`（无 `local/` 签名目录） | BUILD SUCCESSFUL |
| 电视端 `assembleRelease` | `app.xingmu.aerial` / `星幕 Aerial` / versionCode 28 |
| 手机端 `assembleDebug` | `app.xingmu.remote` / `星幕遥控` / versionCode 28 |
| 电视端真机（Android 15，`192.168.31.112`） | 注册→审批→在线，命令、遥控、P2P、DLNA、应用清单上报、关于本机均通过 |
| 手机端真机（Android 16，vivo V2302A） | 登录→设备→遥控→投屏→应用→我的，六页截图确认 |

未验证：**Android 4.4 实机**（只有 Lint 级 API 检查与静态加固）。
