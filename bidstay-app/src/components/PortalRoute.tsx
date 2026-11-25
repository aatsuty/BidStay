import { useLocalSearchParams, usePathname } from 'expo-router';
import { Platform } from 'react-native';
import PortalScreen from './PortalScreen';
import { portalOrigin, portalPath } from '@/lib/portal-url';

export default function PortalRoute() {
  const pathname = usePathname();
  const params = useLocalSearchParams();
  const origin = portalOrigin(Platform.OS === 'web' ? window.location.hostname : undefined);
  return <PortalScreen origin={origin} path={portalPath(pathname, params)} />;
}
