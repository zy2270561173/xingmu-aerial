import axios from 'axios';
import { http } from './http';
import type {
  AiConnectionTestRequest,
  AiConnectionTestResult,
  EpayConnectionTestResult,
  ApkArtifact,
  ClientRelease,
  Command,
  ConsoleSettings,
  ConsoleSettingsUpdate,
  Device,
  DeviceBinding,
  DeviceEvent,
  InstalledApp,
  HostedPlayerSession,
  ReleasePublishRequest,
  SystemStatus,
  User,
  VipPlan, VipOrder, VipCode, CreatedVipCode, VipGrant,
} from '../types';

export async function login(username: string, password: string) {
  return (await http.post<{ access_token: string; user: User }>('/api/auth/login', { username, password })).data;
}

export async function getMe() {
  return (await http.get<User>('/api/auth/me')).data;
}

export async function getUsers() {
  return (await http.get<User[]>('/api/users')).data;
}

export async function createUser(payload: { username: string; password: string; nickname?: string; phone?: string }) {
  return (await http.post<User>('/api/users', payload)).data;
}

export async function updateUser(userId: number, payload: { active?: boolean; password?: string; nickname?: string; phone?: string; vip_expires_at?: number; vip_tag_style?: string; vip_plan_name?: string }) {
  return (await http.patch<User>(`/api/users/${userId}`, payload)).data;
}

export async function getVipPlans() { return (await http.get<VipPlan[]>('/api/vip/plans')).data; }
export async function createVipPlan(payload: Omit<VipPlan, 'id'>) { return (await http.post<VipPlan>('/api/vip/plans', payload)).data; }
export async function updateVipPlan(id: number, payload: Partial<VipPlan>) { return (await http.patch<VipPlan>(`/api/vip/plans/${id}`, payload)).data; }
export async function getVipOrders() { return (await http.get<VipOrder[]>('/api/vip/orders')).data; }
export async function createVipOrder(planId: number, userId?: number) { return (await http.post<VipOrder>('/api/vip/orders', { plan_id: planId, user_id: userId })).data; }
export async function reviewVipOrder(id: number, status: 'paid' | 'cancelled') { return (await http.post<VipOrder>(`/api/vip/orders/${id}/review`, { status })).data; }
export async function getVipCodes() { return (await http.get<VipCode[]>('/api/vip/codes')).data; }
export async function createVipCodes(planId: number, count: number) { return (await http.post<CreatedVipCode[]>('/api/vip/codes', { plan_id: planId, count })).data; }
export async function getVipGrants() { return (await http.get<VipGrant[]>('/api/vip/grants')).data; }
export async function revokeVipGrant(userId: number, grantId: number) { return (await http.delete<{ revoked: VipGrant; user: User }>(`/api/vip/users/${userId}/grants/${grantId}`)).data; }

export async function deleteUser(userId: number) {
  return (await http.delete<{ deleted: boolean; user_id: number; unbound_device_ids: string[] }>(`/api/users/${userId}`)).data;
}

export async function uploadUserAvatar(userId: number, file: File) {
  const form = new FormData();
  form.append('file', file);
  return (await http.post<User>(`/api/users/${userId}/avatar`, form)).data;
}

export async function unbindDevice(deviceId: string) {
  return (await http.delete<Device>(`/api/device/${encodeURIComponent(deviceId)}/binding`)).data;
}

export async function getDevices() {
  return (await http.get<Device[]>('/api/devices')).data;
}

export async function getDevice(deviceId: string) {
  return (await http.get<Device>(`/api/device/${encodeURIComponent(deviceId)}`)).data;
}

export async function getDeviceBindings(deviceId: string) {
  return (await http.get<DeviceBinding[]>(`/api/device/${encodeURIComponent(deviceId)}/bindings`)).data;
}

export async function addDeviceBinding(deviceId: string, userId: number) {
  return (await http.post<DeviceBinding[]>(`/api/device/${encodeURIComponent(deviceId)}/bindings`, { user_id: userId })).data;
}

export async function setPrimaryDeviceBinding(deviceId: string, userId: number) {
  return (await http.put<DeviceBinding[]>(`/api/device/${encodeURIComponent(deviceId)}/bindings/${userId}/primary`)).data;
}

