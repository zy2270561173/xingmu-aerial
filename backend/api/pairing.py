from __future__ import annotations

import json
import time

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import HTMLResponse

from auth import verify_device_token, verify_password
from dependencies import get_current_user
from models import DeviceAccountLoginRequest, DevicePairingRequest
from pairing_service import issue_pending_pairing, pairing_digest, public_base
from serializers import device_public, user_public


router = APIRouter(tags=["pairing"])
def _public_base(request: Request) -> str:
    return public_base(request)


def _find_pairing(request: Request, code: str) -> dict | None:
    return request.app.state.db.fetch_one(
        """
        SELECT pairing_sessions.*, devices.device_id, devices.name, devices.model,
               devices.status AS device_status, devices.owner_user_id,
               users.username AS claimed_username, users.nickname AS claimed_nickname,
               users.phone AS claimed_phone
               , users.vip_expires_at AS claimed_vip_expires_at
               , users.vip_tag_style AS claimed_vip_tag_style
               , users.vip_plan_name AS claimed_vip_plan_name
               , users.vip_device_limit AS claimed_vip_device_limit
               , users.vip_benefits_json AS claimed_vip_benefits_json
               , users.vip_entitlements_json AS claimed_vip_entitlements_json
               , users.vip_badge_text AS claimed_vip_badge_text
               , users.vip_accent_color AS claimed_vip_accent_color
        FROM pairing_sessions
        JOIN devices ON devices.id = pairing_sessions.device_pk
        LEFT JOIN users ON users.id = pairing_sessions.claimed_by
        WHERE pairing_sessions.code_hash = ?
        """,
        (pairing_digest(code),),
    )


def _authenticated_device(request: Request, payload: DevicePairingRequest) -> dict:
    device = request.app.state.db.fetch_one(
        "SELECT * FROM devices WHERE device_id = ?", (payload.device_id,)
    )
    if device is None or not verify_device_token(payload.device_token, device["token_hash"]):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid device credentials")
    return device


def _ensure_device_capacity(connection, user: dict, device_pk: int) -> None:
    if user.get("role") == "admin":
        return
    active = int(user.get("vip_expires_at") or 0) > int(time.time())
    limit = int(user.get("vip_device_limit") or 1) if active else 1
    used = connection.execute(
        """
        SELECT COUNT(*) FROM devices
        WHERE id <> ? AND (
          owner_user_id = ? OR EXISTS (
            SELECT 1 FROM device_user_bindings
            WHERE device_user_bindings.device_pk = devices.id
              AND device_user_bindings.user_id = ?
          )
        )
        """,
        (device_pk, user["id"], user["id"]),
    ).fetchone()[0]
    if int(used) >= limit:
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            f"当前账号最多绑定 {limit} 台电视，请先解绑旧设备或升级套餐",
        )


async def _apply_owner(request: Request, device: dict, user: dict, purpose: str) -> dict:
    now = int(time.time())
    old_owner_id = device.get("owner_user_id")
    with request.app.state.db.transaction() as connection:
        if old_owner_id != user["id"]:
            connection.execute(
                "DELETE FROM device_user_bindings WHERE device_pk = ?", (device["id"],)
            )
        _ensure_device_capacity(connection, user, device["id"])
        connection.execute(
            "UPDATE devices SET owner_user_id = ?, status = 'approved', enabled = 1, updated_at = ? WHERE id = ?",
            (user["id"], now, device["id"]),
        )
        connection.execute(
            """
            INSERT INTO pending_agents(device_id, requested_at, state, reviewed_at, reviewed_by)
            VALUES (?, ?, 'approved', ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET state = 'approved', reviewed_at = excluded.reviewed_at,
                reviewed_by = excluded.reviewed_by
            """,
            (device["device_id"], now, now, user["id"]),
        )
        connection.execute(
            "INSERT INTO device_events(device_pk, type, data_json, created_at) VALUES (?, 'pairing_claimed', ?, ?)",
            (device["id"], json.dumps({"purpose": purpose, "owner_user_id": user["id"]}, separators=(",", ":")), now),
        )
    if old_owner_id != user["id"]:
        await request.app.state.hub.disconnect_controls(
            device["device_id"], reason="device ownership changed"
        )
    owner = user_public(user, _public_base(request))
    await request.app.state.hub.send_device(
        device["device_id"], {"type": "binding_updated", "owner": owner}
    )
    updated = request.app.state.db.fetch_one("SELECT * FROM devices WHERE id = ?", (device["id"],))
    assert updated is not None
    public = device_public(updated, await request.app.state.hub.is_device_online(device["device_id"]))
    await request.app.state.hub.broadcast_control("*", {"type": "device_updated", "device": public})
    return {"device": public, "owner": owner}


