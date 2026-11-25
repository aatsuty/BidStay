import { useEffect } from 'react';
import PortalStatus, { missingPortal } from './PortalStatus';

export default function PortalScreen({ origin, path }: { origin?: string; path: string }) {
  useEffect(() => {
    if (origin && window.location.origin !== origin) window.location.replace(origin + path);
  }, [origin, path]);

  return <PortalStatus title="Open BidStay" detail={origin ? 'Opening the shared BidStay portal…' : missingPortal} />;
}
