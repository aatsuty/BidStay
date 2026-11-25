import { useEffect, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { NavLink, Outlet, useLocation, useNavigate, useOutletContext } from 'react-router-dom';
import { BedDouble, ChevronRight, CircleUserRound, LogOut, type LucideIcon } from 'lucide-react';
import { useApi } from '../lib/api';
import { useSession } from '../lib/session';
import { PageHead } from './ui';
import './portal.css';

export type PortalLink = {
  to: string;
  label: string;
  shortLabel?: string;
  icon: LucideIcon;
  count?: number;
  end?: boolean;
};

type PortalAccount = {
  name?: string;
  role: string;
  links: PortalLink[];
  signOut: () => void;
};

function NavigationLink({ item, sectionActive = false }: { item: PortalLink; sectionActive?: boolean }) {
  const { to, label, shortLabel, icon: Icon, count, end } = item;
  return <NavLink to={to} end={end} aria-label={label}
    className={({ isActive }) => 'portal-link' + (isActive || sectionActive ? ' active' : '')}>
    <Icon size={19} aria-hidden="true" /><span>{shortLabel ?? label}</span>
    {!!count && <span className="portal-count" aria-hidden="true">{count}</span>}
  </NavLink>;
}

export default function PortalLayout({ role, name, links, tabs, settings, accountPath, header, children }: {
  role: string;
  name?: string;
  links: PortalLink[];
  tabs: PortalLink[];
  settings: PortalLink[];
  accountPath: string;
  header?: ReactNode;
  children?: ReactNode;
}) {
  const api = useApi();
  const nav = useNavigate();
  const { pathname } = useLocation();
  const { token, signOut } = useSession();
  const qc = useQueryClient();
  const account = { to: accountPath, label: 'Account', icon: CircleUserRound };
  const home = links[0]?.to ?? accountPath;
  const current = [...links, account].find((item) => pathname === item.to);
  const inSettings = settings.some((item) => pathname === item.to);
  useEffect(() => { window.scrollTo(0, 0); }, [pathname]);

  async function leave() {
    try {
      if (token) await api.post('/api/v1/auth/logout');
    } finally {
      qc.clear();
      signOut();
      nav('/login', { replace: true });
    }
  }

  const accountContext: PortalAccount = { name, role, links: settings, signOut: () => { void leave().catch(() => undefined); } };
  const initials = name?.trim().split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
  return <div className="portal-shell">
    <aside className="portal-sidebar">
      <NavLink className="portal-brand" to={home}><span><BedDouble size={22} /></span>Bid<b>Stay</b></NavLink>
      <p className="portal-label">{role}</p>
      <nav className="portal-desktop-nav" aria-label={role + ' navigation'}>
        {links.map((item) => <NavigationLink key={item.to} item={item} />)}
      </nav>
      <div className="portal-sidebar-account"><NavigationLink item={account} /></div>
    </aside>
    <div className="portal-workspace">
      <header className="portal-top">
        <div className="portal-trail">{role}{current && <> <span aria-hidden="true">/</span> {current.label}</>}</div>
        {header}
        <NavLink className="portal-avatar" to={accountPath} aria-label={role + ' account'}>
          {initials || <CircleUserRound size={20} aria-hidden="true" />}
        </NavLink>
      </header>
      <main className="main portal-main">
        {children}
        <Outlet context={accountContext} />
      </main>
    </div>
    <nav className="portal-mobile-nav" aria-label={role + ' navigation'}>
      {tabs.map((item) => <NavigationLink key={item.to} item={item} />)}
      <NavigationLink item={account} sectionActive={inSettings && !tabs.some((item) => item.to === pathname)} />
    </nav>
  </div>;
}

export function PortalAccountPage() {
  const { name, role, links, signOut } = useOutletContext<PortalAccount>();
  return <div className="portal-account-page">
    <PageHead title="Account" sub={role} />
    <div className="card portal-account-identity">
      <span className="portal-avatar"><CircleUserRound size={21} aria-hidden="true" /></span>
      <strong>{name ?? role}</strong>
    </div>
    {links.length > 0 && <nav className="portal-account-links" aria-label={role + ' tools'}>
      {links.map(({ to, label, icon: Icon }) => <NavLink to={to} key={to}>
        <Icon size={19} aria-hidden="true" />{label}<ChevronRight size={16} aria-hidden="true" />
      </NavLink>)}
    </nav>}
    <div className="portal-account-end">
      <button className="btn danger" onClick={signOut}><LogOut size={18} aria-hidden="true" /> Sign out</button>
    </div>
  </div>;
}
