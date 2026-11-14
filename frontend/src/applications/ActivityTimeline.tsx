import { useState } from 'react'
import type { FormEvent } from 'react'
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { addNote, dateLabel, getActivities, statusLabel } from '../api/applications'
import type { Activity } from '../api/applications'
import { actionTypeLabel } from '../api/actions'

/** Notes and server-generated history are one paginated, newest-first timeline. */
export function ActivityTimeline({ id, timezone }: { id: string; timezone: string }) {
  const client = useQueryClient()
  const [content, setContent] = useState('')
  const timeline = useInfiniteQuery({ queryKey: ['activities', id], initialPageParam: 0,
    queryFn: ({ pageParam }) => getActivities(id, pageParam),
    getNextPageParam: page => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
    retry: false,
  })
  const note = useMutation({ mutationFn: () => addNote(id, content), onSuccess: async () => {
    setContent('')
    await client.invalidateQueries({ queryKey: ['activities', id] })
  } })
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); note.mutate() }
  const entries = timeline.data?.pages.flatMap(page => page.items) || []
  return <section className="card border-0 shadow-sm mt-4" aria-labelledby="timeline-title"><div className="card-body p-4">
    <h2 className="h5" id="timeline-title">Notes &amp; history</h2><p className="text-secondary small">Keep conversations and status changes together. Times shown in {timezone}.</p>
    <form onSubmit={submit} className="mb-4"><label htmlFor="note-content" className="form-label">Add a note</label><textarea id="note-content" className="form-control" rows={3} maxLength={10000} required value={content} onChange={e => setContent(e.target.value)} disabled={note.isPending} placeholder="Recruiter updates, interview preparation, or anything worth remembering…" /><div className="d-flex justify-content-between gap-3 align-items-center mt-2"><span className="form-text">Plain text · up to 10,000 characters</span><button type="submit" className="btn btn-dark btn-sm" disabled={note.isPending || !content.trim()}>{note.isPending ? 'Adding…' : 'Add note'}</button></div>{note.isError && <p className="text-danger small mt-2" role="alert">{note.error.message}</p>}</form>
    {timeline.isPending && <p role="status">Loading history…</p>}
    {timeline.isError && <p className="text-danger" role="alert">{timeline.error.message} <button className="btn btn-link btn-sm" onClick={() => void timeline.refetch()}>Retry history</button></p>}
    {!timeline.isPending && !timeline.isError && entries.length === 0 && <p className="text-secondary">No history yet. Earlier status changes were not recorded; new changes and notes will appear here.</p>}
    <ol className="timeline-list mb-0">{entries.map(event => <TimelineEntry key={event.id} event={event} timezone={timezone} />)}</ol>
    {timeline.hasNextPage && <button className="btn btn-outline-dark btn-sm mt-3" disabled={timeline.isFetchingNextPage} onClick={() => void timeline.fetchNextPage()}>{timeline.isFetchingNextPage ? 'Loading…' : 'Load older activity'}</button>}
  </div></section>
}

/** Action snapshots display historical values, rather than reading the current action. */
function TimelineEntry({event,timezone}:{event:Activity;timezone:string}) {
  const timestamp=(value:string)=>new Intl.DateTimeFormat(undefined,{dateStyle:'medium',timeStyle:'short',timeZone:timezone}).format(new Date(value))
  const before=event.beforeAction, after=event.afterAction
  let title='Activity'
  if(event.type==='NOTE')title='Note'
  else if(event.type==='APPLICATION_CREATED')title='Application created'
  else if(event.type==='STATUS_CHANGED')title=(event.previousStatus?statusLabel(event.previousStatus):'')+' → '+(event.status?statusLabel(event.status):'')
  else if(event.type==='ACTION_CREATED')title='Action created'
  else if(event.type==='ACTION_COMPLETED')title='Action completed'
  else if(event.type==='ACTION_CANCELLED')title='Action cancelled'
  else if(event.type==='ACTION_UPDATED')title=before?.dueOn!==after?.dueOn||before?.scheduledAt!==after?.scheduledAt?'Action rescheduled':'Action updated'
  return <li className="timeline-entry"><div className="d-flex flex-wrap justify-content-between gap-2"><h3 className="h6 mb-1">{title}</h3><time className="small text-secondary" dateTime={event.createdAt}>{timestamp(event.createdAt)}</time></div>
    {event.type==='NOTE' && <p className="description-text mb-0">{event.content}</p>}
    {event.type==='APPLICATION_CREATED' && event.status && <p className="small text-secondary mb-0">Initial status: {statusLabel(event.status)}</p>}
    {after && <><p className="fw-semibold small mb-1">{after.title}</p>{before && before.title!==after.title && <p className="small text-secondary mb-1">Previously: {before.title}</p>}<p className="small text-secondary mb-1">{actionTypeLabel(after.type)} · Due {before && before.dueOn!==after.dueOn?dateLabel(before.dueOn)+' → ':''}{dateLabel(after.dueOn)}</p>{after.scheduledAt && <p className="small mb-1">Interview: {before?.scheduledAt && before.scheduledAt!==after.scheduledAt?timestamp(before.scheduledAt)+' → ':''}{timestamp(after.scheduledAt)} ({timezone})</p>}{after.details && <p className="description-text small mb-1">{after.details}</p>}{after.cancellationReason && <p className="small text-secondary mb-0">{after.cancellationReason}</p>}</>}
  </li>
}
