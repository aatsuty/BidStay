import { useMemo } from 'react';
import { useSession } from './session';

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

interface Options {
  baseUrl: string;
  userId?: number;
  token?: string;
}

function createApi({ baseUrl, userId, token }: Options) {
  async function request<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15_000);
    try {
      const res = await fetch(baseUrl.replace(/\/$/, '') + path, {
        method,
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(!token && userId ? { 'X-User-Id': String(userId) } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      });

      const text = await res.text();
      const data = text ? safeJson(text) : undefined;
      if (!res.ok) {
        // Spring ProblemDetail: { title, status, detail }
        const detail = (data as { detail?: string } | undefined)?.detail;
        throw new ApiError(detail ?? `Request failed (${res.status})`, res.status);
      }
      return data as T;
    } catch (e) {
      if (e instanceof ApiError) throw e;
      throw new ApiError(`Can't reach the server at ${baseUrl}. Is the backend running?`, 0);
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    get: <T>(path: string) => request<T>('GET', path),
    post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  };
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export type Api = ReturnType<typeof createApi>;

export function useApi(): Api {
  const { baseUrl, userId, auth } = useSession();
  const token = auth?.token;
  return useMemo(() => createApi({ baseUrl, userId: __DEV__ ? userId : undefined, token }), [baseUrl, userId, token]);
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : 'Something went wrong';
}
