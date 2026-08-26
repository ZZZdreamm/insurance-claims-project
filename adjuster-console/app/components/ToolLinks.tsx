'use client';

import { useEffect, useState } from 'react';
import { EXTERNAL_LINKS } from '../api';

type Availability = 'checking' | 'up' | 'down';
const TOOLS = [
  { key: 'grafana', label: 'Grafana', url: EXTERNAL_LINKS.grafana, probe: '/api/health', profile: 'observability', what: 'dashboard biznesowy + metryki JVM/HTTP, Explore → Tempo (trace) i Loki (logi)' },
  { key: 'kibana', label: 'Kibana', url: EXTERNAL_LINKS.kibana, probe: '/api/status', profile: 'search', what: 'Discover nad indeksami claims (stan szkód) i claim-events (dziennik zdarzeń)' },
  { key: 'jenkins', label: 'Jenkins', url: EXTERNAL_LINKS.jenkins, probe: '/login', profile: 'ci', what: 'pipeline claims-platform (build, testy, obrazy)' },
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
      <h2>Narzędzia zewnętrzne</h2>
      <table>
        <thead><tr><th>Narzędzie</th><th>Stan</th><th>Co tam jest</th></tr></thead>
        <tbody>
          {TOOLS.map((tool) => {
            const state = availability[tool.key] ?? 'checking';
            return (
              <tr key={tool.key}>
                <td>{state === 'up' ? <a href={tool.url} target="_blank" rel="noreferrer"><strong>{tool.label}</strong></a> : <strong>{tool.label}</strong>}<br /><span className="mono small muted">{tool.url}</span></td>
                <td>{state === 'checking' && <span className="badge">sprawdzam…</span>}
                  {state === 'up' && <span className="badge ok">działa</span>}
                  {state === 'down' && <><span className="badge bad">nie działa</span><div className="small muted">uruchom: <span className="mono">docker compose --profile {tool.profile} up -d</span></div></>}</td>
                <td className="muted">{tool.what}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
