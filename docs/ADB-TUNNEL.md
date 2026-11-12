# 公网机房部署：让后端能操作电视（adb 打通）

> **电视端 1.34 / 后端 0.14.14 起已内置 `/ws/tunnel` 反向隧道**：电视自己连出来，后端在
> `127.0.0.1` 上开一个端口并自动 `adb connect`；服务器没有 adb 二进制时还会用仓库自带的
> 纯 Python ADB 兜底，公网机房不需要 Tailscale、frp 或 iptables。
> 先看 [DEPLOY-BT.md](DEPLOY-BT.md) 第 7 节「内置隧道」；本文保留给 1.33 及以前的电视，
> 或需要自己控制隧道实现的场景。

> **本文是备选方案。** 如果家宽有公网 IP，优先用 [DEPLOY-HOME-PUBLICIP.md](DEPLOY-HOME-PUBLICIP.md)：后端放家里、路由器映射 443，adb 天然可用，不需要任何隧道。

后端放在公网机房时，`key`（按键）、`install`（安装）、`snapshot`（截图）这三项**默认不可用**，因为服务器连不到电视的 5555 端口。

本文给两种打通方式。推荐 Tailscale，理由在最后。

---

## 一、先确认哪些功能不受影响

即使完全不打通，下列功能正常：

- 设备注册 / 心跳 / 在线状态
- 手机遥控（手机自己连电视，不经服务器）
- 投屏（手机 → 电视，局域网内）
- 账号、会员、套餐、支付、兑换码
- Web 控制台的全部管理功能

只有**服务器代劳**的三项需要 adb：按键注入、APK 安装、截图。

---

## 二、方案 A：Tailscale（推荐）

优点：不暴露任何端口到公网、服务器能直接用**电视原来的内网 IP**、配置一次长期有效。

### 1. 家里那台机器做子网路由

找一台常开的机器（NAS、软路由、小主机、甚至一台旧笔记本），装 Tailscale：

```bash
# Linux / NAS
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --advertise-routes=192.168.31.0/24 --accept-routes
```

```powershell
# Windows
winget install Tailscale.Tailscale
tailscale up --advertise-routes=192.168.31.0/24
```

`192.168.31.0/24` 换成你家电视所在的网段。

### 2. 在 Tailscale 后台批准子网路由

登录 https://login.tailscale.com/admin/machines → 找到这台机器 → 「…」→ **Edit route settings** → 勾选 `192.168.31.0/24` → 保存。

### 3. 服务器装 Tailscale 并接入

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --accept-routes
tailscale status          # 能看到家里那台机器
```

### 4. 验证能通电视

```bash
ping -c 2 192.168.31.245          # ICMP 可能被电视忽略，ping 不通不代表失败
nc -vz 192.168.31.245 5555        # 这个必须通
```

### 5. 用后端自己的身份连一次 adb

```bash
adb connect 192.168.31.245:5555
adb devices                        # 应显示 device
adb shell input keyevent 20        # 电视上焦点应移动
```

第一次连会在电视上弹「允许 USB 调试吗」，勾选「一律允许」。

### 6. 后端无需改任何配置

因为电视上报的 `lan_ip` 就是 `192.168.31.245`，服务器现在能路由到它，所以按键、安装、截图直接可用。

### 7. 收紧访问（重要）

Tailscale 后台 → **Access Controls**，只允许 VPS 访问电视的 5555：

```json
{
  "acls": [
    { "action": "accept", "src": ["tag:vps"], "dst": ["tag:home:5555"] },
    { "action": "accept", "src": ["tag:vps"], "dst": ["tag:home:443,80"] }
  ],
  "tagOwners": { "tag:vps": ["autogroup:admin"], "tag:home": ["autogroup:admin"] }
}
```

给两台机器打上对应 tag，其余流量一律拒绝。

---

## 三、方案 B：frp（没有 Tailscale 时的替代）

### 服务器端（公网机房）

`/etc/frp/frps.toml`：

```toml
bindPort = 7000
auth.method = "token"
auth.token = "换成一串随机长字符串"

# 面板（可选）
webServer.addr = "127.0.0.1"
webServer.port = 7500
```

```bash
systemctl enable --now frps
```

防火墙只放行 7000，**且限制只能你家宽带的出口 IP 访问**（家宽 IP 会变，可用 DDNS 或定期更新）。

### 家里端

`frpc.toml`：

```toml
serverAddr = "你的服务器IP"
serverPort = 7000
auth.method = "token"
auth.token = "同一串随机长字符串"

[[proxies]]
name = "tv-adb"
type = "tcp"
localIP = "192.168.31.245"
localPort = 5555
remotePort = 15555
```

启动后，服务器上 `127.0.0.1:15555` 就是电视的 adb。

### 服务器上保持常连

```bash
adb connect 127.0.0.1:15555
adb devices
```

### ⚠️ 这个方案有个坑

后端拼 adb 序列号用的是「设备上报的 `lan_ip` + `adb_port`」，也就是 `192.168.31.245:5555`——服务器走 frp 时实际要连 `127.0.0.1:15555`，两者对不上。

两个办法：

1. **改设备记录**（推荐，一次性）：

```sql
-- 在 data 目录的 tvcast.db 上执行
UPDATE devices SET lan_ip = '127.0.0.1', adb_port = 15555
WHERE device_id = 'lite-xxxx';
```

  注意：电视下次心跳会重新上报 `lan_ip`，所以更好的做法是——

2. **在服务器上用 iptables 把 `192.168.31.245:5555` 重定向到 `127.0.0.1:15555`**：

```bash
iptables -t nat -A OUTPUT -d 192.168.31.245 -p tcp --dport 5555 \
  -j DNAT --to-destination 127.0.0.1:15555
```

   这样电视上报的地址照旧可用，后端不用改。

---

## 四、两种方案对比

| 维度 | Tailscale | frp |
|---|---|---|
| 是否暴露端口到公网 | 否 | 是（7000 + 15555） |
| 是否需要改设备记录 | 否 | 需要（或用 iptables 绕开） |
| 家宽 IP 变化是否影响 | 不影响 | 影响（服务器端要更新来源限制） |
| 配置复杂度 | 中（装客户端 + 后台批路由） | 中（两端配置文件 + 防火墙） |
| 稳定性 | 高（自动重连、NAT 穿透） | 中（依赖 frps 在线） |
| 合规风险 | 低 | 中（等于把电视 shell 挂公网） |

**结论：公网机房部署，用 Tailscale。** 它让服务器"看起来就在你家局域网里"，后端一行配置都不用改。

---

## 五、验证清单

打通后，在服务器上依次跑：

```bash
adb devices                                   # 有 device
adb -s 192.168.31.245:5555 shell input keyevent 20
```

然后从后端验证三项：

```bash
TOKEN=$(curl -s -X POST https://tv.example.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"你的密码"}' | jq -r .access_token)

# 按键
curl -s -X POST https://tv.example.com/api/device/<设备ID>/commands \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"key","payload":{"key":"down"}}'

# 截图
curl -s -X POST https://tv.example.com/api/device/<设备ID>/commands \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"snapshot","payload":{}}'
```

- 按键：数据库里该指令 `status=completed`
- 截图：`result_json` 里带 `url`，浏览器打开是一张 PNG

安装则用后台或手机上传一个 APK 后下发 `install`，看 `status=completed` 且电视端版本更新。
