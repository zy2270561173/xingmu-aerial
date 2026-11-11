# 用宝塔面板把后端推到公网服务器

本文只讲**星幕后端**（FastAPI + SQLite）在宝塔面板上的部署。电视端和手机端不需要公网，它们只是连这个后端。

---

## 0. 先想清楚一件事：adb 依赖

后端有一半能力是**通过 adb 直接操作电视**完成的：

| 能力 | 是否依赖 adb |
|---|---|
| 按键注入（`key`） | 是 |
| 安装 APK（`install`） | 是 |
| 截图（`snapshot`） | 是 |
| 遥控/画面（手机直连） | 否（手机自己连） |
| 设备注册、心跳、账号、会员、支付 | 否 |

所以如果服务器在**公网机房**、电视在**家里**，那么这三项会失败（`adb connect` 连不到电视）。

三种可行部署方式，按推荐度排序：

1. **后端放在家里（NAS/小主机/树莓派），用 frp 或宝塔反代把 443 转到公网** —— adb 和电视同局域网，所有功能都可用；
2. **后端放公网，家里跑一个 frp 客户端把电视的 5555 端口映射上去** —— 功能可用，但**等于把电视 adb 暴露到公网**，必须限制来源 IP；
3. **后端放公网 + Tailscale 打通家庭局域网** —— 推荐做法，服务器像在家局域网里一样直接访问电视，按键/安装/截图全部可用且不暴露端口。详细步骤见 [ADB-TUNNEL.md](ADB-TUNNEL.md)；
4. **后端放公网，放弃 adb 三项** —— 注册/心跳/遥控/会员/支付可用，按键、安装、截图不可用。

> 下面按「后端在公网服务器」写，adb 那三项的处理方式在第 7 节单独说明。

---

## 1. 服务器与面板准备

- 系统：Ubuntu 22.04 / Debian 12 / CentOS 7+ 均可
- 宝塔面板已装好，并安装：**Nginx**、**Python 项目管理器**（软件商店里叫「Python项目管理器」或「Python项目」）
- 建议 2C2G 起步；SQLite 单机足够，不需要 MySQL

---

## 2. 拉代码

宝塔「文件」里进到 `/www/wwwroot`，或用终端：

```bash
cd /www/wwwroot
git clone https://github.com/zy2270561173/xingmu-aerial.git
cd xingmu-aerial
```

**国内服务器拉不动 GitHub 时**，改用镜像代理地址（只读拉取可用，推送仍走本地代理）：

```bash
git clone https://v4.gh-proxy.org/https://github.com/zy2270561173/xingmu-aerial.git xingmu-aerial
# 已克隆过的，直接改 origin：
git remote set-url origin https://v4.gh-proxy.org/https://github.com/zy2270561173/xingmu-aerial.git
git pull --ff-only
```

代理域名失效时，把 `v4.gh-proxy.org` 换成任意可用镜像（如 `ghproxy.net`、`gh-proxy.com`），后面的仓库路径保持不变。

后端在 `backend/`，Web 控制台的构建产物放 `web/`（见第 6 节）。

---

## 3. 建虚拟环境与依赖

```bash
cd /www/wwwroot/xingmu-aerial/backend
python3 -m venv .venv
.venv/bin/pip install -U pip
.venv/bin/pip install -r requirements.txt
```

如果 `requirements.txt` 里没有显式写 `uvicorn[standard]`，补一条：

```bash
.venv/bin/pip install "uvicorn[standard]"
```

---

## 4. 配置环境变量

在 `backend/` 下新建 `.env`（**不要提交到 git**）：

```ini
# 数据目录：数据库、APK、截图、JWT 密钥都在这里，务必备份
TVCAST_DATA_DIR=/www/wwwroot/xingmu-aerial-data

# 管理员账号（首次启动时创建）
TVCAST_ADMIN_USERNAME=admin
TVCAST_ADMIN_PASSWORD=换成你的强密码

# 公网访问地址：支付回调、APK 下载链接、播放器链接都用它拼
TVCAST_PUBLIC_BASE_URL=https://tv.example.com

# JWT 密钥：不填会自动生成并持久化到 data 目录
# TVCAST_JWT_SECRET=

# 允许跨域的前端地址（Web 控制台域名）
TVCAST_CORS_ORIGINS=https://tv.example.com,http://127.0.0.1:15173

# P2P UDP 会合服务
TVCAST_P2P_UDP_PORT=18099
TVCAST_P2P_PUBLIC_HOST=tv.example.com
TVCAST_P2P_PUBLIC_PORT=18099
```

