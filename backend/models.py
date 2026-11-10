from __future__ import annotations

import re
from typing import Any, Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, Field, field_validator, model_validator


DeviceStatus = Literal["pending", "approved", "rejected", "revoked"]
CommandType = Literal["key", "media", "install", "switch", "snapshot", "p2p", "settings", "app", "power"]
SignalKind = Literal["offer", "answer", "ice", "ready", "failed"]
ReleasePlatform = Literal["tv", "mobile"]
VipTagStyle = Literal["sky", "mint", "gold", "rose", "violet"]


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class RegisterRequest(BaseModel):
    username: str = Field(min_length=3, max_length=64, pattern=r"^[A-Za-z0-9_.-]+$")
    password: str = Field(min_length=8, max_length=256)
    nickname: str = Field(default="", max_length=32)
    phone: str = Field(default="", max_length=20)

    @field_validator("username")
    @classmethod
    def normalize_username(cls, value: str) -> str:
        return value.strip()

    @field_validator("nickname", "phone")
    @classmethod
    def normalize_profile_fields(cls, value: str) -> str:
        return value.strip()

    @field_validator("phone")
    @classmethod
    def validate_phone(cls, value: str) -> str:
        if value and not re.fullmatch(r"\+?[0-9]{6,20}", value):
            raise ValueError("phone must contain 6-20 digits with an optional leading plus")
        return value


class DevicePairingRequest(BaseModel):
    device_id: str = Field(pattern=r"^[A-Za-z0-9_.:-]{6,96}$")
    device_token: str = Field(min_length=16, max_length=256)


class DeviceAccountLoginRequest(DevicePairingRequest):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class DeviceRegisterRequest(BaseModel):
    device_id: str = Field(pattern=r"^[A-Za-z0-9_.:-]{6,96}$")
    name: str = Field(min_length=1, max_length=64)
    token: str | None = Field(default=None, max_length=256)
    public_key: str = Field(default="", max_length=4096)
    platform: str = Field(default="android_tv", max_length=64)
    model: str = Field(default="", max_length=128)
    app_version: str = Field(default="", max_length=64)
    lan_ip: str = Field(default="", max_length=64)
    owner_username: str = Field(default="", max_length=64)
    owner_phone: str = Field(default="", max_length=20)

    @field_validator("owner_username", "owner_phone")
    @classmethod
    def normalize_owner_username(cls, value: str) -> str:
        return value.strip()


class ApprovalRequest(BaseModel):
    device_id: str = Field(pattern=r"^[A-Za-z0-9_.:-]{6,96}$")
    approve: bool = True
    name: str | None = Field(default=None, min_length=1, max_length=64)
    owner_user_id: int | None = Field(default=None, gt=0)


class DeviceApprovalDecisionRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=64)
    owner_user_id: int | None = Field(default=None, gt=0)


class UserCreateRequest(BaseModel):
    username: str = Field(min_length=3, max_length=64, pattern=r"^[A-Za-z0-9_.-]+$")
    password: str = Field(min_length=8, max_length=256)
    nickname: str = Field(default="", max_length=32)
    phone: str = Field(default="", max_length=20)

    @field_validator("username")
    @classmethod
    def normalize_username(cls, value: str) -> str:
        return value.strip()

    @field_validator("nickname", "phone")
    @classmethod
    def normalize_user_fields(cls, value: str) -> str:
        return value.strip()

    @field_validator("phone")
    @classmethod
    def validate_user_phone(cls, value: str) -> str:
        if value and not re.fullmatch(r"\+?[0-9]{6,20}", value):
            raise ValueError("phone must contain 6-20 digits with an optional leading plus")
        return value


class UserUpdateRequest(BaseModel):
    active: bool | None = None
    password: str | None = Field(default=None, min_length=8, max_length=256)
    nickname: str | None = Field(default=None, max_length=32)
    phone: str | None = Field(default=None, max_length=20)
    vip_expires_at: int | None = Field(default=None, ge=0)
    vip_tag_style: VipTagStyle | None = None
    vip_plan_name: str | None = Field(default=None, max_length=48)

    @field_validator("nickname", "phone", "vip_plan_name")
    @classmethod
    def normalize_optional_user_fields(cls, value: str | None) -> str | None:
        return value.strip() if value is not None else None

    @field_validator("phone")
    @classmethod
    def validate_optional_user_phone(cls, value: str | None) -> str | None:
        if value and not re.fullmatch(r"\+?[0-9]{6,20}", value):
            raise ValueError("phone must contain 6-20 digits with an optional leading plus")
        return value


