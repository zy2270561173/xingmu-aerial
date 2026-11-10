export type DeviceStatus = 'pending' | 'approved' | 'rejected' | 'revoked';
export type CommandStatus = 'pending' | 'delivered' | 'started' | 'completed' | 'failed';

export interface User {
  id: number;
  username: string;
  nickname: string;
  phone: string;
  avatar_url: string;
  role: 'admin' | 'user';
  active?: boolean;
  created_at?: number;
  device_count?: number;
  pending_count?: number;
  vip_active: boolean;
  vip_expires_at: number;
  vip_tag_style: 'sky' | 'mint' | 'gold' | 'rose' | 'violet';
  vip_plan_name: string;
  vip_active_plans: string[];
  vip_device_limit: number;
  vip_benefits: string[];
  vip_entitlements: Record<string, boolean>;
  vip_badge_text: string;
  vip_accent_color: string;
}

export interface Device {
  id: number;
  device_id: string;
  name: string;
  owner_user_id: number | null;
  owner?: Pick<User, 'username' | 'nickname' | 'phone'> | null;
  status: DeviceStatus;
  enabled: boolean;
  online: boolean;
  power_state: 'online' | 'reconnecting' | 'assumed_off';
  heartbeat_age_seconds: number | null;
  missed_heartbeats: number;
  platform: string;
  model: string;
  app_version: string;
  last_seen_at: number | null;
  lan_ip: string;
  wan_ip: string;
  nat_type: string;
  public_key: string;
  current_activity: string;
  playing_url: string;
  position_ms: number;
  battery_whitelist: boolean;
  metadata: Record<string, unknown>;
  created_at: number;
  updated_at: number;
  requested_at?: number;
}

export interface DeviceBinding extends User {
  binding_role: 'owner' | 'member';
  bound_at: number;
}

export interface Command {
  id: number;
  device_id: string;
  type: string;
  payload: Record<string, unknown>;
  status: CommandStatus;
  result: Record<string, unknown> | null;
  created_at: number;
  delivered_at: number | null;
  completed_at: number | null;
}

export interface DeviceEvent {
  id: number;
  type: string;
  data: Record<string, unknown>;
  created_at: number;
}

export interface InstalledApp {
  package_name: string;
  label: string;
  version_name: string;
  version_code: number;
  system: boolean;
  enabled: boolean;
  launchable: boolean;
  first_install_time: number;
  last_update_time: number;
  icon_data_uri: string;
  collected_at: number;
}

export interface ClientRelease {
  id: number;
  platform: 'tv' | 'mobile';
  version_code: number;
  version_name: string;
  release_notes: string;
  mandatory: boolean;
  published_at: number;
  filename: string;
  size: number;
  sha256: string;
  url: string;
}

export interface ReleasePublishRequest {
  platform: 'tv' | 'mobile';
  apk_id: number;
  version_code: number;
  version_name: string;
  release_notes: string;
  mandatory: boolean;
}

export interface ApkArtifact {
  id: number;
  filename: string;
  size: number;
  sha256: string;
  url: string;
  created_at: number;
}

export interface ConsoleSettings {
  ai_enabled: boolean;
  ai_api_url: string;
  ai_api_model: string;
  ai_api_key_set: boolean;
  p2p_enabled: boolean;
  relay_enabled: boolean;
  sales_enabled: boolean;
  payment_enabled: boolean;
  epay_payment_methods: Array<'alipay' | 'wxpay'>;
  epay_base_url: string;
  epay_pid: string;
  epay_key_set: boolean;
  epay_notify_url: string;
  epay_return_url: string;
  epay_merchant_name: string;
  epay_merchant_account: string;
  epay_merchant_active: boolean;
  epay_merchant_balance: string;
  epay_merchant_settlement_type: number;
  epay_merchant_orders: number;
  epay_merchant_orders_today: number;
  epay_merchant_orders_lastday: number;
  epay_verified_pid: string;
  epay_verified_at: number;
}

