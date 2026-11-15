import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { actionTypes, actionTypeLabel, finishAction, searchActions } from '../api/actions'
import type { JobAction } from '../api/actions'
import { dateLabel } from '../api/applications'
import { ActionForm } from './ActionPanel'

/** URL filters survive refresh/back navigation; missing status means Pending, ALL is explicit. */
export function AllActions() {
  const [params, setParams] = useSearchParams()
  const client = useQueryClient()
  const [editing, setEditing] = useState<JobAction>()
  const [message, setMessage] = useState('')
  const status = params.get('status') || 'PENDING'
  const requestParams = new URLSearchParams(params)
  if (status === 'ALL') requestParams.delete('status'); else requestParams.set('status', status)
  const queryString = requestParams.toString()
  const query = useQuery({ queryKey: ['actions', 'search', queryString], queryFn: () => searchActions(new URLSearchParams(queryString)), retry: false })
  async function refresh() {
    // Completion/rescheduling updates both parent versions and cross-application views.
    await Promise.all(['actions', 'dashboard', 'activities', 'application', 'applications', 'application-board'].map(key => client.invalidateQueries({ queryKey: [key] })))
  }
  const finish = useMutation({ mutationFn: ({ action, cancel }: { action: JobAction; cancel: boolean }) => finishAction(action, cancel),
    onSuccess: async action => {setMessage(action.status === 'COMPLETED' ? 'Action completed.' : 'Action cancelled.'); await refresh()},
  })
  function reload() {setEditing(undefined); finish.reset(); void refresh()}
  function filter(name: string, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(name, value); else next.delete(name)
    next.delete('page'); setEditing(undefined); finish.reset(); setMessage(''); setParams(next)
  }
  function search(event: FormEvent<HTMLFormElement>) {event.preventDefault(); filter('search', String(new FormData(event.currentTarget).get('search') || '').trim())}
  function page(value: number) {const next = new URLSearchParams(params); next.set('page', String(value)); setEditing(undefined); setParams(next)}
  const filtered = Boolean(params.get('search') || params.get('type') || params.get('from') || params.get('to') || status !== 'PENDING')
  return <>
    <p className="eyebrow">Across your applications</p><h1 className="page-title">All your <em>actions</em></h1><p className="text-secondary">Plan beyond this week and revisit completed work. Earliest due dates appear first.</p>
    <section className="card border-0 shadow-sm mb-4" aria-label="Action search and filters"><div className="card-body p-4">
      <form onSubmit={search} className="d-flex gap-2 mb-3"><label htmlFor="action-search" className="visually-hidden">Search actions</label><input key={params.get('search') || ''} id="action-search" name="search" type="search" maxLength={120} defaultValue={params.get('search') || ''} className="form-control" placeholder="Search action, company, or role" /><button className="btn btn-outline-dark" type="submit">Search</button></form>
      <div className="row g-3">
        <div className="col-6 col-lg-3"><label htmlFor="all-action-status" className="form-label">Status</label><select id="all-action-status" className="form-select" value={status} onChange={e => filter('status', e.target.value)}><option value="PENDING">Pending</option><option value="COMPLETED">Completed</option><option value="CANCELLED">Cancelled</option><option value="ALL">All statuses</option></select></div>
        <div className="col-6 col-lg-3"><label htmlFor="all-action-type" className="form-label">Type</label><select id="all-action-type" className="form-select" value={params.get('type') || ''} onChange={e => filter('type', e.target.value)}><option value="">All types</option>{actionTypes.map(type => <option key={type} value={type}>{actionTypeLabel(type)}</option>)}</select></div>
        <div className="col-6 col-lg-3"><label htmlFor="action-from" className="form-label">Due from</label><input id="action-from" type="date" className="form-control" value={params.get('from') || ''} onChange={e => filter('from', e.target.value)} /></div>
        <div className="col-6 col-lg-3"><label htmlFor="action-to" className="form-label">Due through</label><input id="action-to" type="date" className="form-control" value={params.get('to') || ''} onChange={e => filter('to', e.target.value)} /></div>
      </div>{filtered && <button className="btn btn-link px-0 mt-2" onClick={() => {setEditing(undefined); finish.reset(); setParams({})}}>Clear filters</button>}
    </div></section>
    <p role="status" className="text-success small" aria-live="polite">{message}</p>
    {editing && <ActionForm key={editing.id + '-' + editing.version} initial={editing} applicationId={editing.applicationId} timezone={editing.timezone} onCancel={() => setEditing(undefined)} onReload={reload} onSaved={async () => {setEditing(undefined); setMessage('Action updated.'); await refresh()}} />}
    {finish.isError && <div className="alert alert-danger" role="alert">{finish.error.message} <button className="btn btn-outline-danger btn-sm" onClick={reload}>Reload actions</button></div>}
    {query.isPending && <p role="status">Loading actions…</p>}
    {query.isError && <div className="alert alert-danger" role="alert">{query.error.message} <button className="btn btn-link" onClick={() => void query.refetch()}>Retry</button></div>}
    {query.data && <><p className="small text-secondary" aria-live="polite">{query.data.totalElements} actions{query.isFetching ? ' · Updating…' : ''}</p>
      {!query.data.items.length && <div className="card border-0 shadow-sm"><div className="card-body p-4"><h2 className="h5">{filtered ? 'No actions match these filters' : 'No pending actions'}</h2><p className="text-secondary">{filtered ? 'Try different filters or clear them to see pending actions.' : 'Open an application to manually add your next step.'}</p><Link to="/applications">Browse applications →</Link></div></div>}
      <div className="d-grid gap-3">{query.data.items.map(({action, company, role}) => <article key={action.id} className="card border-0 shadow-sm"><div className="card-body p-4">
        <div className="d-flex flex-wrap justify-content-between gap-2"><h2 className="h6 text-break">{action.title}</h2><div className="d-flex gap-2">{action.overdue && <span className="badge text-bg-danger">Overdue</span>}<span className="badge text-bg-light">{action.status.toLowerCase()}</span></div></div>
        <p className="mb-1 text-break">{company} · {role}</p><p className="small text-secondary">{actionTypeLabel(action.type)} · Due {dateLabel(action.dueOn)}{action.scheduledAt && <> · {new Intl.DateTimeFormat(undefined, {timeStyle:'short', timeZone:action.timezone}).format(new Date(action.scheduledAt))} ({action.timezone})</>}</p>
        {action.details && <p className="description-text small">{action.details}</p>}
        {action.cancellationReason && <p className="small text-secondary">{action.cancellationReason}</p>}
        <div className="d-flex flex-wrap align-items-center gap-3"><Link to={'/applications/' + encodeURIComponent(action.applicationId)}>Open application</Link>{action.status === 'PENDING' && <><button className="btn btn-outline-success btn-sm" disabled={finish.isPending || !!editing} onClick={() => finish.mutate({action, cancel:false})}>Complete</button><button className="btn btn-outline-dark btn-sm" disabled={finish.isPending || !!editing} onClick={() => {finish.reset(); setEditing(action)}}>Edit / reschedule</button><button className="btn btn-outline-secondary btn-sm" disabled={finish.isPending || !!editing} onClick={() => finish.mutate({action, cancel:true})}>Cancel action</button></>}</div>
      </div></article>)}</div>
      <nav aria-label="Action pagination" className="d-flex flex-wrap justify-content-between align-items-center gap-3 mt-4"><div><label htmlFor="action-size" className="form-label me-2">Per page</label><select id="action-size" className="form-select w-auto d-inline-block" value={params.get('size') || '20'} onChange={e => filter('size', e.target.value)}>{[10,20,50].map(size => <option key={size}>{size}</option>)}</select></div><div className="d-flex align-items-center gap-3"><button className="btn btn-outline-dark btn-sm" disabled={!query.data.page || query.isFetching} onClick={() => page(query.data.page - 1)}>Previous</button><span className="small">Page {query.data.page + 1} of {Math.max(1,query.data.totalPages)}</span><button className="btn btn-outline-dark btn-sm" disabled={query.data.page + 1 >= query.data.totalPages || query.isFetching} onClick={() => page(query.data.page + 1)}>Next</button></div></nav>
    </>}
  </>
}