class ProfileUpdateRequest(BaseModel):
    nickname: str = Field(default="", max_length=32)
    phone: str = Field(default="", max_length=20)

    @field_validator("nickname", "phone")
    @classmethod
    def normalize_profile_update(cls, value: str) -> str:
        return value.strip()

    @field_validator("phone")
    @classmethod
    def validate_profile_phone(cls, value: str) -> str:
        if value and not re.fullmatch(r"\+?[0-9]{6,20}", value):
            raise ValueError("phone must contain 6-20 digits with an optional leading plus")
        return value


class PasswordChangeRequest(BaseModel):
    old_password: str = Field(min_length=1, max_length=256)
    new_password: str = Field(min_length=8, max_length=256)
    new_password_confirm: str = Field(min_length=8, max_length=256)

    @model_validator(mode="after")
    def matching_new_passwords(self) -> "PasswordChangeRequest":
        if self.new_password != self.new_password_confirm:
            raise ValueError("new passwords do not match")
        if self.old_password == self.new_password:
            raise ValueError("new password must be different")
        return self


class VipPlanCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=48)
    days: int = Field(ge=1, le=3650)
    price_cents: int = Field(ge=0, le=100_000_000)
    tag_style: VipTagStyle = "sky"
    description: str = Field(default="", max_length=240)
    device_limit: int = Field(default=1, ge=1, le=1000)
    benefits: list[str] = Field(default_factory=list, max_length=32)
    entitlements: dict[str, bool] = Field(default_factory=dict)
    badge_text: str = Field(default="", max_length=16)
    accent_color: str = Field(default="#2F80ED", pattern=r"^#[0-9A-Fa-f]{6}$")
    recommended: bool = False
    active: bool = True
    sort_order: int = Field(default=0, ge=-10000, le=10000)

    @field_validator("name", "description", "badge_text")
    @classmethod
    def trim_vip_plan_fields(cls, value: str) -> str:
        return value.strip()

    @field_validator("benefits")
    @classmethod
    def normalize_benefits(cls, values: list[str]) -> list[str]:
        result: list[str] = []
        for value in values:
            normalized = str(value).strip()
            if normalized and normalized not in result:
                if len(normalized) > 80:
                    raise ValueError("each benefit must not exceed 80 characters")
                result.append(normalized)
        return result

    @field_validator("entitlements")
    @classmethod
    def normalize_entitlements(cls, values: dict[str, bool]) -> dict[str, bool]:
        from entitlements import CONFIGURABLE_ENTITLEMENTS

        allowed = CONFIGURABLE_ENTITLEMENTS
        unknown = set(values) - allowed
        if unknown:
            raise ValueError(f"unsupported entitlements: {', '.join(sorted(unknown))}")
        return {key: bool(value) for key, value in values.items()}


class VipPlanUpdateRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=48)
    days: int | None = Field(default=None, ge=1, le=3650)
    price_cents: int | None = Field(default=None, ge=0, le=100_000_000)
    tag_style: VipTagStyle | None = None
    description: str | None = Field(default=None, max_length=240)
    device_limit: int | None = Field(default=None, ge=1, le=1000)
    benefits: list[str] | None = Field(default=None, max_length=32)
    entitlements: dict[str, bool] | None = None
    badge_text: str | None = Field(default=None, max_length=16)
    accent_color: str | None = Field(default=None, pattern=r"^#[0-9A-Fa-f]{6}$")
    recommended: bool | None = None
    active: bool | None = None
    sort_order: int | None = Field(default=None, ge=-10000, le=10000)

    @field_validator("name", "description", "badge_text")
    @classmethod
    def trim_optional_vip_plan_fields(cls, value: str | None) -> str | None:
        return value.strip() if value is not None else None

    @field_validator("benefits")
    @classmethod
    def normalize_optional_benefits(cls, values: list[str] | None) -> list[str] | None:
        return VipPlanCreateRequest.normalize_benefits(values) if values is not None else None

    @field_validator("entitlements")
    @classmethod
    def normalize_optional_entitlements(cls, values: dict[str, bool] | None) -> dict[str, bool] | None:
        return VipPlanCreateRequest.normalize_entitlements(values) if values is not None else None


