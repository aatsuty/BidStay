import { lazy, Suspense, useState } from 'react';
import { parsePin, type PinDraft } from './map-pin';

const LocationPinMap = lazy(() => import('./LocationPinMap'));

export default function LocationPin({ value, onChange }: { value: PinDraft; onChange: (value: PinDraft) => void }) {
  const [showMap, setShowMap] = useState(false);
  const pin = parsePin(value);
  return <div className="stack">
    <p className="muted small">Set a pin at the hotel entrance. Guests see an approximate location.</p>
    <div className="row wrap">
      <label className="field grow">Latitude<input className="input" type="number" step="any" min={-90} max={90}
        value={value.latitude} onChange={(e) => onChange({ ...value, latitude: e.target.value })} /></label>
      <label className="field grow">Longitude<input className="input" type="number" step="any" min={-180} max={180}
        value={value.longitude} onChange={(e) => onChange({ ...value, longitude: e.target.value })} /></label>
    </div>
    {!pin && <p className="muted small">Enter both coordinates in range to save a pin.</p>}
    <button className="btn ghost sm" type="button" aria-expanded={showMap}
      onClick={() => setShowMap(!showMap)}>{showMap ? 'Hide map' : 'Choose on map'}</button>
    {showMap && <Suspense fallback={<p role="status">Loading map…</p>}>
      <LocationPinMap latitude={pin?.latitude} longitude={pin?.longitude}
        onPick={(lat, lng) => onChange({ latitude: lat.toFixed(6), longitude: lng.toFixed(6) })} />
    </Suspense>}
  </div>;
}
