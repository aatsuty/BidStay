import { createContext, useContext } from 'react';
import type { MyHotel } from '../../lib/types';

export interface HotelCtx { hotel: MyHotel; hotels: MyHotel[]; select: (id: number) => void }
export const HotelContext = createContext<HotelCtx | null>(null);

export function useHotel(): HotelCtx {
  const c = useContext(HotelContext);
  if (!c) throw new Error('useHotel must be used inside the hotel portal');
  return c;
}