export async function removeDeviceBinding(deviceId: string, userId: number) {
  return (await http.delete<DeviceBinding[]>(`/api/device/${encodeURIComponent(deviceId)}/bindings/${userId}`)).data;
}

export async function updateDevice(deviceId: string, payload: { name?: string; enabled?: boolean; owner_user_id?: number }) {
  return (await http.patch<Device>(`/api/device/${encodeURIComponent(deviceId)}`, payload)).data;
}

export async function deleteDevice(deviceId: string) {
  return (await http.delete<{ deleted: boolean; device_id: string }>(`/api/device/${encodeURIComponent(deviceId)}`)).data;
}

export async function getApprovals() {
  return (await http.get<Device[]>('/api/device-approvals')).data;
}

export async function reviewApproval(deviceId: string, approve: boolean, name?: string, ownerUserId?: number) {
  return (await http.post<Device>(`/api/device-approvals/${encodeURIComponent(deviceId)}/${approve ? 'approve' : 'reject'}`, {
    name,
    owner_user_id: ownerUserId,
  })).data;
}

export async function getCommands(deviceId: string) {
  return (await http.get<Command[]>(`/api/device/${encodeURIComponent(deviceId)}/commands`)).data;
}

export async function sendCommand(deviceId: string, type: string, payload: Record<string, unknown>) {
  return (await http.post<Command>(`/api/device/${encodeURIComponent(deviceId)}/commands`, { type, payload })).data;
}

export async function createHostedPlayerSession(url: string, mediaType = '') {
  return (
    await http.post<HostedPlayerSession>('/api/player/sessions', {
      url,
      media_type: mediaType,
      title: '星幕',
    })
  ).data;
}

export async function getEvents(deviceId: string) {
  return (await http.get<DeviceEvent[]>(`/api/device/${encodeURIComponent(deviceId)}/events`)).data;
}

export async function getDeviceApps(deviceId: string) {
  return (await http.get<InstalledApp[]>(`/api/device/${encodeURIComponent(deviceId)}/apps`)).data;
}

export async function uploadApk(file: File, onProgress?: (percent: number) => void) {
  const form = new FormData();
  form.append('file', file);
  return (
    await http.post<ApkArtifact>('/api/upload/apk', form, {
      timeout: 0,
      onUploadProgress: (event) => {
        if (event.total) onProgress?.(Math.round((event.loaded / event.total) * 100));
      },
    })
  ).data;
}

export async function getReleases() {
  return (await http.get<ClientRelease[]>('/api/releases')).data;
}

export async function publishRelease(payload: ReleasePublishRequest) {
  return (await http.post<ClientRelease>('/api/releases', payload)).data;
}

export async function createP2PSession(deviceId: string, mode: 'auto' | 'relay' | 'webrtc' = 'auto') {
  return (
    await http.post<{
      session_id: string;
      requested_mode: string;
      selected_mode: string;
      direct_capable: boolean;
      selection_reason: string;
      relay_fallback: boolean;
      command: Command;
    }>(`/api/device/${encodeURIComponent(deviceId)}/p2p/session`, { mode })
  ).data;
}

export async function getSettings() {
  return (await http.get<ConsoleSettings>('/api/settings')).data;
}

export async function saveSettings(settings: ConsoleSettingsUpdate) {
  return (await http.put<ConsoleSettings>('/api/settings', settings)).data;
}

export async function testAiConnection(settings: AiConnectionTestRequest) {
  return (await http.post<AiConnectionTestResult>('/api/settings/ai/test', settings)).data;
}

export async function testEpayConnection(payload: { epay_base_url: string; epay_pid: string; epay_key?: string; use_stored_epay_key: boolean }) {
  return (await http.post<EpayConnectionTestResult>('/api/settings/payment/test', payload)).data;
}

export async function getSystemStatus() {
  return (await http.get<SystemStatus>('/api/system/status')).data;
}

export function readableError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    return String(error.response?.data?.detail || error.message || '请求失败');
  }
  return error instanceof Error ? error.message : '请求失败';
}
