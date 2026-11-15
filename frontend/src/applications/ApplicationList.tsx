import type { FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { dateLabel, getSources, listApplications, statuses, statusLabel } from '../api/applications'
import { ErrorState, StatusBadge } from './Shared'
import { ApplicationBoard } from './ApplicationBoard'

export function ApplicationList() {
  const [params, setParams] = useSearchParams()
  // URL-backed filters survive refresh and make browser back/forward useful.
  const board = params.get('view') === 'board'
  const clear = () => setParams(board ? { view: 'board' } : {})
  const toggle = (view: string) => { const next = new URLSearchParams(params); next.delete('page'); if (view === 'board') next.set('view', view); else next.delete('view'); setParams(next) }
  const queryString = params.toString()
  const result = useQuery({ queryKey: ['applications', queryString], queryFn: () => listApplications(new URLSearchParams(queryString)), enabled: !board, retry: false })
  const sources = useQuery({ queryKey: ['application-sources'], queryFn: getSources, retry: false })
  function filter(name: string, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(name, value); else next.delete(name)
    next.delete('page')
    setParams(next)
  }
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    filter('search', String(new FormData(event.currentTarget).get('search') || '').trim())
  }
  function page(number: number) { const next = new URLSearchParams(params); next.set('page', String(number)); setParams(next) }
  // Visible table headers also avoid absolute hidden text escaping a scroll container.
  const sourceOptions = [...new Set([...(sources.data || []), ...(params.get('source') ? [params.get('source')!] : [])])]
  const active = Boolean(params.get('search') || params.get('status') || params.get('source') || params.get('page'))
  return <>
    <div className="d-flex flex-wrap gap-3 justify-content-between align-items-end mb-4">
      <div><p className="eyebrow mb-2">Your job search</p><h1 className="page-title mb-2">Your <em>applications</em> library</h1><p className="text-secondary mb-0">Keep every opportunity in one place.</p></div>
      <div className="d-flex gap-3 align-items-center"><div className="btn-group" role="group" aria-label="Application view"><button className={'btn btn-sm ' + (!board ? 'btn-dark' : 'btn-outline-dark')} aria-pressed={!board} onClick={() => toggle('list')}>List</button><button className={'btn btn-sm ' + (board ? 'btn-dark' : 'btn-outline-dark')} aria-pressed={board} onClick={() => toggle('board')}>Board</button></div><Link to="/applications/new" className="btn btn-dark">+ Add application</Link></div>
    </div>
    <section className="card border-0 shadow-sm mb-4" aria-label="Search and filters"><div className="card-body p-3 p-md-4">
      <form onSubmit={search} className="d-flex gap-2 mb-3"><label className="visually-hidden" htmlFor="search">Search applications</label><input key={params.get('search') || ''} id="search" name="search" type="search" maxLength={120} defaultValue={params.get('search') || ''} className="form-control" placeholder="Search company, role, or location" /><button className="btn btn-outline-dark" type="submit">Search</button></form>
      <div className="row g-3 align-items-end">
        <div className="col-6 col-lg-3"><label htmlFor="status-filter" className="form-label small">Status</label><select id="status-filter" className="form-select" value={params.get('status') || ''} onChange={e => filter('status', e.target.value)}><option value="">All statuses</option>{statuses.map(s => <option key={s} value={s}>{statusLabel(s)}</option>)}</select></div>
        <div className="col-6 col-lg-3"><label htmlFor="source-filter" className="form-label small">Source</label><select id="source-filter" className="form-select" value={params.get('source') || ''} onChange={e => filter('source', e.target.value)}><option value="">All sources</option>{sourceOptions.map(s => <option key={s}>{s}</option>)}</select></div>
        <div className="col-6 col-lg-3"><label htmlFor="sort-filter" className="form-label small">Sort by</label><select id="sort-filter" className="form-select" value={params.get('sort') || 'updatedAt'} onChange={e => filter('sort', e.target.value)}><option value="updatedAt">Last updated</option><option value="createdAt">Date added</option><option value="company">Company</option><option value="role">Role</option><option value="appliedOn">Application date</option><option value="status">Status</option></select></div>
        <div className="col-6 col-lg-3"><label htmlFor="direction-filter" className="form-label small">Order</label><select id="direction-filter" className="form-select" value={params.get('direction') || 'desc'} onChange={e => filter('direction', e.target.value)}><option value="desc">Descending</option><option value="asc">Ascending</option></select></div>
      </div>
      {active && <button className="btn btn-link text-secondary px-0 mt-2" onClick={clear}>Clear filters</button>}
      {sources.isError && <p className="small text-danger mt-2 mb-0" role="alert">Source filters could not load. <button className="btn btn-link btn-sm p-0" onClick={() => void sources.refetch()}>Retry sources</button></p>}
    </div></section>
    {/* Board has independent column pagination; list paging never hides board records. */}
    {board ? <ApplicationBoard params={params} /> : <>
    {result.isPending && <p role="status">Loading applications…</p>}
    {result.isError && <ErrorState error={result.error} retry={() => void result.refetch()} />}
    {result.data && <>
      <p className="small text-secondary" aria-live="polite">{result.data.totalElements} {result.data.totalElements === 1 ? 'application' : 'applications'}{result.isFetching ? ' · Updating…' : ''}</p>
      {result.data.items.length === 0 ? <div className="card border-0 shadow-sm text-center"><div className="card-body py-5"><h2 className="h4">{active ? 'No applications match these filters' : 'Your next opportunity starts here'}</h2><p className="text-secondary">{active ? 'Try a different search or clear your filters.' : 'Save a role you like or record an application you’ve already sent.'}</p>{active ? <button className="btn btn-outline-dark" onClick={clear}>Show all applications</button> : <Link to="/applications/new" className="btn btn-dark">Add your first application</Link>}</div></div> :
      <div className="card border-0 shadow-sm"><div className="table-responsive"><table className="table application-table mb-0"><caption className="visually-hidden">Your applications</caption><thead><tr><th scope="col">Company &amp; role</th><th scope="col">Status</th><th scope="col">Source</th><th scope="col">Applied</th><th scope="col">Actions</th></tr></thead><tbody>{result.data.items.map(app => <tr key={app.id}><td><Link to={'/applications/' + app.id} className="fw-semibold text-dark">{app.company}</Link><div>{app.role}</div>{app.location && <div className="small text-secondary">{app.location}</div>}</td><td><StatusBadge status={app.status} /></td><td>{app.source || '—'}</td><td className="text-nowrap">{dateLabel(app.appliedOn)}</td><td><Link to={'/applications/' + app.id + '/edit'} aria-label={'Edit ' + app.role + ' at ' + app.company}>Edit</Link></td></tr>)}</tbody></table></div></div>}
      <nav className="d-flex flex-wrap gap-3 justify-content-between align-items-center mt-4" aria-label="Application pagination">
        <div className="d-flex gap-2 align-items-center"><label htmlFor="page-size" className="small text-secondary">Per page</label><select id="page-size" className="form-select form-select-sm w-auto" value={params.get('size') || '10'} onChange={e => filter('size', e.target.value)}>{[10,20,50].map(s => <option key={s}>{s}</option>)}</select></div>
        <div className="d-flex gap-3 align-items-center"><button className="btn btn-outline-dark btn-sm" disabled={result.data.page === 0 || result.isFetching} onClick={() => page(result.data.page - 1)}>Previous</button><span className="small">Page {result.data.page + 1} of {Math.max(1, result.data.totalPages)}</span><button className="btn btn-outline-dark btn-sm" disabled={result.data.page + 1 >= result.data.totalPages || result.isFetching} onClick={() => page(result.data.page + 1)}>Next</button></div>
      </nav>
    </>}
    </>}
  </>
}
