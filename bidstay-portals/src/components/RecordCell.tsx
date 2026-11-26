import type { ReactNode } from 'react';

export default function RecordCell({ label, className, children }: { label: string; className?: string; children: ReactNode }) {
  return <td role="cell" className={className}>
    <span className="record-cell-label" aria-hidden="true">{label}</span>
    <div>{children}</div>
  </td>;
}
