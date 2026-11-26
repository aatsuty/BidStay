import { createContext, useContext } from 'react';
import type { MyHotel } from '../../lib/types';

interface HotelCtx { hotel?: MyHotel; hotels: MyHotel[]; select: (id: number) => void }
export const HotelContext = createContext<HotelCtx | null>(null);

export function useHotel() {
  const c = useContext(HotelContext);
  if (!c?.hotel) throw new Error('useHotel requires an active hotel');
  return { ...c, hotel: c.hotel };
}