> 注意 `TVCAST_PUBLIC_BASE_URL` 必须是**公网可访问的 HTTPS 地址**，否则：
> - 手机拿到的 APK 下载链接是内网地址，装不上；
> - 易支付回调打不进来，付了钱会员不生效。

---

## 5. 用宝塔跑起来

### 方式 A：Python 项目管理器（推荐）

在「Python项目」里新建：

| 字段 | 值 |
|---|---|
| 项目名称 | xingmu-backend |
| 路径 | /www/wwwroot/xingmu-aerial/backend |
| Python 版本 | 3.10+ |
| 框架 | uvicorn |
| 启动方式 | uvicorn |
| 启动文件/应用 | main:app |
| 端口 | 18000 |
| 运行用户 | www |
| 是否安装依赖 | 否（第 3 步已装） |

环境变量把第 4 节的键值逐条填进去（宝塔这里填的是生效值，不读 `.env` 也行）。

### 方式 B：systemd

`/etc/systemd/system/xingmu-backend.service`：

```ini
[Unit]
Description=Xingmu backend
After=network-online.target

[Service]
Type=simple
User=www
WorkingDirectory=/www/wwwroot/xingmu-aerial/backend
EnvironmentFile=/www/wwwroot/xingmu-aerial/backend/.env
Environment=PATH=/www/server/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ExecStart=/www/wwwroot/xingmu-aerial/backend/.venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 18000 --proxy-headers --forwarded-allow-ips=127.0.0.1
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now xingmu-backend
systemctl status xingmu-backend
```

> 只监听 `127.0.0.1`，外部一律走 Nginx。

---

## 6. Nginx 反代 + HTTPS

宝塔「网站」→ 添加站点 `tv.example.com`（不要 PHP），然后：

1. **SSL**：Let's Encrypt 一键申请，开启强制 HTTPS；
2. **反向代理**：目标 `http://127.0.0.1:18000`，发送域名 `$host`；
3. 反代配置文件里确认这两点（WebSocket 必需）：

```nginx
location / {
    proxy_pass http://127.0.0.1:18000;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;   # 设备 WS 长连接
    proxy_send_timeout 3600s;
    client_max_body_size 300m;  # APK 上传
}
```

没有 `Upgrade/Connection` 两行，设备会一直 `400` 或秒断，表现是「电视一直显示未连接」。

**Web 控制台**：构建产物已经打进后端（`backend/webui/`），由 FastAPI 直接提供，**不需要再配 Nginx 站点**：

- 访问 `https://tv.example.com/` 就是控制台；
- 任意前端路由（如 `/devices`、`/login`）都会回落到 `index.html`；
- `/assets/*` 是静态资源，`/api/*`、`/ws/*`、`/static/*` 不走前端回落。

重新构建前端后，把 `web/` 的 `dist/` 覆盖到 `backend/webui/` 即可，随后重启后端。

---

## 7. adb（按键 / 安装 / 截图）

服务器要能执行 `adb` 命令：

```bash
apt install -y android-tools-adb     # Debian/Ubuntu
# 或下载 platform-tools 解压后把目录加进 PATH
adb version
```

三个前提：

1. `adb` 在 systemd 服务的 `PATH` 里（上面的 service 已显式设置）；
2. 服务器能访问电视的 `<电视IP>:5555`；
3. 电视已开启「ADB 调试」并授权过这台服务器（`adb connect` 一次，电视上点允许）。

### 如果服务器不在电视所在局域网

家宽没有公网 IP 时，用 frp 把电视的 5555 映射到服务器的一个高位端口，然后：

- 设备记录里的 `adb_port` 改成映射后的端口；
- 或者在后端所在机器上 `adb connect 127.0.0.1:<映射端口>` 保持常连。

**安全提醒**：把 5555 暴露到公网等于把电视的 shell 交出去。必须：
- frp 只允许服务器 IP 连入；
- 或在服务器上用 `iptables` 限制来源；
- 用完就关。

如果不想承担这个风险，就选第 0 节的方案 1（后端放家里）。

---

## 8. 防火墙

宝塔「安全」里放行：

