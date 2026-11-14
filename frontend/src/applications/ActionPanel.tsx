import { useState } from 'react'
import type { FormEvent } from 'react'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { actionTypeLabel, actionTypes, finishAction, getActions, saveAction } from '../api/actions'
import type { ActionInput, JobAction } from '../api/actions'
import { dateLabel } from '../api/applications'
import type { JobApplication } from '../api/applications'

/** Per-application actions are the foundation for the future cross-application Today screen. */
export function ActionPanel({ app, timezone }: { app: JobApplication; timezone: string }) {
  const client = useQueryClient()
  const [filter, setFilter] = useState('PENDING')
  // undefined = closed form, null = create form, object = edit form.
  const [editing, setEditing] = useState<JobAction | null | undefined>(undefined)
  const [message, setMessage] = useState('')
  const closed = ['ACCEPTED', 'REJECTED', 'WITHDRAWN'].includes(app.status)
  const query = useInfiniteQuery({ queryKey: ['actions', app.id, filter], initialPageParam: 0,
    queryFn: ({ pageParam }) => getActions(app.id, filter, pageParam), retry: false,
    getNextPageParam: page => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
  })
  async function refresh() {
    // Action writes also advance the parent version and can reorder the board/list.
    await Promise.all([
      client.invalidateQueries({ queryKey: ['actions', app.id] }),
      client.invalidateQueries({ queryKey: ['activities', app.id] }),
      client.invalidateQueries({ queryKey: ['application', app.id] }),
      client.invalidateQueries({ queryKey: ['applications'] }),
      client.invalidateQueries({ queryKey: ['application-board'] }),
    ])
  }
  const finish = useMutation({ mutationFn: ({ action, cancel }: { action: JobAction; cancel: boolean }) => finishAction(action, cancel),
    onSuccess: async action => { setMessage(action.status === 'COMPLETED' ? 'Action completed.' : 'Action cancelled.'); await refresh() },
  })
  function reload() { setEditing(undefined); finish.reset(); void refresh() }
  const actions = query.data?.pages.flatMap(page => page.items) || []
  const timestamp = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: timezone }).format(new Date(value))
  return <section className="card border-0 shadow-sm mt-4" aria-labelledby="actions-title"><div className="card-body p-4">
    <div className="d-flex flex-wrap justify-content-between gap-3 align-items-center"><h2 id="actions-title" className="h5 mb-0">Actions &amp; interviews</h2><button className="btn btn-dark btn-sm" disabled={closed || editing !== undefined || finish.isPending} onClick={() => {setEditing(null); setMessage(''); finish.reset()}}>Add action</button></div>
    <p className="text-secondary small mt-2">Track your next step. New follow-ups are always created manually.</p>
    {closed && <p className="alert alert-secondary small">This application is closed. Pending actions were cancelled; completed actions remain in your history. Reopen the application to add new actions.</p>}
    <p role="status" className="small text-success" aria-live="polite">{message}</p>
    {editing !== undefined && !closed && <ActionForm key={editing ? editing.id + '-' + editing.version : 'new'} initial={editing || undefined} applicationId={app.id} timezone={timezone} onCancel={() => setEditing(undefined)} onReload={reload} onSaved={async () => {if (!editing) setFilter('PENDING'); setEditing(undefined); setMessage(editing ? 'Action updated.' : 'Action added.'); await refresh()}} />}
    {finish.isError && <div className="alert alert-danger" role="alert">{finish.error.message}<div><button className="btn btn-outline-danger btn-sm mt-2" onClick={reload}>Reload actions</button></div></div>}
    <div className="d-flex flex-wrap justify-content-between gap-3 align-items-end mb-3"><div><label className="form-label small" htmlFor="action-status-filter">Show actions</label><select id="action-status-filter" className="form-select form-select-sm" value={filter} onChange={event => {setFilter(event.target.value); finish.reset()}}><option value="PENDING">Pending</option><option value="COMPLETED">Completed</option><option value="CANCELLED">Cancelled</option><option value="">All actions</option></select></div>{query.data && <span className="small text-secondary">{query.data.pages[0].totalElements} actions</span>}</div>
    {query.isPending && <p role="status">Loading actions…</p>}
    {query.isError && <p className="text-danger" role="alert">{query.error.message} <button className="btn btn-link btn-sm" onClick={() => void query.refetch()}>Retry actions</button></p>}
    {query.isSuccess && actions.length === 0 && <p className="text-secondary mb-0">No {filter ? filter.toLowerCase() + ' ' : ''}actions yet.</p>}
    <div className="d-grid gap-3">{actions.map(action => <article key={action.id} className="border rounded p-3 action-card">
      <div className="d-flex flex-wrap gap-2 justify-content-between"><h3 className="h6 text-break mb-1">{action.title}</h3><div className="d-flex gap-2 align-items-center">{action.overdue && <span className="badge text-bg-danger">Overdue</span>}<span className="badge text-bg-light">{action.status.toLowerCase()}</span></div></div>
      <p className="small text-secondary mb-1">{actionTypeLabel(action.type)} · Due {dateLabel(action.dueOn)}</p>
      {action.scheduledAt && <p className="small mb-2">Interview: {timestamp(action.scheduledAt)} ({timezone})</p>}
      {action.details && <p className="description-text small mb-2">{action.details}</p>}
      {action.completedAt && <p className="small text-secondary mb-0">Completed {timestamp(action.completedAt)}</p>}
      {action.cancelledAt && <p className="small text-secondary mb-0">{action.cancellationReason} · {timestamp(action.cancelledAt)}</p>}
      {action.status === 'PENDING' && <div className="d-flex flex-wrap gap-3 mt-3"><button className="btn btn-outline-success btn-sm" disabled={closed || finish.isPending || editing !== undefined} onClick={() => finish.mutate({action,cancel:false})}>Complete</button><button className="btn btn-outline-dark btn-sm" disabled={closed || finish.isPending || editing !== undefined} onClick={() => {setEditing(action);finish.reset()}}>Edit / reschedule</button><button className="btn btn-outline-secondary btn-sm" disabled={finish.isPending || editing !== undefined} onClick={() => finish.mutate({action,cancel:true})}>Cancel action</button></div>}
    </article>)}</div>
    {query.hasNextPage && <button className="btn btn-outline-dark btn-sm mt-3" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? 'Loading…' : 'Load more actions'}</button>}
  </div></section>
}

