'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { homeFor, useAuth } from './auth';

export default function Index() {
  const { session, ready } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (ready) router.replace(session ? homeFor(session.user.roles) : '/login');
  }, [ready, session, router]);
  return null;
}
