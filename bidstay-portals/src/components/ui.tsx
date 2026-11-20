import type { ReactNode } from 'react';
import type { HotelStatus } from '../lib/types';

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  return <div className="error">{error instanceof Error ? error.message : String(error)}</div>;
}

export const Loading = () => <p className="muted">Loading…</p>;

export function PageHead({ title, sub, children }: { title: string; sub?: string; children?: ReactNode }) {
  return (
    <div className="page-head row spread wrap">
      <div><h1>{title}</h1>{sub && <div className="muted">{sub}</div>}</div>
      {children}
    </div>
  );
}

const STATUS: Record<HotelStatus, [string, string]> = {
  ACTIVE: ['Live', 'ok'],
  PENDING_VERIFICATION: ['Awaiting verification', 'warn'],
  REJECTED: ['Not approved', 'bad'],
  SUSPENDED: ['Suspended', 'bad'],
};
export function HotelStatusBadge({ status }: { status: HotelStatus }) {
  const [label, tone] = STATUS[status];
  return <span className={`badge ${tone}`}>{label}</span>;
}

export const DOC_LABEL: Record<string, string> = { GST: 'GST registration', LICENCE: 'Hotel licence', OWNER_ID: 'Owner ID', PHOTOS: 'Property photos' };
