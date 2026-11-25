import { BedDouble, Clock3 } from 'lucide-react';
import { NavLink } from 'react-router-dom';

export default function StayTypeNav({ hourlyUrl = '/hourly', overnightUrl = '/' }: {
  hourlyUrl?: string; overnightUrl?: string;
}) {
  return <nav className="guest-switch guest-stay-switch" aria-label="Stay type">
    <NavLink to={overnightUrl} end><BedDouble size={17} /> Overnight</NavLink>
    <NavLink to={hourlyUrl}><Clock3 size={17} /> Hourly</NavLink>
  </nav>;
}
