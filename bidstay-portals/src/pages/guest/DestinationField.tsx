import { useEffect, useId, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { MapPin } from 'lucide-react';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';

export type Place = { label: string; city: string; area: string; kind: 'CITY' | 'NEIGHBOURHOOD' | 'REGION' };

export default function DestinationField({ city, onChange, onChoose, hourly = false }: {
  city: string; onChange: (city: string) => void; onChoose: (place: Place) => void; hourly?: boolean;
}) {
  const api = useApi();
  const { baseUrl } = useSession();
  const id = useId();
  const listId = id + '-cities';
  const hintId = id + '-hint';
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [debounced, setDebounced] = useState('');
  const prefix = city.trim();
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(prefix), 200);
    return () => window.clearTimeout(timer);
  }, [prefix]);

  const ready = debounced === prefix && prefix.length >= 2;
  const suggestions = useQuery({
    queryKey: ['destinations', baseUrl, debounced, hourly],
    queryFn: () => api.get<Place[]>('/api/v1/hotels/places?' + new URLSearchParams({
      prefix: debounced, hourly: String(hourly),
    })),
    enabled: open && ready,
    staleTime: 60_000,
    retry: false,
  });
  const cities = ready ? suggestions.data ?? [] : [];
  const listVisible = open && cities.length > 0;
  const activeId = listVisible && cities[active] ? listId + '-' + active : undefined;
  useEffect(() => {
    if (activeId) document.getElementById(activeId)?.scrollIntoView({ block: 'nearest' });
  }, [activeId]);
  const hint = prefix.length < 2 ? 'Type at least 2 letters to find a destination.'
    : !ready || suggestions.isPending ? 'Finding destinations…'
      : suggestions.isError ? 'Suggestions are unavailable. You can still enter a city.'
        : !cities.length ? 'No matching destinations with published stays.' : '';

  function choose(place: Place) {
    onChoose(place);
    setActive(-1);
    setOpen(false);
  }

  function handleKey(event: KeyboardEvent<HTMLInputElement>) {
    if (event.nativeEvent.isComposing) return;
    if (event.key === 'Escape') {
      setOpen(false);
      setActive(-1);
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
      if (cities.length) {
        setActive((index) => event.key === 'ArrowDown'
          ? (index + 1) % cities.length
          : (index <= 0 ? cities.length - 1 : index - 1));
      }
    } else if (event.key === 'Enter' && listVisible && cities[active]) {
      event.preventDefault();
      choose(cities[active]);
    }
  }

  return <div className="guest-destination">
    <label className={hourly ? 'field' : undefined} htmlFor={id}>
      <span><MapPin size={16} /> Destination</span>
      <input id={id} className={hourly ? 'input' : undefined} name="city" role="combobox"
        value={city} onChange={(event) => {
          onChange(event.target.value);
          setActive(-1);
          setOpen(true);
        }}
        onClick={() => setOpen(true)} onFocus={() => setOpen(true)}
        onBlur={() => { setOpen(false); setActive(-1); }} onKeyDown={handleKey}
        aria-autocomplete="list" aria-expanded={listVisible} aria-controls={listVisible ? listId : undefined}
        aria-activedescendant={activeId}
        aria-describedby={open && hint ? hintId : undefined}
        placeholder="City, region or neighbourhood" autoComplete="off" enterKeyHint="search"
        required pattern=".*\S.*" maxLength={182} />
    </label>
    {listVisible && <ul id={listId} className="guest-destination-popup" role="listbox" aria-label="Matching destinations">
      {cities.map((place, index) => <li key={place.kind + place.label} id={listId + '-' + index} role="option"
        aria-selected={index === active} onMouseDown={(event) => event.preventDefault()} onClick={() => choose(place)}>
        <MapPin size={15} /> {place.label}
      </li>)}
    </ul>}
    {open && hint && <p id={hintId} className="guest-destination-popup guest-destination-hint" role="status">{hint}</p>}
  </div>;
}
