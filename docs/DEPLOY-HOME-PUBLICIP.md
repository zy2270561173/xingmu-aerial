# 公网 IP + 家庭后端部署（推荐拓扑）

前提：家宽有**公网 IP**（IPv4 或 IPv6 均可），后端跑在家里的一台常开机器上，路由器做端口映射。

这个拓扑不需要 Tailscale、不需要 frp、不需要把电视的 adb 暴露出去。

---

## 1. 拓扑

```
                    ┌──────────── 家庭局域网 192.168.31.0/24 ────────────┐
公网 IP             │                                                    │
  │  80/443 ────────►│  Nginx (家里机器) ──► 后端 uvicorn 127.0.0.1:18000  │
  │                 │        │                                            │
  │                 │        └── adb ──► 电视 192.168.31.245:5555         │
  │  UDP 18099 ────►│  后端 P2P 会合服务 (同一个进程)                     │
  │                 └────────────────────────────────────────────────────┘
  │
  ├─ 手机（在外）──► 公网 IP:443 ──► 后端 ──► 转发指令给电视
  └─ 手机（在家）──► 直接局域网连电视（不经后端，延迟最低）
```

关键点：**后端和电视在同一个局域网**，所以按键、安装、截图这三项 adb 能力天然可用。

---

## 2. 路由器端口映射

| 外网端口 | 协议 | 内网目标 | 说明 |
|---|---|---|---|
| 80 | TCP | 家里机器:80 | Let's Encrypt 证书验证 + HTTP 跳 HTTPS |
| 443 | TCP | 家里机器:443 | 网站主入口 |
| 18099 | **UDP** | 家里机器:18099 | P2P 会合，**必须 UDP** |

**不要映射的端口**：

| 端口 | 原因 |
|---|---|
| 5555（电视 adb） | 等于把电视 shell 挂公网，后端自己内网连即可 |
| 18000（uvicorn 直连端口） | 一律走 Nginx，不直接暴露 |
| 宝塔面板端口 | 只在内网访问，或改端口 + 开两步验证 |

---

## 3. 域名与 HTTPS

1. 域名 A 记录指向你家公网 IP（DDNS 也行，家宽 IP 可能会变）；
2. 宝塔「网站」→ 站点 → SSL → Let's Encrypt：
   - 80 端口可用 → 选「文件验证」；
   - 80 被封 / 被运营商拦 → 选「DNS 验证」，填 DNS API（阿里云、Cloudflare 等都支持）；
3. 开启「强制 HTTPS」。

> 支付回调要求 HTTPS，证书必须是**受信任 CA** 签发的，自签名证书易支付会拒绝。

---

## 4. 后端配置要点

`.env` 里这几项和公网 IP 直接相关：

```ini
# 公网访问地址，支付回调 / APK 下载 / 播放器链接都用它拼
TVCAST_PUBLIC_BASE_URL=https://tv.example.com

# P2P 会合：让外网手机能打洞到你家电视
TVCAST_P2P_UDP_PORT=18099
TVCAST_P2P_PUBLIC_HOST=tv.example.com
TVCAST_P2P_PUBLIC_PORT=18099

# 管理员密码务必改
TVCAST_ADMIN_PASSWORD=强密码
```

Nginx 反代必须带 WebSocket 头（否则电视连不上）：

```nginx
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 3600s;
client_max_body_size 300m;
```

---

## 5. ⚠️ NAT 回环（hairpin）问题

家里的设备（电视、手机）去访问 `https://tv.example.com` 时，流量要「出去再回来」。**部分路由器不支持这种回环**，表现为：

- 外网访问正常；
- 家里电视/手机连不上后端（一直「未连接」或请求超时）。

两种处理：

**方案一（推荐）：内网设备用内网地址**

电视端「连接设置 → 后端地址」填 `http://192.168.31.111:18000`（家里那台机器的内网 IP）。

手机在家也用这个地址（App 支持改后端地址），出门前切回域名，或者干脆一直用域名并在路由器上开启 NAT 回环。

**方案二：路由器开启 NAT Loopback / NAT 回流**

不同品牌叫法不同（华硕「NAT Loopback」、OpenWrt「reflection」、爱快「回流」）。开了之后家里设备也能用域名访问。

---

## 6. 直连与转发的判定

代码里已有，不需要配置：

### 遥控（手机 → 电视）

```
AUTO 模式（默认）
  ├─ 先试局域网直连（lan_ip:8080 / DLNA）
  │    成功 → 提示「局域网直连遥控成功」
  └─ 失败 → 后端命令通道转发
```

界面「遥控」页顶部的状态行会显示当前走的哪条：
- `局域网直连` / `P2P 直连 · 已连接` → 直连
- 只显示「已连接」→ 走后端转发

### 视频/画面

`POST /api/device/{id}/p2p/session` 返回 `selected_mode`：

| 值 | 含义 |
|---|---|
| `udp_jpeg` | UDP 打洞成功，P2P 直连 |
| `relay` | 回落后端转发 |
| `unavailable` | 既不能直连也没开转发 |

后端 `/api/health` 的 `p2p_sessions` 能看到当前会话数与已建立数。

---

## 7. 上线后验证顺序

```bash
# 1. 公网可达
curl -s https://tv.example.com/api/health

# 2. 家里机器能连电视 adb
adb connect 192.168.31.245:5555 && adb devices

# 3. 电视上线
#    电视 App → 连接设置 → 后端地址填内网地址 → 重启 App
#    后台「设备」出现待审批设备 → 批准

# 4. 手机在外网（关 Wi-Fi 用流量）登录，遥控能通 = 转发链路 OK

# 5. 手机在家（连 Wi-Fi）遥控，提示「局域网直连」= 直连链路 OK

# 6. 按键指令
curl -X POST https://tv.example.com/api/device/<设备ID>/commands \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"key","payload":{"key":"down"}}'
# 数据库里 status 应为 completed

# 7. 截图
curl -X POST https://tv.example.com/api/device/<设备ID>/commands \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"snapshot","payload":{}}'
# result_json 里应有 url，浏览器能打开 PNG

# 8. 支付
#    后台填易支付商户号 → 验证通过 → 电视点套餐出二维码 → 手机扫码付 1 元
```

---

## 8. 安全清单

- [ ] 只映射 80 / 443 / UDP 18099，其余全关
- [ ] 电视 adb 5555 不出内网
- [ ] 宝塔面板不对公网开放，或改端口 + 两步验证
- [ ] `TVCAST_ADMIN_PASSWORD` 强密码
- [ ] CORS 只填自己的域名
- [ ] 易支付密钥只在后台设置里填
- [ ] 定期备份 `TVCAST_DATA_DIR`（数据库 + APK + 截图）
- [ ] 域名开启 DNSSEC（可选，防劫持）

---

## 9. 和机房方案的区别

| 项 | 家庭后端 + 公网 IP（本文） | 机房 + Tailscale（备选） |
|---|---|---|
| adb 三项能力 | 天然可用 | 需要 Tailscale/端口映射 |
| 依赖 | 家里机器常开 + 公网 IP | 机房机器 + 隧道 |
| 断网风险 | 家里断电/断网即全断 | 机房稳定 |
| 电视延迟 | 最低（同局域网） | 取决于隧道 |
| 安全面 | 只暴露 443/80/18099 | 只暴露隧道端口 |

家用常开设备建议接 UPS，避免断电导致设备离线、订单/会员状态无法查询。