| 端口 | 协议 | 用途 |
|---|---|---|
| 80 / 443 | TCP | 网站 |
| 18099 | **UDP** | P2P 会合（TCP 放行没用） |
| 18000 | TCP | 仅在不用 Nginx 时开放 |

云厂商的安全组也要同步放行，尤其是 **UDP 18099**——这是 P2P 打洞最常见的漏配项。

---

## 9. 首次启动与冒烟测试

```bash
curl -s http://127.0.0.1:18000/api/health
# {"status":"ok","version":"...","connected_agents":0,...}
```

公网验证：

```bash
curl -s https://tv.example.com/api/health
```

然后按顺序验：

1. 浏览器打开 `https://tv.example.com/docs`，能看到接口文档（生产可关掉，见第 11 节）；
2. 用管理员账号 `POST /api/auth/login` 拿 token；
3. 电视端「连接设置 → 后端地址」填 `https://tv.example.com`，重启 App，后台「设备」里出现待审批设备；
4. 手机端登录同一账号，能看到设备并遥控；
5. 上传一个 APK，确认返回的 `url` 是 `https://tv.example.com/static/apk/...`；
6. 后台设置里填易支付商户号 → 点「验证商户配置」→ 通过后开启在线购买；
7. 电视上点套餐 → 出二维码 → 手机扫码付 1 元 → 电视点「刷新支付状态」看会员是否生效。

---

## 10. 数据与备份

所有状态都在 `TVCAST_DATA_DIR`：

```
tvcast.db          SQLite 数据库（用户、设备、订单、会员、兑换码）
apk/               上传的 APK 与截图
.jwt-secret        JWT 密钥（删了所有人重新登录）
```

- 宝塔「计划任务」加一条：每天 `mysqldump` 换成
  `cp /www/wwwroot/xingmu-aerial-data/tvcast.db /www/backup/tvcast-$(date +%F).db`；
- `apk/` 目录随流量增长，建议每月清理 30 天前的文件；
- 迁移服务器时，整个 `data` 目录拷走即可，不需要额外导入导出。

---

## 11. 上线前的安全清单

- [ ] `TVCAST_ADMIN_PASSWORD` 改成强密码（默认 `admin123` 极其危险）
- [ ] `TVCAST_PUBLIC_BASE_URL` 是 HTTPS，且 Nginx 强制跳转 HTTP→HTTPS
- [ ] 生产环境关闭接口文档：启动参数加 `--no-access-log`，或在 `main.py` 里 `FastAPI(docs_url=None, redoc_url=None)`（需要改代码，可选）
- [ ] CORS 只填自己的域名，不要 `*`
- [ ] 易支付的 `epay_key` 只在后台设置里填，不要写进代码或 `git`
- [ ] 数据库目录不给 Web 可执行权限（`chmod 750`，属主 www）
- [ ] 关闭宝塔面板的公网访问，或改端口 + 开两步验证
- [ ] 定期看 `/www/wwwlogs/` 下的 Nginx 日志，异常 IP 拉黑

---

## 12. 常见故障对照

| 现象 | 原因 | 处理 |
|---|---|---|
| 电视一直「未连接」 | Nginx 没配 WebSocket 升级头 | 加 `Upgrade`/`Connection` 两行并 reload |
| 手机拿到的 APK 链接是 `127.0.0.1` | `TVCAST_PUBLIC_BASE_URL` 没设 | 设成公网域名后重启 |
| 支付成功但会员没变 | 易支付回调地址不可达 | 检查 `https://域名/api/vip/payments/epay/notify` 是否 200 |
| 按键/安装/截图全失败 | 服务器连不到电视 5555 | 见第 7 节；或改为家里部署 |
| P2P 一直 relay | UDP 18099 没放行 | 云安全组 + 宝塔防火墙都要开 UDP |
| 上传 APK 报 413 | Nginx 体积限制 | `client_max_body_size 300m` |
| 启动报 `ModuleNotFoundError` | 依赖装在别的解释器 | 确认用 `.venv/bin/python -m uvicorn` |

---

## 13. 升级流程

```bash
cd /www/wwwroot/xingmu-aerial
git pull
cd backend && .venv/bin/pip install -r requirements.txt
systemctl restart xingmu-backend      # 或宝塔里点重启
curl -s https://tv.example.com/api/health
```

数据库结构变更由后端启动时自动迁移（`db.py` 里的 `ALTER TABLE` 列表），不需要手工执行 SQL。
