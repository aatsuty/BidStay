import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';

export default function LocationPinMap({ latitude, longitude, onPick }: {
  latitude?: number | null; longitude?: number | null; onPick: (latitude: number, longitude: number) => void;
}) {
  const root = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const pin = useRef<L.CircleMarker | null>(null);
  const latestPick = useRef(onPick);

  useEffect(() => { latestPick.current = onPick; }, [onPick]);
  useEffect(() => {
    if (!root.current) return;
    const instance = L.map(root.current, { scrollWheelZoom: false }).setView([22, 79], 5);
    map.current = instance;
    L.tileLayer(import.meta.env.VITE_MAP_TILE_URL ?? 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: import.meta.env.VITE_MAP_ATTRIBUTION ?? '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 18,
    }).addTo(instance);
    instance.on('click', ({ latlng }) => {
      const point = latlng.wrap();
      latestPick.current(point.lat, point.lng);
    });
    return () => {
      instance.remove();
      map.current = null;
      pin.current = null;
    };
  }, []);
  useEffect(() => {
    const instance = map.current;
    if (!instance) return;
    pin.current?.remove();
    if (latitude == null || longitude == null) return;
    const point: L.LatLngTuple = [latitude, longitude];
    pin.current = L.circleMarker(point, { radius: 9 }).addTo(instance);
    if (instance.getZoom() < 13 || !instance.getBounds().contains(point)) {
      instance.setView(point, Math.max(instance.getZoom(), 13));
    }
  }, [latitude, longitude]);

  return <div ref={root} aria-label="Click the map to set the hotel pin"
    style={{ height: 310, borderRadius: 10, zIndex: 0 }} />;
}
