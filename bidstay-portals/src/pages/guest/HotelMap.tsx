import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { rupees, type HotelResult } from './models';

export default function HotelMap({ hotels, selected, toggle }: {
  hotels: HotelResult[];
  selected: ReadonlySet<number>;
  toggle: (id: number) => void;
}) {
  const root = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const markers = useRef<L.LayerGroup | null>(null);
  const latestToggle = useRef(toggle);
  const lastHotels = useRef<HotelResult[] | null>(null);
  const [tilesUnavailable, setTilesUnavailable] = useState(false);

  useEffect(() => { latestToggle.current = toggle; }, [toggle]);

  useEffect(() => {
    if (!root.current) return;
    const instance = L.map(root.current, { scrollWheelZoom: false, zoomControl: false });
    L.control.zoom({ position: 'bottomright' }).addTo(instance);
    L.tileLayer(import.meta.env.VITE_MAP_TILE_URL ?? 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: import.meta.env.VITE_MAP_ATTRIBUTION ?? '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 18,
    }).on('tileerror', () => setTilesUnavailable(true))
      .on('tileload', () => setTilesUnavailable(false))
      .addTo(instance);
    markers.current = L.layerGroup().addTo(instance);
    map.current = instance;
    const resize = new ResizeObserver(() => instance.invalidateSize({ pan: false }));
    resize.observe(root.current);
    return () => {
      resize.disconnect();
      instance.remove();
      lastHotels.current = null;
      map.current = null;
      markers.current = null;
    };
  }, []);

  useEffect(() => {
    const layer = markers.current;
    if (!layer) return;
    layer.clearLayers();
    const positions: L.LatLngTuple[] = [];
    for (const hotel of hotels) {
      if (hotel.latitude == null || hotel.longitude == null) continue;
      const point: L.LatLngTuple = [hotel.latitude, hotel.longitude];
      positions.push(point);
      const active = selected.has(hotel.id);
      const price = rupees.format(hotel.listedPrice);
      L.marker(point, {
        icon: L.divIcon({ className: 'guest-map-marker', html: `<span class="${active ? 'active' : ''}">${price}</span>`, iconSize: [60, 32], iconAnchor: [30, 16] }),
        title: hotel.name + ' ' + price,
        alt: hotel.name,
        zIndexOffset: active ? 50 : 0,
      }).on('click', () => latestToggle.current(hotel.id)).addTo(layer);
    }
    if (lastHotels.current !== hotels && positions.length) {
      map.current?.fitBounds(L.latLngBounds(positions), { padding: [45, 45], maxZoom: 13, animate: false });
    }
    lastHotels.current = hotels;
  }, [hotels, selected]);

  return <div className="guest-map-frame">
    <div className="guest-map" ref={root} aria-label="Map of approximate hotel locations" />
    {tilesUnavailable && <p className="guest-map-notice" role="status">Map background unavailable. You can still select hotels from the list.</p>}
  </div>;
}
