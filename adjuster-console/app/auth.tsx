'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { api, loadSession, saveSession, type Session } from './api';
import type { Role } from './types';

interface AuthState {
  session: Session | null; ready: boolean;
  login: (username: string, password: string) => Promise<Session>;
  logout: () => void;
  has: (...roles: Role[]) => boolean;
}
const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => { setSession(loadSession()); setReady(true); }, []);
  const login = useCallback(async (username: string, password: string) => {
    const response = await api.login(username, password);
    const next: Session = { token: response.accessToken, expiresAt: response.expiresAt, user: response.user };
    saveSession(next); setSession(next); return next;
  }, []);
  const logout = useCallback(() => { saveSession(null); setSession(null); }, []);
  const value = useMemo<AuthState>(() => ({
    session, ready, login, logout,
    has: (...roles) => !!session && roles.some((role) => session.user.roles.includes(role)),
  }), [session, ready, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth outside AuthProvider');
  return context;
}

export function homeFor(roles: Role[]): string {
  if (roles.includes('ADMIN')) return '/admin';
  if (roles.includes('ADJUSTER')) return '/reviews';
  if (roles.includes('FINANCE')) return '/finance';
  return '/claims';
}

/** Client-side guard; every rule is enforced again by the APIs. */
export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { session, ready, has } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (!ready) return;
    if (!session) router.replace('/login');
    else if (!has(...roles)) router.replace(homeFor(session.user.roles));
  }, [ready, session, has, roles, router]);
  if (!ready || !session || !has(...roles)) return null;
  return <>{children}</>;
}
