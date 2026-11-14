import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/client'
import { changeStatus, dateLabel, listApplications, statuses, statusLabel } from '../api/applications'
import type { ApplicationStatus, JobApplication } from '../api/applications'

type Move = { app: JobApplication; status: ApplicationStatus; appliedOn?: string }

export function ApplicationBoard({ params }: { params: URLSearchParams }) {
  const client = useQueryClient()
  const [dragged, setDragged] = useState<JobApplication | null>(null)
  const [pending, setPending] = useState<Move | null>(null)
  const [message, setMessage] = useState('')
  const save = useMutation({ mutationFn: (move: Move) => changeStatus(move.app, move.status, move.appliedOn),
    onSuccess: async app => {
      setPending(null); setDragged(null)
      setMessage(app.company + ' moved to ' + statusLabel(app.status) + '.')
      client.setQueryData(['application', app.id], app)
      await Promise.all([
        client.invalidateQueries({ queryKey: ['applications'] }),
        client.invalidateQueries({ queryKey: ['application-board'] }),
        client.invalidateQueries({ queryKey: ['activities', app.id] }), client.invalidateQueries({ queryKey: ['actions', app.id] }),
      ])
    },
  })
  function move(app: JobApplication, status: ApplicationStatus) {
    if (save.isPending || app.status === status) return
    save.reset(); setMessage('')
    // A board move must not guess when the user actually applied.
    if (status !== 'SAVED' && !app.appliedOn) setPending({ app, status })
    else save.mutate({ app, status })
  }
  function reload() {
    setPending(null); setDragged(null); save.reset()
    void client.invalidateQueries({ queryKey: ['application-board'] })
  }
  const selected = params.get('status')
  const columns = selected ? statuses.filter(status => status === selected) : statuses
  if (!columns.length) return <p className="alert alert-danger" role="alert">Choose a valid status filter to display the board.</p>
  return <>
    <p className="text-secondary small">Drag a card to change its status, or use its Move to selector. Each column loads independently.</p>
    <p className="small text-success" role="status" aria-live="polite">{save.isPending ? 'Saving status…' : message}</p>
    {!pending && save.isError && <div className="alert alert-danger" role="alert">{save.error.message}<div className="mt-2"><button className="btn btn-outline-danger btn-sm" onClick={reload}>Reload board</button></div></div>}
    <div className="kanban-board" aria-label="Application board">{columns.map(status => <BoardColumn key={status} status={status} params={params} busy={save.isPending} onMove={move} dragged={dragged} onDrag={setDragged} />)}</div>
    {pending && <DateDialog move={pending} busy={save.isPending} error={save.error} onCancel={() => { if (!save.isPending) {setPending(null); save.reset()} }} onSubmit={date => save.mutate({ ...pending, appliedOn: date })} reload={reload} />}
  </>
}

