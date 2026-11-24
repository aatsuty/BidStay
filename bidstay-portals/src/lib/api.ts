import { useMemo } from 'react';
import { useSession } from './session';

export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

export function make(baseUrl: string, userId: number | null, token?: string) {
  async function request<T>(method: string, path: string, body?: unknown, binary = false): Promise<T> {
    let res: Response;
    const upload = body instanceof FormData;
    try {
      res = await fetch(baseUrl.replace(/\/$/, '') + path, {
        method,
        headers: { ...(upload ? {} : { 'Content-Type': 'application/json' }), Accept: binary ? '*/*' : 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : import.meta.env.DEV && userId ? { 'X-User-Id': String(userId) } : {}) },
        body: upload ? body : body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      throw new ApiError(`Can't reach the server${baseUrl ? ` at ${baseUrl}` : ''}. Is the backend running?`, 0);
    }
    if (res.ok && binary) return await res.blob() as T;
    const text = await res.text();
    let data: unknown;
    try { data = text ? JSON.parse(text) : undefined; } catch { data = undefined; }
    if (!res.ok) throw new ApiError((data as { detail?: string } | undefined)?.detail ?? `Request failed (${res.status})`, res.status);
    return data as T;
  }
  return {
    get: <T,>(p: string) => request<T>('GET', p),
    post: <T,>(p: string, b?: unknown) => request<T>('POST', p, b ?? {}),
    put: <T,>(p: string, b?: unknown) => request<T>('PUT', p, b ?? {}),
    download: (p: string) => request<Blob>('GET', p, undefined, true),
  };
}

export type Api = ReturnType<typeof make>;

export function useApi(): Api {
  const { baseUrl, userId, token } = useSession();
  return useMemo(() => make(baseUrl, userId, token), [baseUrl, userId, token]);
}

export const errMsg = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong');