export interface VipEntitlements {
  remote_cast?: boolean;
  remote_control?: boolean;
  screen_view?: boolean;
  app_install?: boolean;
  p2p_access?: boolean;
  relay_access?: boolean;
}
export interface VipPlan { id: number; name: string; days: number; price_cents: number; tag_style: 'sky' | 'mint' | 'gold' | 'rose' | 'violet'; description: string; device_limit: number; benefits: string[]; entitlements: VipEntitlements; badge_text: string; accent_color: string; recommended: boolean; active: boolean; sort_order: number; }
export interface VipOrder { id: number; order_no: string; user_id: number; username: string; nickname: string; phone: string; plan_id: number; plan_name: string; days: number; amount_cents: number; tag_style: string; device_limit: number; benefits: string[]; entitlements: VipEntitlements; badge_text: string; accent_color: string; status: 'pending' | 'paid' | 'cancelled'; payment_status: 'unpaid' | 'paid' | 'failed'; payment_provider: string; payment_method: '' | 'alipay' | 'wxpay'; provider_trade_no: string; payment_payload_type: string; payment_target: string; source: 'manual' | 'web' | 'mobile' | 'tv'; device_id: string; device_name: string; created_at: number; updated_at: number; expires_at?: number; paid_at?: number; cancelled_at?: number; }
export interface VipCode { id: number; code_hint: string; plan_id: number; plan_name: string; days: number; device_limit: number; benefits: string[]; entitlements: VipEntitlements; tag_style: string; status: 'unused' | 'redeemed'; created_at: number; redeemed_at?: number; redeemed_username?: string; }
export interface CreatedVipCode extends VipCode { code: string; redeem_uri: string; }
export interface VipGrant { id: number; user_id: number; username: string; nickname: string; phone: string; plan_id?: number; plan_name: string; days: number; tag_style: string; device_limit: number; benefits: string[]; entitlements: VipEntitlements; badge_text: string; accent_color: string; source: string; status: 'active' | 'revoked' | 'expired'; granted_at: number; expires_at: number; revoked_at?: number; }

export interface ConsoleSettingsUpdate extends ConsoleSettings {
  ai_api_key: string;
  clear_ai_api_key: boolean;
  epay_key: string;
  clear_epay_key: boolean;
}

export interface EpayConnectionTestResult {
  ok: boolean;
  message: string;
  latency_ms: number | null;
  merchant_pid?: string;
  merchant_name?: string;
  merchant_active?: boolean;
  merchant_balance?: string;
  settlement_type?: number;
  settlement_account?: string;
  orders?: number;
  orders_today?: number;
  orders_lastday?: number;
  verified_at?: number;
}

export interface AiConnectionTestRequest {
  ai_api_url: string;
  ai_api_key?: string;
  ai_api_model: string;
  use_stored_api_key: boolean;
}

export interface AiConnectionTestResult {
  ok: boolean;
  message: string;
  latency_ms: number | null;
  model?: string;
}

export interface SystemStatus {
  status: string;
  version: string;
  uptime_seconds: number;
  database_size_bytes: number;
  registered_devices: number;
  enabled_devices: number;
  pending_approvals: number;
  active_commands: number;
  failed_commands: number;
  stored_apks: number;
  connected_agents: number;
  connected_controls: number;
  control_scopes: number;
  cached_snapshots: number;
  media_relay: boolean;
  p2p_mode: string;
}

export interface HostedPlayerSession {
  ticket: string;
  player_path: string;
  player_url: string;
  engine: 'dplayer';
  expires_at: number;
  transcode_available: boolean;
  ffmpeg_detected: boolean;
  note: string;
}

export interface RealtimeMessage {
  type: string;
  device_id?: string;
  online?: boolean;
  device?: Device;
  command?: Command;
  event?: string;
  data?: Record<string, unknown>;
  [key: string]: unknown;
}
