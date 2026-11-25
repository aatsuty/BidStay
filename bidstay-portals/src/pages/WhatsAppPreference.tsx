import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MessageCircle } from 'lucide-react';
import { ErrorBox } from '../components/ui';
import { useApi } from '../lib/api';
import { useSession } from '../lib/session';

interface Preference { available: boolean; enabled: boolean }

export default function WhatsAppPreference({ endpoint, label, compact = false }: {
  endpoint: string;
  label: string;
  compact?: boolean;
}) {
  const api = useApi();
  const client = useQueryClient();
  const { token, cacheKey } = useSession();
  const key = ['whatsapp-consent', cacheKey, endpoint];
  const preference = useQuery({
    queryKey: key, enabled: !!token,
    queryFn: () => api.get<Preference>(endpoint),
  });
  const change = useMutation({
    mutationFn: (enabled: boolean) => api.put<Preference>(endpoint, { enabled }),
    onSuccess: (current) => client.setQueryData(key, current),
  });

  if (!token || !preference.data || (!preference.data.available && !preference.data.enabled)) return null;
  return <div className={compact ? 'wa-consent compact' : 'wa-consent card'}>
    <label title={label}>
      <MessageCircle size={16} />
      <span>{label}</span>
      <input type="checkbox" checked={preference.data.enabled}
        disabled={change.isPending} onChange={(event) => change.mutate(event.target.checked)} />
    </label>
    <ErrorBox error={change.error} />
  </div>;
}
