'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';
import { useAuth } from '../auth';
import { EXTERNAL_LINKS } from '../api';
import type { Role } from '../types';

interface NavItem { href: string; label: string; roles: Role[]; icon: string; }
const NAV: { section: string; items: NavItem[] }[] = [
  { section: 'Policyholder', items: [{ href: '/claims', label: 'My claims', roles: ['POLICYHOLDER', 'ADMIN'], icon: '🚗' }] },
  { section: 'Claims handling', items: [
    { href: '/reviews', label: 'Review queue', roles: ['ADJUSTER', 'ADMIN'], icon: '📋' },
    { href: '/search', label: 'Search', roles: ['ADJUSTER', 'FINANCE', 'ADMIN'], icon: '🔎' },
  ] },
  { section: 'Finance', items: [{ href: '/finance', label: 'Payouts', roles: ['FINANCE', 'ADMIN'], icon: '💳' }] },
  { section: 'Administration', items: [{ href: '/admin', label: 'Admin panel', roles: ['ADMIN'], icon: '⚙️' }] },
];

export function Shell({ title, subtitle, actions, children }: { title: string; subtitle?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  const { session, logout, has } = useAuth();
  const path = usePathname();
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand"><span className="logo">C</span> Claims Platform</div>
        <nav className="nav">
          {NAV.map((group) => {
            const visible = group.items.filter((item) => has(...item.roles));
            if (visible.length === 0) return null;
            return (
              <div key={group.section}>
                <div className="section">{group.section}</div>
                {visible.map((item) => (
                  <Link key={item.href} href={item.href} className={path.startsWith(item.href) ? 'active' : ''}>
                    <span aria-hidden>{item.icon}</span> {item.label}
                  </Link>
                ))}
              </div>
            );
          })}
          {has('ADMIN') && (
            <div>
              <div className="section">Tools</div>
              <a href={EXTERNAL_LINKS.grafana} target="_blank" rel="noreferrer">📈 Grafana</a>
              <a href={EXTERNAL_LINKS.kibana} target="_blank" rel="noreferrer">🧭 Kibana</a>
              <a href={EXTERNAL_LINKS.jenkins} target="_blank" rel="noreferrer">🛠 Jenkins</a>
            </div>
          )}
        </nav>
        {session && (
          <div className="userbox">
            <div className="name">{session.user.displayName}</div>
            <div className="roles">{session.user.username} · {session.user.roles.join(', ')}</div>
            <button className="btn sm" style={{ marginTop: '0.6rem' }} onClick={logout}>Sign out</button>
          </div>
        )}
      </aside>
      <main className="content">
        <div className="page-head">
          <div><h1>{title}</h1>{subtitle && <p>{subtitle}</p>}</div>
          {actions && <div className="actions">{actions}</div>}
        </div>
        {children}
      </main>
    </div>
  );
}
