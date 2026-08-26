'use client';

import { useEffect, useState } from 'react';
import { EXTERNAL_LINKS } from '../api';

type Availability = 'checking' | 'up' | 'down';
const TOOLS = [
  { key: 'grafana', label: 'Grafana', url: EXTERNAL_LINKS.grafana, probe: '/api/health', profile: 'observability', what: 'business dashboard + JVM/HTTP metrics, Explore → Tempo (traces) and Loki (logs)' },
  { key: 'kibana', label: 'Kibana', url: EXTERNAL_LINKS.kibana, probe: '/api/status', profile: 'search', what: 'Discover over the claims (current state) and claim-events (fact log) indices' },
  { key: 'jenkins', label: 'Jenkins', url: EXTERNAL_LINKS.jenkins, probe: '/login', profile: 'ci', what: 'claims-platform pipeline (build, tests, images)' },
] as const;

/** Pings each tool with an opaque request: a resolved fetch means something answers on that port, a network error means it is not running. */
export function ToolLinks() {
  const [availability, setAvailability] = useState<Record<string, Availability>>({});
  useEffect(() => {
    TOOLS.forEach((tool) => {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 3000);
      fetch(tool.url + tool.probe, { mode: 'no-cors', signal: controller.signal })
        .then(() => setAvailability((current) => ({ ...current, [tool.key]: 'up' })))
        .catch(() => setAvailability((current) => ({ ...current, [tool.key]: 'down' })))
        .finally(() => clearTimeout(timeout));
    });
  }, []);
  return (
    <div className="card">
      <h2>External tools</h2>
      <table>
        <thead><tr><th>Tool</th><th>State</th><th>What is there</th></tr></thead>
        <tbody>
          {TOOLS.map((tool) => {
            const state = availability[tool.key] ?? 'checking';
            return (
              <tr key={tool.key}>
                <td>{state === 'up' ? <a href={tool.url} target="_blank" rel="noreferrer"><strong>{tool.label}</strong></a> : <strong>{tool.label}</strong>}<br /><span className="mono small muted">{tool.url}</span></td>
                <td>{state === 'checking' && <span className="badge">checking…</span>}
                  {state === 'up' && <span className="badge ok">running</span>}
                  {state === 'down' && <><span className="badge bad">not running</span><div className="small muted">start: <span className="mono">docker compose --profile {tool.profile} up -d</span></div></>}</td>
                <td className="muted">{tool.what}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
