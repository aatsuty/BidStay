import Constants from 'expo-constants';

const ORIGIN = /^(https?):\/\/(\[[\da-f:]+\]|[a-z\d.-]+)(?::(\d{1,5}))?(?=\/|\?|#|$)/i;

export function httpOrigin(url: string) {
  const match = url.match(ORIGIN);
  if (!match) return undefined;
  const [, scheme, hostname, port] = match;
  if (port && (Number(port) < 1 || Number(port) > 65535)) return undefined;
  const protocol = scheme.toLowerCase();
  const defaultPort = protocol === 'https' ? 443 : 80;
  return protocol + '://' + hostname.toLowerCase() + (port && Number(port) !== defaultPort ? ':' + Number(port) : '');
}

function localHost(host: string) {
  return /^(?:localhost|[\da-z-]+\.local|127(?:\.\d{1,3}){3}|10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2}|\[::1\])$/i.test(host);
}

export function portalOrigin(browserHost?: string) {
  const configured = process.env.EXPO_PUBLIC_PORTAL_URL?.trim().replace(/\/$/, '');
  if (configured) {
    const origin = httpOrigin(configured);
    const match = configured.match(ORIGIN);
    if (origin && match?.[0] === configured && (origin.startsWith('https://') || (__DEV__ && localHost(match[2])))) {
      return origin;
    }
    return undefined;
  }
  if (!__DEV__) return undefined;
  const hostUri = Constants.expoConfig?.hostUri;
  const host = browserHost ?? hostUri?.match(/^(\[[\da-f:]+\]|[a-z\d.-]+)(?::\d+)?$/i)?.[1];
  return host && localHost(host) ? `http://${host}:8080` : undefined;
}

export function portalPath(pathname: string, params: Record<string, string | string[] | undefined>) {
  let path = pathname;
  const legacy = pathname.match(/^\/(bid|booking)\/(\d+)$/);
  if (legacy) path = (legacy[1] === 'bid' ? '/offers/' : '/checkout/') + legacy[2];
  if (path === '/hotels') path = '/';
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (key === 'route') continue;
    for (const item of Array.isArray(value) ? value : value == null ? [] : [value]) query.append(key, item);
  }
  if (pathname === '/profile') {
    path = '/';
    query.set('signIn', '1');
  }
  const search = query.toString();
  return path + (search ? '?' + search : '');
}
