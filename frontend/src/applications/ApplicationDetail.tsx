import { useQuery } from '@tanstack/react-query'
import { Link, useOutletContext, useParams } from 'react-router-dom'
import { dateLabel, getApplication } from '../api/applications'
import type { Session } from '../api/session'
import { ErrorState, StatusBadge } from './Shared'
import { ActivityTimeline } from './ActivityTimeline'
import { ActionPanel } from './ActionPanel'

export function ApplicationDetail() {
  const { id } = useParams()
  const session = useOutletContext<Session>()
  const query = useQuery({ queryKey: ['application', id], queryFn: () => getApplication(id!), retry: false })
  if (query.isPending) return <p role="status">Loading application…</p>
  if (query.isError) return <ErrorState error={query.error} retry={() => void query.refetch()} />
  const app = query.data
  // Timestamp display follows the account timezone; date-only appliedOn stays unchanged.
  const timestamp = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: session.timezone }).format(new Date(value))
  return <div className="detail-panel mx-auto">
    <Link to="/applications" className="small">← All applications</Link>
    <div className="d-flex flex-wrap justify-content-between gap-3 align-items-start mt-3 mb-4"><div><p className="eyebrow mb-2">{app.company}</p><h1 className="page-title text-break">{app.role}</h1>{app.location && <p className="text-secondary mb-2">{app.location}</p>}<StatusBadge status={app.status} /></div><Link to={'/applications/' + app.id + '/edit'} className="btn btn-dark">Edit application</Link></div>
    <section className="card border-0 shadow-sm mb-4" aria-labelledby="details-title"><div className="card-body p-4"><h2 id="details-title" className="h5 mb-3">Application details</h2><dl className="row mb-0"><dt className="col-sm-4">Application date</dt><dd className="col-sm-8">{dateLabel(app.appliedOn)}</dd><dt className="col-sm-4">Source</dt><dd className="col-sm-8">{app.source || 'Not specified'}</dd><dt className="col-sm-4">Job posting</dt><dd className="col-sm-8 text-break">{app.postingUrl ? <a href={app.postingUrl} target="_blank" rel="noopener noreferrer">Open job posting ↗</a> : 'Not provided'}</dd></dl></div></section>
    <section className="card border-0 shadow-sm" aria-labelledby="description-title"><div className="card-body p-4"><h2 id="description-title" className="h5 mb-3">Job description</h2><p className="description-text mb-0">{app.jobDescription || 'No description saved yet.'}</p></div></section>
    {/* The timeline owns its loading/error states so job details remain usable. */}
    <ActionPanel app={app} timezone={session.timezone} />
    <ActivityTimeline id={app.id} timezone={session.timezone} />
    <p className="small text-secondary mt-3">Added {timestamp(app.createdAt)} · Updated {timestamp(app.updatedAt)} ({session.timezone})</p>
  </div>
}
