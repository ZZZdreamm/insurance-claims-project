'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';
import { useAuth } from '../auth';

const NAV: { href: string; label: string; roles: string[] }[] = [
  { href: '/claims', label: 'Moje szkody', roles: ['POLICYHOLDER', 'ADMIN'] },
  { href: '/reviews', label: 'Kolejka review', roles: ['ADJUSTER', 'ADMIN'] },
  { href: '/finance', label: 'Wypłaty', roles: ['FINANCE', 'ADMIN'] },
];

export function Shell({ title, children }: { title: string; children: ReactNode }) {
  const { session, logout, has } = useAuth();
  const path = usePathname();
  return (
    <main>
      <header className="topbar">
        <nav className="actions">
          {NAV.filter((n) => has(...(n.roles as never[]))).map((n) => (
            <Link key={n.href} href={n.href} className={path === n.href ? 'active' : ''}>{n.label}</Link>
          ))}
        </nav>
        {session && (
          <span className="muted">
            {session.user.displayName} <span className="small">[{session.user.roles.join(', ')}]</span>{' '}
            <button onClick={logout}>Wyloguj</button>
          </span>
        )}
      </header>
      <h1>{title}</h1>
      {children}
    </main>
  );
}
