type DeviceAction = { action: 'copy'; text: string }
  | { action: 'print'; text: string; html?: string }
  | { action: 'location' }
  | { action: 'share'; name: string; base64: string };
type DeviceResponse<T> = { id: number; result: T; error?: string };

declare global {
  interface Window { ReactNativeWebView?: { postMessage: (message: string) => void } }
}

let nextId = Date.now();

export function deviceRequest<T>(message: DeviceAction): Promise<T> | undefined {
  const bridge = window.ReactNativeWebView;
  if (!bridge) return undefined;
  const id = ++nextId;
  const eventName = 'bidstay:device-response';
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      window.removeEventListener(eventName, onResponse);
      window.clearTimeout(timer);
    };
    function onResponse(event: Event) {
      const response = (event as CustomEvent<DeviceResponse<T>>).detail;
      if (response?.id !== id) return;
      cleanup();
      if (response.error) reject(new Error(response.error));
      else resolve(response.result);
    }
    const timer = window.setTimeout(() => {
      cleanup();
      reject(new Error('Your device did not finish the action.'));
    }, 120_000);
    window.addEventListener(eventName, onResponse);
    try {
      bridge.postMessage(JSON.stringify({ ...message, id }));
    } catch (error) {
      cleanup();
      reject(error);
    }
  });
}

export async function downloadDocument(bytes: Blob, name: string) {
  if (window.ReactNativeWebView) {
    if (bytes.size > 5 * 1024 * 1024) throw new Error('This document is too large to share.');
    const base64 = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error('Could not read the document.'));
      reader.onload = () => resolve(String(reader.result).split(',')[1]);
      reader.readAsDataURL(bytes);
    });
    const extension = name.match(/\.(pdf|png|jpe?g)$/i)?.[0]
      ?? (bytes.type === 'image/png' ? '.png' : bytes.type === 'image/jpeg' ? '.jpg' : '.pdf');
    const stem = name.replace(/\.[^.]+$/, '').replace(/[^\p{L}\p{N}._ -]/gu, '_').slice(0, 175) || 'document';
    await deviceRequest({ action: 'share', name: stem + extension, base64 });
    return;
  }
  const url = URL.createObjectURL(bytes);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
