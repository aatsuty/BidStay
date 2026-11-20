import { useMemo } from 'react';
import { useSession } from './session';

export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

function make(baseUrl: string, userId: number | null) {
  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    let res: Response;
    try {
      res = await fetch(baseUrl.replace(/\/$/, '') + path, {
        method,
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...(userId ? { 'X-User-Id': String(userId) } : {}) },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      throw new ApiError(`Can't reach the server at ${baseUrl}. Is the backend running?`, 0);
    }
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
  };
}

export type Api = ReturnType<typeof make>;

export function useApi(): Api {
  const { baseUrl, userId } = useSession();
  return useMemo(() => make(baseUrl, userId), [baseUrl, userId]);
}

export const errMsg = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong');