class VipOrderCreateRequest(BaseModel):
    plan_id: int = Field(gt=0)
    user_id: int | None = Field(default=None, gt=0)


class VipPaymentCreateRequest(BaseModel):
    plan_id: int = Field(gt=0)
    payment_method: Literal["alipay", "wxpay"]
    device_id: str = Field(default="", max_length=96, pattern=r"^[A-Za-z0-9_.:-]*$")


class DeviceVipPaymentCreateRequest(VipPaymentCreateRequest, DevicePairingRequest):
    pass


class VipOrderReviewRequest(BaseModel):
    status: Literal["paid", "cancelled"]


class VipCodeCreateRequest(BaseModel):
    plan_id: int = Field(gt=0)
    count: int = Field(default=1, ge=1, le=100)


class VipCodeRedeemRequest(BaseModel):
    code: str = Field(min_length=8, max_length=96)

    @field_validator("code")
    @classmethod
    def normalize_code(cls, value: str) -> str:
        return value.strip().upper()


class DeviceVipRedeemRequest(VipCodeRedeemRequest, DevicePairingRequest):
    pass


class DeviceUpdateRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=64)
    enabled: bool | None = None
    owner_user_id: int | None = Field(default=None, gt=0)


class DeviceBindingCreateRequest(BaseModel):
    user_id: int = Field(gt=0)


class CommandCreateRequest(BaseModel):
    type: CommandType
    payload: dict[str, Any] = Field(default_factory=dict)


class P2PSessionRequest(BaseModel):
    mode: Literal["p2p", "webrtc", "relay", "auto"] = "auto"


class P2PSignalRequest(BaseModel):
    session_id: str = Field(min_length=8, max_length=128)
    kind: SignalKind
    payload: dict[str, Any] = Field(default_factory=dict)


class PlayerSessionRequest(BaseModel):
    url: str = Field(min_length=8, max_length=8192)
    media_type: str = Field(default="", max_length=128)
    title: str = Field(default="星投屏", max_length=128)

    @field_validator("url")
    @classmethod
    def validate_player_url(cls, value: str) -> str:
        normalized = value.strip()
        try:
            parsed = urlsplit(normalized)
        except ValueError as exc:
            raise ValueError("invalid media URL") from exc
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("media URL must use HTTP or HTTPS")
        if parsed.username or parsed.password:
            raise ValueError("media URL cannot contain credentials")
        return normalized

    @field_validator("media_type", "title")
    @classmethod
    def trim_player_fields(cls, value: str) -> str:
        return value.strip()


class ReleasePublishRequest(BaseModel):
    platform: ReleasePlatform
    apk_id: int = Field(gt=0)
    version_code: int = Field(gt=0)
    version_name: str = Field(min_length=1, max_length=64)
    release_notes: str = Field(default="", max_length=4000)
    mandatory: bool = False

    @field_validator("version_name", "release_notes")
    @classmethod
    def trim_release_fields(cls, value: str) -> str:
        return value.strip()


