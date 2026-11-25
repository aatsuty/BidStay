import { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { rupees, type HotelResult } from './models';
import type { MapBounds } from './useMapSearch';

const WRAP = (longitude: number) => ((longitude + 180) % 360 + 360) % 360 - 180;

export default function HotelMap({ hotels, selected, toggle, onSearchArea, appliedBounds }: {
  hotels: Pick<HotelResult, 'id' | 'name' | 'latitude' | 'longitude' | 'listedPrice'>[];
  selected?: ReadonlySet<number>;
  toggle: (id: number) => void;
  onSearchArea: (bounds: MapBounds) => void;
  appliedBounds?: MapBounds;
}) {
  const root = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const markers = useRef<L.LayerGroup | null>(null);
  const latestToggle = useRef(toggle);
  const lastHotels = useRef<typeof hotels | null>(null);
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
      const active = selected?.has(hotel.id);
      const price = rupees.format(hotel.listedPrice);
      L.marker(point, {
        icon: L.divIcon({ className: 'guest-map-marker', html: `<span class="${active ? 'active' : ''}">${price}</span>`, iconSize: [60, 32], iconAnchor: [30, 16] }),
        title: hotel.name + ' ' + price,
        alt: hotel.name,
        zIndexOffset: active ? 50 : 0,
      }).on('click', () => latestToggle.current(hotel.id)).addTo(layer);
    }
    if (!lastHotels.current && appliedBounds) {
      const west = Number(appliedBounds.west);
      const east = Number(appliedBounds.east);
      map.current?.fitBounds([[Number(appliedBounds.south), west], [Number(appliedBounds.north), east < west ? east + 360 : east]],
        { animate: false });
    } else if (positions.length && !appliedBounds && lastHotels.current !== hotels) {
      map.current?.fitBounds(L.latLngBounds(positions), { padding: [45, 45], maxZoom: 13, animate: false });
    }
    lastHotels.current = hotels;
  }, [hotels, selected, appliedBounds]);

  function searchArea() {
    if (!map.current || !lastHotels.current) return;
    const bounds = map.current.getBounds();
    const fullWidth = bounds.getEast() - bounds.getWest() >= 360;
    onSearchArea({
      south: Math.max(-90, bounds.getSouth()).toFixed(5),
      north: Math.min(90, bounds.getNorth()).toFixed(5),
      west: (fullWidth ? -180 : WRAP(bounds.getWest())).toFixed(5),
      east: (fullWidth ? 180 : WRAP(bounds.getEast())).toFixed(5),
    });
  }

  return <div className="guest-map-frame">
    <div className="guest-map" ref={root} aria-label="Map of approximate hotel locations" />
    <button type="button" className="guest-quiet guest-map-search" onClick={searchArea}>Search this area</button>
    {tilesUnavailable && <p className="guest-map-notice" role="status">Map background unavailable. You can still select hotels from the list.</p>}
  </div>;
}
