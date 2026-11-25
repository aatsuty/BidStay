export type PinDraft = { latitude: string; longitude: string };
export type Pin = { latitude: number | null; longitude: number | null };
export const emptyPin: PinDraft = { latitude: '', longitude: '' };

export function parsePin(draft: PinDraft): Pin | undefined {
  const { latitude, longitude } = draft;
  if (!latitude && !longitude) return { latitude: null, longitude: null };
  if (!latitude || !longitude) return undefined;
  const lat = Number(latitude);
  const lng = Number(longitude);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return undefined;
  return { latitude: lat, longitude: lng };
}