function BoardColumn({ status, params, busy, onMove, dragged, onDrag }: {
  status: ApplicationStatus; params: URLSearchParams; busy: boolean
  onMove: (app: JobApplication, status: ApplicationStatus) => void
  dragged: JobApplication | null; onDrag: (app: JobApplication | null) => void
}) {
  const search = params.get('search') || '', source = params.get('source') || ''
  const sort = params.get('sort') || 'updatedAt', direction = params.get('direction') || 'desc'
  const [over, setOver] = useState(false)
  const column = useInfiniteQuery({ queryKey: ['application-board', status, search, source, sort, direction], initialPageParam: 0,
    queryFn: ({ pageParam }) => listApplications(new URLSearchParams({ search, source, sort, direction, status, page: String(pageParam), size: '20' })),
    getNextPageParam: page => page.page + 1 < page.totalPages ? page.page + 1 : undefined, retry: false,
  })
  // The count covers all matches, not just the currently loaded cards.
  const cards = column.data?.pages.flatMap(page => page.items) || []
  const count = column.data?.pages[0].totalElements
  return <section className={'kanban-column' + (over ? ' drop-active' : '')} aria-label={statusLabel(status) + ' column'} tabIndex={0}
    onDragOver={event => { if (dragged && !busy) { event.preventDefault(); event.dataTransfer.dropEffect = 'move'; setOver(true) } }}
    onDragLeave={event => { if (!(event.relatedTarget instanceof Node) || !event.currentTarget.contains(event.relatedTarget)) setOver(false) }}
    onDrop={event => { event.preventDefault(); setOver(false); if (dragged && event.dataTransfer.getData('text/plain') === dragged.id) onMove(dragged, status); onDrag(null) }}>
    <div className="d-flex justify-content-between align-items-center mb-3"><h2 className="h6 mb-0">{statusLabel(status)}</h2><span className="badge text-bg-light">{count ?? '…'}</span></div>
    {column.isPending && <p className="small" role="status">Loading…</p>}
    {column.isError && <p className="small text-danger" role="alert">{column.error.message} <button className="btn btn-link btn-sm p-0" onClick={() => void column.refetch()}>Retry</button></p>}
    {column.isSuccess && cards.length === 0 && <p className="small text-secondary">No applications{search || source ? ' match these filters' : ' here yet'}.</p>}
    <div className="d-grid gap-3">{cards.map(app => <article key={app.id} className="kanban-card card border-0 shadow-sm" draggable={!busy} onDragStart={event => {event.dataTransfer.setData('text/plain', app.id); event.dataTransfer.effectAllowed = 'move'; onDrag(app)}} onDragEnd={() => {onDrag(null); setOver(false)}}>
      <div className="card-body p-3"><Link to={'/applications/' + app.id} className="fw-semibold text-dark text-break">{app.company}</Link><p className="mb-2 text-break">{app.role}</p>{app.location && <p className="small text-secondary mb-1">{app.location}</p>}<p className="small text-secondary mb-2">{app.source || 'Source not specified'} · {dateLabel(app.appliedOn)}</p>
        {/* A native select makes moving cards available without drag gestures. */}
        <label htmlFor={'move-' + app.id} className="small form-label mb-1">Move to</label><select id={'move-' + app.id} aria-label={'Move ' + app.role + ' at ' + app.company} className="form-select form-select-sm" value={app.status} disabled={busy} onChange={event => onMove(app, event.target.value as ApplicationStatus)}>{statuses.map(value => <option key={value} value={value}>{statusLabel(value)}</option>)}</select>
      </div>
    </article>)}</div>
    {column.hasNextPage && <button className="btn btn-outline-dark btn-sm w-100 mt-3" disabled={column.isFetchingNextPage} onClick={() => void column.fetchNextPage()}>{column.isFetchingNextPage ? 'Loading…' : 'Load more ' + statusLabel(status).toLowerCase()}</button>}
  </section>
}

function DateDialog({ move, busy, error, onCancel, onSubmit, reload }: { move: Move; busy: boolean; error: Error | null; onCancel: () => void; onSubmit: (date: string) => void; reload: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [date, setDate] = useState('')
  useEffect(() => {
    // Native modal dialogs trap focus and support Escape without a custom focus manager.
    const element = dialog.current!
    element.showModal()
    return () => { if (element.open) element.close() }
  }, [])
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); onSubmit(date) }
  return <dialog ref={dialog} className="status-dialog" aria-labelledby="move-title" onCancel={event => {event.preventDefault(); onCancel()}}>
    <h2 id="move-title" className="h5">When did you apply?</h2><p className="text-secondary">Moving {move.app.role} at {move.app.company} to {statusLabel(move.status)} needs an application date.</p>
    {error && <div className="alert alert-danger" role="alert">{error.message}{error instanceof ApiError && error.code === 'APPLICATION_CHANGED' && <div><button className="btn btn-outline-danger btn-sm mt-2" onClick={reload}>Reload board</button></div>}</div>}
    <form onSubmit={submit}><label htmlFor="move-date" className="form-label">Application date</label><input id="move-date" type="date" className="form-control" value={date} required disabled={busy} onChange={event => setDate(event.target.value)} /><div className="d-flex gap-3 mt-4"><button type="submit" className="btn btn-dark" disabled={busy}>{busy ? 'Saving…' : 'Save status'}</button><button type="button" className="btn btn-outline-secondary" disabled={busy} onClick={onCancel}>Cancel</button></div></form>
  </dialog>
}