function ActionForm({ initial, applicationId, timezone, onSaved, onCancel, onReload }: {
  initial?: JobAction; applicationId: string; timezone: string; onSaved: () => Promise<void>; onCancel: () => void; onReload: () => void
}) {
  // Copy only editable fields: audit timestamps and status are server-controlled.
  const [form,setForm] = useState<ActionInput>(() => initial ? {title:initial.title,type:initial.type,dueOn:initial.dueOn,scheduledTime:initial.scheduledTime,details:initial.details,version:initial.version} : {title:'',type:'FOLLOW_UP',dueOn:'',scheduledTime:'',details:''})
  const save = useMutation({mutationFn: () => saveAction(applicationId,form,initial?.id),onSuccess:onSaved})
  const fields = save.error instanceof ApiError ? save.error.fieldErrors : {}
  function change<K extends keyof ActionInput>(key: K,value: ActionInput[K]) {setForm(current=>({...current,[key]:value}))}
  function submit(event: FormEvent<HTMLFormElement>) {event.preventDefault();save.mutate()}
  return <form onSubmit={submit} className="border rounded bg-light p-3 mb-4"><h3 className="h6">{initial?'Edit / reschedule action':'New action'}</h3>
    {save.isError && <div className="alert alert-danger" role="alert">{save.error.message}{save.error instanceof ApiError && save.error.status===409 && <div><button type="button" className="btn btn-outline-danger btn-sm mt-2" onClick={onReload}>Reload actions (discards edits)</button></div>}</div>}
    <fieldset disabled={save.isPending}><div className="row g-3">
      <div className="col-12"><label htmlFor="action-title" className="form-label">Action title *</label><input id="action-title" className="form-control" value={form.title} onChange={event=>change('title',event.target.value)} required maxLength={160} aria-invalid={Boolean(fields.title)} />{fields.title && <div className="text-danger small">{fields.title}</div>}</div>
      <div className="col-md-6"><label htmlFor="action-type" className="form-label">Action type *</label><select id="action-type" className="form-select" value={form.type} onChange={event=>setForm(current=>({...current,type:event.target.value as ActionInput['type'],scheduledTime:event.target.value==='INTERVIEW'?current.scheduledTime:''}))}>{actionTypes.map(type=><option key={type} value={type}>{actionTypeLabel(type)}</option>)}</select></div>
      <div className="col-md-6"><label htmlFor="action-due" className="form-label">Due date *</label><input id="action-due" className="form-control" type="date" value={form.dueOn} onChange={event=>change('dueOn',event.target.value)} required aria-invalid={Boolean(fields.dueOn)} />{fields.dueOn && <div className="text-danger small">{fields.dueOn}</div>}</div>
      {form.type==='INTERVIEW' && <div className="col-12"><label htmlFor="action-time" className="form-label">Interview time (optional)</label><input id="action-time" type="time" className="form-control" value={form.scheduledTime} onChange={event=>change('scheduledTime',event.target.value)} aria-invalid={Boolean(fields.scheduledTime)} aria-describedby="action-time-help" /><div id="action-time-help" className={fields.scheduledTime?'text-danger small':'form-text'}>{fields.scheduledTime || 'Time on the due date, in '+timezone+'.'}</div></div>}
      <div className="col-12"><label htmlFor="action-details" className="form-label">Action details</label><textarea id="action-details" className="form-control" rows={4} maxLength={10000} value={form.details} onChange={event=>change('details',event.target.value)} placeholder="Meeting link, preparation notes, or instructions" />{fields.details && <div className="text-danger small">{fields.details}</div>}</div>
    </div><div className="d-flex gap-3 mt-3"><button className="btn btn-dark btn-sm" type="submit">{save.isPending?'Saving…':initial?'Save action changes':'Save action'}</button><button className="btn btn-outline-secondary btn-sm" type="button" onClick={onCancel}>Discard</button></div></fieldset>
  </form>
}
