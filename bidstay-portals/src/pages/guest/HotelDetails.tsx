import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { BedDouble, Check, Images, MapPin, Plus, Users, X } from 'lucide-react';
import { ErrorBox } from '../../components/ui';
import { useApi } from '../../lib/api';
import { useSession } from '../../lib/session';
import type { StayPolicy } from '../../lib/types';
import { mediaUrl, rupees } from './models';

interface Photo { id: number; caption?: string }
interface Room { id: number; name: string; maxGuests: number; listedPrice: number; photos: Photo[] }
interface Hotel { id: number; name: string; city: string; area: string; starRating?: number; photos: Photo[]; rooms: Room[]; stayPolicy?: StayPolicy }
interface PublicReview { rating: number; comment?: string; createdAt: string }

export default function HotelDetails({ id, name, selection, bookingAction, close }: {
  id: number; name: string; selection?: { selected: boolean; toggle: () => void }; bookingAction?: ReactNode; close: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const api = useApi();
  const { baseUrl } = useSession();
  const [photoId, setPhotoId] = useState<number>();
  const hotel = useQuery({
    queryKey: ['hotel-details', baseUrl, id],
    queryFn: () => api.get<Hotel>('/api/v1/hotels/' + id),
    refetchOnWindowFocus: 'always',
  });
  const reviews = useQuery({
    queryKey: ['hotel-reviews', baseUrl, id],
    queryFn: () => api.get<PublicReview[]>(`/api/v1/hotels/${id}/reviews`),
    enabled: hotel.isSuccess,
  });
  useEffect(() => { ref.current?.showModal(); }, []);

  const photos = [...(hotel.data?.photos ?? []), ...(hotel.data?.rooms.flatMap((room) => room.photos) ?? [])];
  const photo = photos.find((image) => image.id === photoId) ?? photos[0];
  const label = (image: Photo) => image.caption ?? hotel.data?.rooms.find((room) => room.photos.some((p) => image.id === p.id))?.name ?? name;

  return <dialog ref={ref} className="guest-dialog guest-hotel-dialog" aria-labelledby="guest-hotel-title" onCancel={close}>
    <button type="button" className="guest-close" onClick={close} aria-label="Close hotel details"><X size={21} /></button>
    <h2 id="guest-hotel-title">{name}</h2>
    {hotel.data && <p className="guest-gallery-location"><MapPin size={15} /> {hotel.data.area}, {hotel.data.city}</p>}
    <ErrorBox error={hotel.error} />
    {hotel.isLoading && <p role="status">Loading hotel details…</p>}
    {hotel.data && <>
      {photo ? <>
        <figure className="guest-gallery-hero">
          <img src={mediaUrl(baseUrl, '/api/v1/media/' + photo.id + '/full')} alt={label(photo)} />
          {photo.caption && <figcaption>{photo.caption}</figcaption>}
        </figure>
        {photos.length > 1 && <div className="guest-gallery-thumbs" aria-label="Choose a photo">{photos.map((image, index) => (
          <button key={image.id} type="button" aria-label={`Photo ${index + 1} — ${label(image)}`} aria-pressed={image.id === photo.id} onClick={() => setPhotoId(image.id)}>
            <img src={mediaUrl(baseUrl, '/api/v1/media/' + image.id + '/thumb')} alt="" loading="lazy" />
          </button>
        ))}</div>}
      </> : <div className="guest-gallery-empty"><Images size={26} /> Approved photos are coming soon</div>}
      <h3 className="guest-gallery-heading">Room types</h3>
      <div className="guest-room-list">{hotel.data.rooms.map((room) => (
        <article key={room.id} className="guest-room">
          {room.photos[0] ? <button className="guest-room-picture" type="button" aria-label={`View ${room.name} photo`} onClick={() => { setPhotoId(room.photos[0].id); ref.current?.scrollTo({ top: 0, behavior: 'instant' }); }}>
            <img src={mediaUrl(baseUrl, '/api/v1/media/' + room.photos[0].id + '/thumb')} alt={room.name} loading="lazy" />
          </button> : <span className="guest-hotel-icon"><BedDouble size={19} /></span>}
          <div><h4>{room.name}</h4><p><Users size={13} /> Up to {room.maxGuests} guests</p></div>
          <div className="guest-room-rate"><strong>{rupees.format(room.listedPrice)}</strong><span>Listed per night</span></div>
        </article>
      ))}</div>
      {hotel.data.stayPolicy && <section className="guest-stay-terms">
        <h3>Stay policy</h3>
        <p>Check-in from {hotel.data.stayPolicy.checkInFrom} IST · Check-out by {hotel.data.stayPolicy.checkOutBy} IST</p>
        {hotel.data.stayPolicy.guestInstructions && <p className="guest-stay-instructions">{hotel.data.stayPolicy.guestInstructions}</p>}
      </section>}
      <section className="guest-gallery-reviews">
        <h3>Verified guest reviews</h3>
        <ErrorBox error={reviews.error} />
        {reviews.isSuccess && reviews.data.length === 0 && <p>No published reviews yet.</p>}
        {reviews.data?.map((review, index) => <article key={review.createdAt + ':' + index}>
          <strong>{review.rating} / 5</strong>
          {review.comment && <p>{review.comment}</p>}
        </article>)}
      </section>
      <div className="guest-listing-actions">
        {selection && <button className="guest-quiet" type="button" onClick={() => { selection.toggle(); close(); }}>
          {selection.selected ? <Check size={16} /> : <Plus size={16} />}{selection.selected ? 'Remove hotel from bid' : 'Add hotel to bid'}
        </button>}
        {bookingAction}
      </div>
    </>}
  </dialog>;
}
