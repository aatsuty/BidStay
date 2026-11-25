import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, BackHandler, Linking, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import WebView, { type WebViewMessageEvent } from 'react-native-webview';
import { httpOrigin } from '@/lib/portal-url';
import { handleDeviceAction } from '@/lib/portal-device';
import PortalStatus, { missingPortal } from './PortalStatus';

export default function PortalScreen({ origin, path }: { origin?: string; path: string }) {
  const web = useRef<WebView<object>>(null);
  const pageUrl = useRef('');
  const canGoBack = useRef(false);
  const [failed, setFailed] = useState(false);
  const source = useMemo(() => ({ uri: (origin ?? '') + path }), [origin, path]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (!web.current || !canGoBack.current) return false;
      web.current.goBack();
      return true;
    });
    return () => subscription.remove();
  }, []);

  if (!origin) return <PortalStatus title="Open BidStay" detail={missingPortal} />;

  function openExternal(url: string) {
    const target = httpOrigin(url);
    if (target?.startsWith('https://')) {
      void Linking.openURL(url).catch(() => Alert.alert('Could not open this link'));
    }
  }

  async function onMessage({ nativeEvent }: WebViewMessageEvent) {
    if (httpOrigin(nativeEvent.url) !== origin || httpOrigin(pageUrl.current) !== origin
      || nativeEvent.data.length > 7_100_000) return;
    let message: Record<string, unknown>;
    try {
      const value: unknown = JSON.parse(nativeEvent.data);
      if (!value || typeof value !== 'object') return;
      message = value as Record<string, unknown>;
    } catch {
      return;
    }
    if (message.action === 'history' && typeof message.canGoBack === 'boolean') {
      canGoBack.current = message.canGoBack;
      return;
    }
    if (!Number.isSafeInteger(message.id)) return;
    let reply: object;
    try {
      const result = await handleDeviceAction(message);
      reply = { id: message.id, result };
    } catch {
      reply = { id: message.id, error: 'This action could not be completed on your device.' };
    }
    if (httpOrigin(pageUrl.current) === origin) {
      web.current?.injectJavaScript(`window.dispatchEvent(new CustomEvent('bidstay:device-response', { detail: ${JSON.stringify(reply)} })); true;`);
    }
  }

  return <SafeAreaView style={styles.root}>
    {failed ? <PortalStatus title="Could not reach BidStay" detail={origin} retry={() => setFailed(false)} /> :
      <WebView<object>
        ref={web}
        source={source}
        originWhitelist={['*']}
        onShouldStartLoadWithRequest={(request) => {
          if (httpOrigin(request.url) === origin) return true;
          if (request.isTopFrame !== false) openExternal(request.url);
          return false;
        }}
        onOpenWindow={({ nativeEvent: { targetUrl } }) => openExternal(targetUrl)}
        onLoadStart={({ nativeEvent }) => { pageUrl.current = nativeEvent.url; }}
        onNavigationStateChange={(state) => { canGoBack.current = state.canGoBack; }}
        onMessage={(event) => { void onMessage(event); }}
        onError={() => setFailed(true)}
        onHttpError={({ nativeEvent }) => {
          if (nativeEvent.url === pageUrl.current) setFailed(true);
        }}
        onContentProcessDidTerminate={() => setFailed(true)}
        onRenderProcessGone={() => setFailed(true)}
        allowsBackForwardNavigationGestures
        allowFileAccess={false}
        mixedContentMode="never"
        webviewDebuggingEnabled={__DEV__}
      />}
  </SafeAreaView>;
}

const styles = StyleSheet.create({ root: { flex: 1, backgroundColor: '#f5f8ff' } });