class AiApiConfig(BaseModel):
    ai_api_url: str = Field(default="", max_length=2048)
    ai_api_key: str | None = Field(default=None, max_length=4096)
    ai_api_model: str = Field(default="", max_length=256)

    @field_validator("ai_api_url", "ai_api_model")
    @classmethod
    def trim_ai_fields(cls, value: str) -> str:
        return value.strip()

    @field_validator("ai_api_key")
    @classmethod
    def normalize_api_key(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            return None
        if any(character in normalized for character in "\r\n"):
            raise ValueError("API key cannot contain line breaks")
        return normalized

    @field_validator("ai_api_url")
    @classmethod
    def validate_api_url(cls, value: str) -> str:
        if not value:
            return value
        try:
            parsed = urlsplit(value)
        except ValueError as exc:
            raise ValueError("invalid API URL") from exc
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("API URL must use HTTP or HTTPS")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("API URL cannot contain credentials, query parameters, or fragments")
        return value.rstrip("/")


class SettingsUpdateRequest(AiApiConfig):
    ai_enabled: bool
    clear_ai_api_key: bool = False
    p2p_enabled: bool
    relay_enabled: bool
    sales_enabled: bool = False
    payment_enabled: bool = False
    epay_payment_methods: list[Literal["alipay", "wxpay"]] = Field(
        default_factory=lambda: ["alipay", "wxpay"], max_length=2
    )
    epay_base_url: str = Field(default="https://pay.id0.cn", max_length=2048)
    epay_pid: str = Field(default="", max_length=128)
    epay_key: str | None = Field(default=None, max_length=4096)
    clear_epay_key: bool = False
    epay_notify_url: str = Field(default="", max_length=2048)
    epay_return_url: str = Field(default="", max_length=2048)

    @model_validator(mode="after")
    def validate_ai_configuration(self) -> "SettingsUpdateRequest":
        if self.clear_ai_api_key and self.ai_api_key:
            raise ValueError("cannot set and clear the API key in the same request")
        if self.ai_enabled and (not self.ai_api_url or not self.ai_api_model):
            raise ValueError("API URL and API model are required when AI is enabled")
        if self.clear_epay_key and self.epay_key:
            raise ValueError("cannot set and clear the Epay key in the same request")
        self.epay_payment_methods = list(dict.fromkeys(self.epay_payment_methods))
        if self.payment_enabled and not self.epay_payment_methods:
            raise ValueError("at least one payment method is required when payment is enabled")
        for field_name in ("epay_base_url", "epay_notify_url", "epay_return_url"):
            value = getattr(self, field_name)
            if not value:
                continue
            try:
                parsed = urlsplit(value)
            except ValueError as exc:
                raise ValueError(f"invalid {field_name}") from exc
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                raise ValueError(f"{field_name} must use HTTP or HTTPS")
            if parsed.username or parsed.password or parsed.fragment:
                raise ValueError(f"{field_name} cannot contain credentials or fragments")
            if field_name == "epay_base_url" and parsed.query:
                raise ValueError("epay_base_url cannot contain query parameters")
            setattr(self, field_name, value.rstrip("/"))
        if self.payment_enabled and (not self.epay_pid or (not self.epay_key and not self.clear_epay_key)):
            # A stored key may satisfy this requirement; the endpoint verifies it before saving.
            pass
        return self


class EpayConnectionTestRequest(BaseModel):
    epay_base_url: str = Field(default="https://pay.id0.cn", max_length=2048)
    epay_pid: str = Field(min_length=1, max_length=128)
    epay_key: str | None = Field(default=None, max_length=4096)
    use_stored_epay_key: bool = True

    @field_validator("epay_base_url")
    @classmethod
    def validate_epay_base_url(cls, value: str) -> str:
        normalized = value.strip().rstrip("/")
        parsed = urlsplit(normalized)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("Epay URL must use HTTP or HTTPS")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("Epay URL cannot contain credentials, query parameters, or fragments")
        return normalized

    @field_validator("epay_pid")
    @classmethod
    def trim_epay_pid(cls, value: str) -> str:
        return value.strip()


class AiConnectionTestRequest(AiApiConfig):
    use_stored_api_key: bool = True

    @model_validator(mode="after")
    def require_connection_fields(self) -> "AiConnectionTestRequest":
        if not self.ai_api_url or not self.ai_api_model:
            raise ValueError("API URL and API model are required")
        return self


class AgentEnvelope(BaseModel):
    type: str = Field(min_length=1, max_length=64)
    device_id: str = Field(min_length=6, max_length=96)
    ts: int | None = None
    data: dict[str, Any] = Field(default_factory=dict)

    @field_validator("type")
    @classmethod
    def supported_type(cls, value: str) -> str:
        allowed = {
            "heartbeat",
            "snapshot",
            "device_info",
            "install_result",
            "page_state",
            "command_result",
            "p2p_signal",
            "app_inventory",
        }
        if value not in allowed:
            raise ValueError("unsupported agent event type")
        return value