@router.post("/api/pairing/device")
async def create_device_pairing(payload: DevicePairingRequest, request: Request) -> dict:
    device = _authenticated_device(request, payload)
    pairing = issue_pending_pairing(request, device)
    if pairing is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "approved devices do not need a pairing code")
    return {
        "device_id": device["device_id"],
        "device_name": device["name"],
        "expires_at": pairing["expires_at"],
        "pairing": pairing,
        "app_pairing": {"code": pairing["code"], "uri": pairing["uri"]},
        "web_pairing": {
            "code": pairing["code"],
            "url": pairing["web_url"],
        },
        "deprecated": True,
    }


@router.post("/api/pairing/device/login")
async def device_account_login(payload: DeviceAccountLoginRequest, request: Request) -> dict:
    device = _authenticated_device(request, payload)
    identity = payload.username.strip()
    user = request.app.state.db.fetch_one(
        "SELECT * FROM users WHERE username = ? COLLATE NOCASE OR (phone <> '' AND phone = ?)",
        (identity, identity),
    )
    if user is None or not user["active"] or not verify_password(payload.password, user["password_hash"]):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid username or password")
    return await _apply_owner(request, device, user, "tv_account_login")


@router.post("/api/pairing/device/logout")
async def device_account_logout(payload: DevicePairingRequest, request: Request) -> dict:
    device = _authenticated_device(request, payload)
    now = int(time.time())
    with request.app.state.db.transaction() as connection:
        connection.execute(
            "DELETE FROM device_user_bindings WHERE device_pk = ?", (device["id"],)
        )
        connection.execute(
            "UPDATE devices SET owner_user_id = NULL, status = 'pending', updated_at = ? WHERE id = ?",
            (now, device["id"]),
        )
        connection.execute(
            "INSERT INTO pending_agents(device_id, requested_at, state) VALUES (?, ?, 'pending') "
            "ON CONFLICT(device_id) DO UPDATE SET state = 'pending', requested_at = excluded.requested_at, "
            "reviewed_at = NULL, reviewed_by = NULL",
            (device["device_id"], now),
        )
        connection.execute(
            "DELETE FROM pairing_sessions WHERE device_pk = ? AND claimed_at IS NULL", (device["id"],)
        )
    await request.app.state.hub.disconnect_controls(device["device_id"], reason="device was unbound")
    await request.app.state.hub.send_device(device["device_id"], {"type": "binding_updated", "owner": None})
    updated = request.app.state.db.fetch_one("SELECT * FROM devices WHERE id = ?", (device["id"],))
    assert updated is not None
    public = device_public(updated, await request.app.state.hub.is_device_online(device["device_id"]))
    await request.app.state.hub.broadcast_control("*", {"type": "device_updated", "device": public})
    return {"device": public, "logged_out": True}


@router.get("/api/pairing/{code}")
def pairing_status(code: str, request: Request) -> dict:
    pairing = _find_pairing(request, code)
    now = int(time.time())
    if pairing is None or pairing["expires_at"] < now:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "pairing code is invalid or expired")
    owner = None
    if (
        pairing["claimed_at"] is not None
        and pairing.get("claimed_username")
        and pairing.get("device_status") == "approved"
    ):
        owner = {
            "username": pairing["claimed_username"],
            "nickname": pairing.get("claimed_nickname") or "",
            "phone": pairing.get("claimed_phone") or "",
            "vip_active": int(pairing.get("claimed_vip_expires_at") or 0) > now,
            "vip_expires_at": int(pairing.get("claimed_vip_expires_at") or 0),
            "vip_tag_style": pairing.get("claimed_vip_tag_style") or "sky",
            "vip_plan_name": pairing.get("claimed_vip_plan_name") or "",
            "vip_device_limit": int(pairing.get("claimed_vip_device_limit") or 1),
            "vip_benefits": json.loads(pairing.get("claimed_vip_benefits_json") or "[]"),
            "vip_entitlements": json.loads(pairing.get("claimed_vip_entitlements_json") or "{}"),
            "vip_badge_text": pairing.get("claimed_vip_badge_text") or "",
            "vip_accent_color": pairing.get("claimed_vip_accent_color") or "#2F80ED",
        }
    approved = pairing["claimed_at"] is not None and pairing.get("device_status") == "approved"
    return {
        "device_id": pairing["device_id"],
        "device_name": pairing["name"],
        "model": pairing["model"],
        "purpose": pairing["purpose"],
        "expires_at": pairing["expires_at"],
        "claimed": pairing["claimed_at"] is not None,
        "approval_status": pairing.get("device_status") or "pending",
        "owner": owner,
        "close_pairing": approved,
        "message": "登录成功 · App 审批已批准！" if approved else "",
    }


