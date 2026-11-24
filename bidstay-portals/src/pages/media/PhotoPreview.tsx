import { useEffect, useRef } from 'react';
import { ImageIcon } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';

export interface ReviewPhoto {
  id: number;
  hotelId: number;
  hotelName: string;
  roomTypeId?: number;
  roomName?: string;
  caption?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  reviewReason?: string;
  createdAt: string;
}

export default function PhotoPreview({ endpoint, alt }: { endpoint: string; alt: string }) {
  const api = useApi();
  const { cacheKey } = useSession();
  const ref = useRef<HTMLImageElement>(null);
  const photo = useQuery({
    queryKey: ['listing-preview', cacheKey, endpoint],
    queryFn: () => api.download(endpoint),
    gcTime: 0,
  });
  const bytes = photo.data;

  useEffect(() => {
    const image = ref.current;
    if (!image || !bytes) return;
    const url = URL.createObjectURL(bytes);
    image.src = url;
    return () => { image.removeAttribute('src'); URL.revokeObjectURL(url); };
  }, [bytes]);

  return <div className="listing-preview">
    {bytes ? <img ref={ref} alt={alt} /> : <span aria-hidden><ImageIcon size={25} /></span>}
    <ErrorBox error={photo.error} />
  </div>;
}
