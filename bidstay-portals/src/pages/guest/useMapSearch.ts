import { useRef, useState } from 'react';
import { deviceRequest } from '../../lib/portal-device';

export type MapBounds = { south: string; west: string; north: string; east: string };
type NearbyPoint = { latitude: string; longitude: string };

export function useMapSearch(onChange?: () => void) {
  const [bounds, setBounds] = useState<MapBounds>();
  const [nearby, setNearby] = useState<NearbyPoint>();
  const [locating, setLocating] = useState(false);
  const [error, setError] = useState('');
  const generation = useRef(0);

  function toggleNearby() {
    setError('');
    if (nearby) {
      setNearby(undefined);
      onChange?.();
      return;
    }
    setLocating(true);
    const active = ++generation.current;
    function applyLocation(coords: NearbyPoint) {
      if (active !== generation.current) return;
      setLocating(false);
      setNearby(coords);
      onChange?.();
    }
    function unavailable() {
      if (active !== generation.current) return;
      setLocating(false);
      setError('Location is unavailable. Choose a neighbourhood instead.');
    }
    const onDevice = deviceRequest<NearbyPoint>({ action: 'location' });
    if (onDevice) void onDevice.then(applyLocation, unavailable);
    else if (navigator.geolocation) navigator.geolocation.getCurrentPosition(({ coords }) => applyLocation({
      latitude: coords.latitude.toFixed(2), longitude: coords.longitude.toFixed(2),
    }), unavailable, { timeout: 8000, maximumAge: 600_000 });
    else unavailable();
  }

  return {
    bounds, nearby, locating, error, toggleNearby,
    changeBounds(next?: MapBounds) { setBounds(next); onChange?.(); },
    reset() {
      generation.current++;
      setLocating(false);
      setBounds(undefined);
      setNearby(undefined);
      setError('');
    },
  };
}
