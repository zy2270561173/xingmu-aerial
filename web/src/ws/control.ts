import type { RealtimeMessage } from '../types';
import { TOKEN_KEY } from '../api/http';

export class ControlSocket {
  private socket?: WebSocket;
  private stopped = false;
  private authenticated = false;
  private retries = 0;
  private timer?: number;
  private pageSuspended = false;
  private listenersAttached = false;
  private readonly handlePageHide = () => this.suspendForPageCache();
  private readonly handlePageShow = () => {
    if (this.stopped || !this.pageSuspended) return;
    this.pageSuspended = false;
    this.connect();
  };

  constructor(
    private readonly scope: string,
    private readonly onMessage: (message: RealtimeMessage) => void,
    private readonly onState: (connected: boolean) => void = () => undefined,
    private readonly onJpeg: (jpeg: ArrayBuffer) => void = () => undefined,
  ) {
    this.attachPageLifecycle();
  }

  connect() {
    this.stopped = false;
    this.attachPageLifecycle();
    if (this.pageSuspended) return;
    if (this.socket?.readyState === WebSocket.OPEN
      || this.socket?.readyState === WebSocket.CONNECTING) return;
    if (this.timer) window.clearTimeout(this.timer);
    this.timer = undefined;
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) return;
    const configured = import.meta.env.VITE_WS_BASE as string | undefined;
    const base = configured || `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}`;
    const socket = new WebSocket(`${base}/ws/control/${encodeURIComponent(this.scope)}`);
    this.socket = socket;
    socket.binaryType = 'arraybuffer';
    socket.addEventListener('open', () => {
      if (this.socket !== socket || this.pageSuspended || this.stopped) {
        socket.close(1000, 'page unavailable');
        return;
      }
      this.retries = 0;
      socket.send(JSON.stringify({ type: 'auth', token }));
    });
    socket.addEventListener('message', (event) => {
      if (this.socket !== socket || this.pageSuspended || this.stopped) return;
      if (event.data instanceof ArrayBuffer) {
        this.onJpeg(event.data);
        return;
      }
      try {
        const message = JSON.parse(event.data) as RealtimeMessage;
        if (message.type === 'authenticated') {
          this.authenticated = true;
          this.onState(true);
        }
        this.onMessage(message);
      } catch {
        // Ignore malformed frames; the REST refresh remains authoritative.
      }
    });
    socket.addEventListener('close', () => {
      if (this.socket !== socket) return;
      this.socket = undefined;
      this.authenticated = false;
      this.onState(false);
      this.scheduleReconnect();
    });
    socket.addEventListener('error', () => socket.close());
  }

  close() {
    this.stopped = true;
    this.pageSuspended = false;
    this.authenticated = false;
    this.onState(false);
    if (this.timer) window.clearTimeout(this.timer);
    this.timer = undefined;
    const socket = this.socket;
    this.socket = undefined;
    socket?.close(1000, 'client closed');
    this.detachPageLifecycle();
  }

  requestSnapshot() {
    if (!this.authenticated || this.socket?.readyState !== WebSocket.OPEN) return false;
    return this.socket.send(JSON.stringify({ type: 'snapshot_request' })) === undefined;
  }

  startPreview(fps = 10) {
    if (!this.authenticated || this.socket?.readyState !== WebSocket.OPEN) return false;
    this.socket.send(JSON.stringify({ type: 'preview_start', fps: Math.max(1, Math.min(15, fps)) }));
    return true;
  }

  stopPreview() {
    if (!this.authenticated || this.socket?.readyState !== WebSocket.OPEN) return false;
    this.socket.send(JSON.stringify({ type: 'preview_stop' }));
    return true;
  }

  private scheduleReconnect() {
    if (this.stopped || this.pageSuspended) return;
    const delay = Math.min(30_000, 1000 * 2 ** this.retries++);
    this.timer = window.setTimeout(() => this.connect(), delay);
  }

  private suspendForPageCache() {
    if (this.stopped) return;
    this.pageSuspended = true;
    this.authenticated = false;
    this.onState(false);
    if (this.timer) window.clearTimeout(this.timer);
    this.timer = undefined;
    const socket = this.socket;
    this.socket = undefined;
    socket?.close(1000, 'page hidden');
  }

  private attachPageLifecycle() {
    if (this.listenersAttached) return;
    window.addEventListener('pagehide', this.handlePageHide);
    window.addEventListener('pageshow', this.handlePageShow);
    this.listenersAttached = true;
  }

  private detachPageLifecycle() {
    if (!this.listenersAttached) return;
    window.removeEventListener('pagehide', this.handlePageHide);
    window.removeEventListener('pageshow', this.handlePageShow);
    this.listenersAttached = false;
  }
}