@router.post("/api/pairing/{code}", include_in_schema=False)
@router.post("/api/pairing/{code}/claim")
async def claim_pairing(
    code: str,
    request: Request,
    user: dict = Depends(get_current_user),
) -> dict:
    now = int(time.time())
    code_hash = pairing_digest(code)
    with request.app.state.db.transaction() as connection:
        pairing_row = connection.execute(
            """
            SELECT pairing_sessions.*, devices.device_id, devices.owner_user_id,
                   devices.status AS device_status
            FROM pairing_sessions
            JOIN devices ON devices.id = pairing_sessions.device_pk
            WHERE pairing_sessions.code_hash = ?
            """,
            (code_hash,),
        ).fetchone()
        if pairing_row is None or pairing_row["expires_at"] < now:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "pairing code is invalid or expired")
        if pairing_row["claimed_at"] is not None:
            raise HTTPException(status.HTTP_409_CONFLICT, "pairing code has already been used")
        device_id = pairing_row["device_id"]
        old_owner_id = pairing_row["owner_user_id"]
        if (
            old_owner_id is not None
            and old_owner_id != user["id"]
            and pairing_row["device_status"] == "approved"
        ):
            # A pairing code stays valid for minutes. A stale one must never hand an already
            # approved, already-owned TV to another account (that was a full takeover).
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                "device is already paired to another account",
            )
        _ensure_device_capacity(connection, user, pairing_row["device_pk"])
        if old_owner_id != user["id"]:
            connection.execute(
                "DELETE FROM device_user_bindings WHERE device_pk = ?",
                (pairing_row["device_pk"],),
            )
        connection.execute(
            """
            UPDATE devices
            SET owner_user_id = ?, status = 'pending', enabled = 1, updated_at = ?
            WHERE id = ?
            """,
            (user["id"], now, pairing_row["device_pk"]),
        )
        connection.execute(
            """
            INSERT INTO pending_agents(device_id, requested_at, state, reviewed_at, reviewed_by)
            VALUES (?, ?, 'pending', NULL, NULL)
            ON CONFLICT(device_id) DO UPDATE SET
                state = 'pending', requested_at = excluded.requested_at,
                reviewed_at = NULL, reviewed_by = NULL
            """,
            (device_id, now),
        )
        connection.execute(
            "UPDATE pairing_sessions SET claimed_at = ?, claimed_by = ? WHERE code_hash = ?",
            (now, user["id"], code_hash),
        )
        connection.execute(
            """
            INSERT INTO device_events(device_pk, type, data_json, created_at)
            VALUES (?, 'pairing_claimed', ?, ?)
            """,
            (
                pairing_row["device_pk"],
                json.dumps(
                    {"purpose": pairing_row["purpose"], "owner_user_id": user["id"]},
                    separators=(",", ":"),
                ),
                now,
            ),
        )
    if old_owner_id != user["id"]:
        await request.app.state.hub.disconnect_controls(
            device_id, reason="device ownership changed by pairing"
        )
    device = request.app.state.db.fetch_one(
        "SELECT * FROM devices WHERE device_id = ?", (device_id,)
    )
    assert device is not None
    public = device_public(device, await request.app.state.hub.is_device_online(device_id))
    public["approval_required"] = True
    public["next_step"] = "open_device_approvals"
    public["approval_path"] = "/approvals"
    public["approval_api"] = "/api/device-approvals"
    await request.app.state.hub.send_device(
        device_id, {"type": "approval_result", "device": public}
    )
    await request.app.state.hub.broadcast_control(
        "*", {"type": "device_updated", "device": public}
    )
    return public


@router.get("/pair/{code}", response_class=HTMLResponse, include_in_schema=False)
def pairing_page(code: str) -> HTMLResponse:
    # json.dumps escapes quotes but not "</script>", and this value is interpolated into an
    # inline script: without escaping '<' the path segment can close the tag and inject markup.
    code_json = json.dumps(code, ensure_ascii=True).replace("<", "\\u003c")
    html = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>星投屏设备配置</title><style>
