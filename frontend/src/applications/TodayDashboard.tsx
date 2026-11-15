import { useState } from 'react'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { request } from '../api/client'
import { actionTypeLabel, finishAction } from '../api/actions'
import type { JobAction } from '../api/actions'
import { dateLabel } from '../api/applications'
import { ActionForm } from './ActionPanel'

type Section = 'OVERDUE' | 'TODAY' | 'UPCOMING'
interface AgendaPage {
  items: { action: JobAction; company: string; role: string }[]
  page: number; totalPages: number; totalElements: number; today: string; timezone: string
}

/** Each section pages independently, so a long overdue list cannot hide today's interviews. */
export function TodayDashboard() {
  return <><div className="d-flex flex-wrap justify-content-between gap-3 mb-4"><div><p className="eyebrow">Your personal workspace</p><h1 className="page-title">Next steps for your <em>career</em></h1><p className="text-secondary mb-0">Your next steps across all applications. Follow-ups are created manually.</p></div><Link to="/applications/new" className="btn btn-dark align-self-start">Add application</Link></div>
    <div className="d-grid gap-4">{(['OVERDUE', 'TODAY', 'UPCOMING'] as Section[]).map(section => <AgendaSection key={section} section={section} />)}</div></>
}

function AgendaSection({ section }: { section: Section }) {
  const client = useQueryClient()
  const [editing, setEditing] = useState<JobAction>()
  const [message, setMessage] = useState('')
  const query = useInfiniteQuery({ queryKey: ['dashboard', section], initialPageParam: 0,
    queryFn: ({ pageParam }) => request<AgendaPage>('/api/dashboard/actions?' + new URLSearchParams({ section, page: String(pageParam) })),
    getNextPageParam: page => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
    // Refetch across midnight and on focus; grouping always comes from the server's account date.
    refetchInterval: 60000, retry: false,
  })
  async function refresh() {
    await Promise.all(['dashboard', 'actions', 'activities', 'application', 'applications', 'application-board'].map(key => client.invalidateQueries({ queryKey: [key] })))
  }
  const complete = useMutation({ mutationFn: (action: JobAction) => finishAction(action, false),
    onSuccess: async () => { setMessage('Action completed.'); await refresh() },
  })
  function reload() { setEditing(undefined); complete.reset(); void refresh() }
  const heading = { OVERDUE: 'Overdue', TODAY: 'Today', UPCOMING: 'Upcoming · next 7 days' }[section]
  const items = query.data?.pages.flatMap(page => page.items) || []
  return <section className={"agenda-section agenda-" + section.toLowerCase()} aria-label={heading}><div>
    <h2 className="section-title">{heading}{query.data && <span className="badge text-bg-light ms-2">{query.data.pages[0].totalElements}</span>}</h2>
    {query.data && <p className="small text-secondary">As of {dateLabel(query.data.pages[0].today)} · {query.data.pages[0].timezone}</p>}
    <p role="status" className="small text-success">{message}</p>
    {query.isPending && <p role="status">Loading actions…</p>}
    {query.isError && <p className="alert alert-danger" role="alert">{query.error.message} <button className="btn btn-link" onClick={() => void query.refetch()}>Retry</button></p>}
    {complete.isError && <div className="alert alert-danger" role="alert">{complete.error.message} <button className="btn btn-outline-danger btn-sm" onClick={reload}>Reload dashboard</button></div>}
    {editing && <ActionForm key={editing.id + '-' + editing.version} initial={editing} applicationId={editing.applicationId} timezone={editing.timezone} onCancel={() => setEditing(undefined)} onReload={reload} onSaved={async () => {setEditing(undefined); setMessage('Action rescheduled.'); await refresh()}} />}
    {query.isSuccess && !items.length && <p className="text-secondary mb-0">{section === 'OVERDUE' ? 'No overdue actions.' : section === 'TODAY' ? 'No pending actions due today.' : 'No pending actions in the next 7 days.'}</p>}
    <div className="agenda-grid">{items.map(({ action, company, role }) => <article key={action.id} className="agenda-card">
      {/* Initials provide a consistent visual anchor without inventing company logos. */}
      <span className="eyebrow action-kind">{actionTypeLabel(action.type)}</span>
      <div className="company-mark" aria-hidden="true">{company.trim().slice(0, 2).toUpperCase()}</div>
      <h3 className="h6 text-break">{action.title}</h3><p className="mb-1 text-break company-line">{company}</p><p className="small text-secondary">{role}</p>
      <p className="small text-secondary mb-2">{actionTypeLabel(action.type)} · Due {dateLabel(action.dueOn)}{action.scheduledAt && <> · {new Intl.DateTimeFormat(undefined, { timeStyle: 'short', timeZone: action.timezone }).format(new Date(action.scheduledAt))} ({action.timezone})</>}</p>
      <div className="agenda-card-controls d-flex flex-wrap align-items-center gap-3"><Link to={'/applications/' + encodeURIComponent(action.applicationId)}>Open application</Link><button className="btn btn-outline-success btn-sm" disabled={complete.isPending || !!editing} onClick={() => {setMessage(''); complete.mutate(action)}}>Complete</button><button className="btn btn-outline-dark btn-sm" disabled={complete.isPending || !!editing} onClick={() => {setMessage(''); complete.reset(); setEditing(action)}}>Reschedule</button></div>
    </article>)}</div>
    {query.hasNextPage && <button className="btn btn-outline-dark btn-sm mt-3" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? 'Loading…' : 'Load more'}</button>}
  </div></section>
}
