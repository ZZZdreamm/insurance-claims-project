'use client';

import { useEffect, useState } from 'react';
import { api } from '../api';
import type { Claim } from '../types';

/** Photos are behind bearer auth, so they are fetched with the token and shown as object URLs. */
export function Photos({ claim }: { claim: Claim }) {
  const [urls, setUrls] = useState<string[]>([]);
  useEffect(() => {
    let alive = true;
    Promise.all(claim.photoIds.map((p) => api.photoBlob(claim.id, p))).then((u) => alive && setUrls(u)).catch(() => {});
    return () => {
      alive = false;
      urls.forEach((u) => URL.revokeObjectURL(u));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [claim.id]);
  if (claim.photoIds.length === 0) return <span className="muted">brak zdjęć</span>;
  return (
    <div className="photos">
      {urls.map((u, i) => (
        <a key={i} href={u} target="_blank" rel="noreferrer"><img src={u} alt="uszkodzenie" /></a>
      ))}
    </div>
  );
}