*{{box-sizing:border-box}}body{{margin:0;background:#f4f8fc;color:#17212b;font-family:Arial,sans-serif}}
main{{width:min(680px,100%);margin:auto;padding:24px 18px 48px}}h1{{font-size:28px;margin:10px 0}}
.sub{{color:#66717d;margin:0 0 22px}}section{{background:#fff;border:1px solid #d7e0ea;border-radius:8px;padding:18px;margin:14px 0}}
label{{display:block;font-size:13px;font-weight:700;margin:12px 0 6px}}input,button{{width:100%;height:46px;border-radius:6px;font:inherit}}
input{{border:1px solid #bcc8d4;padding:0 12px}}button{{border:0;background:#2f80ed;color:#fff;font-weight:700;margin-top:12px;cursor:pointer}}
button.alt{{background:#e7f0fb;color:#195da8}}button:disabled{{opacity:.55}}.row{{display:grid;grid-template-columns:1fr 1fr;gap:10px}}
#message{{min-height:24px;color:#c83c4b}}#tools{{display:none}}small{{color:#66717d}}@media(max-width:520px){{.row{{grid-template-columns:1fr}}}}
</style></head><body><main><h1>星投屏设备配置</h1><p class="sub" id="device">正在读取电视信息...</p>
<section id="auth"><h2>登录或注册</h2><label>账号或手机号</label><input id="username" autocomplete="username">
<div class="row"><div><label>昵称（注册时填写）</label><input id="nickname" maxlength="32"></div>
<div><label>手机号（注册时填写）</label><input id="phone" inputmode="tel" maxlength="20" autocomplete="tel"></div></div>
<label>密码</label><input id="password" type="password" autocomplete="current-password"><div class="row">
<button id="login">登录并绑定</button><button class="alt" id="register">注册并绑定</button></div></section>
<section id="tools"><h2>设备已绑定</h2><p>账号已经获得该电视的远程控制权限。</p>
<label>上传 APK 到电视安装</label><input id="apk" type="file" accept=".apk,application/vnd.android.package-archive">
<button id="install">上传并打开电视安装器</button><small>电视端仍会显示系统安装确认页。</small></section>
<p id="message"></p></main><script>
(function(){{'use strict';var code={code_json},token='',deviceId='';
var msg=document.getElementById('message');function say(v,ok){{msg.textContent=v;msg.style.color=ok?'#278a59':'#c83c4b';}}
async function call(path,options){{options=options||{{}};options.headers=options.headers||{{}};
if(token)options.headers.Authorization='Bearer '+token;var r=await fetch(path,options);var data=await r.json().catch(function(){{return {{}}}});
if(!r.ok)throw new Error(data.detail||'请求失败（'+r.status+'）');return data;}}
async function load(){{try{{var p=await call('/api/pairing/'+encodeURIComponent(code));deviceId=p.device_id;
document.getElementById('device').textContent=p.device_name+' · '+(p.model||'Android TV');if(p.claimed)say('二维码已使用，请在电视刷新二维码',false);}}
catch(e){{say(e.message,false);document.getElementById('auth').style.display='none';}}}}
async function authenticate(register){{var username=document.getElementById('username').value.trim(),password=document.getElementById('password').value;
var nickname=document.getElementById('nickname').value.trim(),phone=document.getElementById('phone').value.trim();
if(!username||password.length<8){{say('请输入账号，密码至少 8 位',false);return;}}try{{say(register?'正在注册...':'正在登录...',true);
if(register&&!/^\\+?[0-9]{{6,20}}$/.test(phone)){{say('注册时请填写正确的手机号',false);return;}}
var authBody=register?{{username:username,password:password,nickname:nickname,phone:phone}}:{{username:username,password:password}};
var result=await call(register?'/api/auth/register':'/api/auth/login',{{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify(authBody)}});
token=result.access_token;var bound=await call('/api/pairing/'+encodeURIComponent(code)+'/claim',{{method:'POST'}});
document.getElementById('auth').style.display='none';
var owner=result.user||{{}};say('绑定申请已提交，请在手机 App 或管理后台审批：'+(owner.nickname||owner.phone||owner.username||username),true);}}
catch(e){{say(e.message,false);}}}}
document.getElementById('login').onclick=function(){{authenticate(false)}};document.getElementById('register').onclick=function(){{authenticate(true)}};
document.getElementById('install').onclick=async function(){{var file=document.getElementById('apk').files[0];if(!file){{say('请先选择 APK',false);return;}}
try{{say('正在上传 APK...',true);var form=new FormData();form.append('file',file);var apk=await call('/api/upload/apk',{{method:'POST',body:form}});
await call('/api/device/'+encodeURIComponent(deviceId)+'/commands',{{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify({{type:'install',payload:{{url:apk.url,sha256:apk.sha256,filename:apk.filename}}}})}});say('已发送到电视，请在电视确认安装',true);}}
catch(e){{say(e.message,false);}}}};load();}})();
</script></body></html>"""
    return HTMLResponse(
        html,
        headers={
            "Cache-Control": "no-store",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
            "Content-Security-Policy": (
                "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                "connect-src 'self'; form-action 'self';"
            ),
        },
    )
