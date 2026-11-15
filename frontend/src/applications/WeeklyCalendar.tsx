import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useOutletContext, useSearchParams } from 'react-router-dom'
import { actionTypeLabel, finishAction, searchActions } from '../api/actions'
import type { JobAction, ActionSearchPage } from '../api/actions'
import type { Session } from '../api/session'
import { request } from '../api/client'
import { dateLabel } from '../api/applications'
import { ActionForm } from './ActionPanel'

// Calendar arithmetic uses UTC only as a date container: DST never adds/removes a day.
function shift(date: string, days: number) { const value = new Date(date + 'T12:00:00Z'); value.setUTCDate(value.getUTCDate() + days); return value.toISOString().slice(0,10) }
function monday(date: string) {const day = new Date(date + 'T12:00:00Z').getUTCDay(); return shift(date, -(day + 6) % 7)}
function validDate(date: string) { return /^\d{4}-\d{2}-\d{2}$/.test(date) && !Number.isNaN(Date.parse(date + 'T12:00:00Z')) && new Date(date + 'T12:00:00Z').toISOString().slice(0,10) === date && date >= '1900-01-01' && date <= '9998-12-31' }

interface Milestone { id: string; applicationId: string; company: string; role: string; label: string; date: string }
type CalendarItem = ActionSearchPage['items'][number]
// Visual slots mark start times only: no duration is stored in the current data model.
function positionEvents(items: CalendarItem[]) {
  const sorted = items.map(item => ({item, minute:Number(item.action.scheduledTime.slice(0,2))*60+Number(item.action.scheduledTime.slice(3)), lane:0, lanes:1})).sort((a,b)=>a.minute-b.minute || a.item.action.id.localeCompare(b.item.action.id))
  let group: typeof sorted = [], ends: number[] = [], groupEnd = -1
  function finish() {for(const event of group)event.lanes=ends.length;group=[];ends=[]}
  for(const event of sorted) {
    if(event.minute>=groupEnd)finish()
    let lane=ends.findIndex(end=>end<=event.minute)
    if(lane<0)lane=ends.length
    event.lane=lane;ends[lane]=event.minute+60;groupEnd=Math.max(...ends);group.push(event)
  }
  finish();return sorted
}

export function WeeklyCalendar() {
  const session = useOutletContext<Session>()
  const [params, setParams] = useSearchParams()
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    // Recompute the account date across midnight, including when a sleeping tab regains focus.
    const update = () => setNow(new Date())
    const timer = window.setInterval(update, 60000)
    window.addEventListener('focus', update)
    return () => {window.clearInterval(timer); window.removeEventListener('focus', update)}
  }, [])
  // Derive today in the account zone, independently of the laptop/browser timezone.
  const parts = new Intl.DateTimeFormat('en-US', {timeZone:session.timezone, year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(now)
  const part = (name: string) => parts.find(p => p.type === name)!.value
  const today = `${part('year')}-${part('month')}-${part('day')}`
  const selected = params.get('week') || today
  const valid = validDate(selected)
  const start = monday(valid ? selected : today), end = shift(start,6)
  const status = params.get('status') || 'PENDING'
  const client = useQueryClient()
  const [selectedEvent,setSelectedEvent] = useState<CalendarItem>()
  const dialog = useRef<HTMLDialogElement>(null)
  const timeScroller = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if(selectedEvent) dialog.current?.showModal()
  }, [selectedEvent])
  const [editing,setEditing] = useState<JobAction>()
  const [message,setMessage] = useState('')
  const query = useQuery({queryKey:['actions','calendar',start,status], enabled:valid, retry:false, refetchInterval:60000,
    queryFn: async () => {
      const filters = new URLSearchParams({from:start,to:end,size:'50'})
      if(status !== 'ALL') filters.set('status',status)
      // Fetch every page so busy weeks never silently hide the 51st event.
      let page = await searchActions(filters)
      const items: ActionSearchPage['items'] = [...page.items]
      while(page.page + 1 < page.totalPages) { filters.set('page',String(page.page + 1)); page = await searchActions(filters); items.push(...page.items) }
      return items
    },
  })
  // Milestones are history, so they remain visible when the action-status filter changes.
  const milestones = useQuery({queryKey:['actions','calendar','milestones',start], enabled:valid, retry:false, refetchInterval:60000,
    queryFn:()=>request<Milestone[]>('/api/calendar/milestones?'+new URLSearchParams({from:start,to:end})),
  })
  // Open around normal working hours; earlier/later events remain reachable by scrolling.
  useEffect(() => {if(query.data && timeScroller.current)timeScroller.current.scrollTop=8*64}, [query.data, start])
  async function refresh() { await Promise.all(['actions','dashboard','activities','application','applications','application-board'].map(key=>client.invalidateQueries({queryKey:[key]}))) }
  const complete = useMutation({mutationFn:(action: JobAction)=>finishAction(action,false),onSuccess:async()=>{setMessage('Action completed.');await refresh()}})
  function reload() {setEditing(undefined);complete.reset();void refresh()}
  function navigate(date?: string) {setSelectedEvent(undefined);setEditing(undefined);complete.reset();setMessage('');const next = new URLSearchParams(params);if(date)next.set('week',date);else next.delete('week');setParams(next)}
  const days = Array.from({length:7},(_,i)=>shift(start,i))
  function eventCard({action,company,role}:ActionSearchPage['items'][number]) {
    return <article key={action.id} className={'calendar-event ' + (action.scheduledAt?'calendar-interview':'')}>
      <p className="eyebrow mb-1">{action.scheduledAt ? action.scheduledTime : actionTypeLabel(action.type)}</p><h3 className="h6 text-break">{action.title}</h3><p className="small mb-1 text-break">{company} · {role}</p>
      <p className="small text-secondary mb-2">{action.status.toLowerCase()}{action.overdue?' · Overdue':''}</p>
      <div className="d-flex flex-wrap gap-2 align-items-center"><Link to={'/applications/'+encodeURIComponent(action.applicationId)} className="small">Open application</Link>{action.status==='PENDING' && <><button className="btn btn-outline-dark btn-sm" disabled={!!editing || complete.isPending} onClick={()=>{setSelectedEvent(undefined);setEditing(action);complete.reset()}}>Reschedule</button><button className="btn btn-outline-success btn-sm" disabled={!!editing || complete.isPending} onClick={()=>{setSelectedEvent(undefined);complete.mutate(action)}}>Complete</button></>}</div>
    </article>
  }
  return <>
    <p className="eyebrow">Make room for your next step</p><h1 className="page-title">Your weekly <em>calendar</em></h1><p className="text-secondary">Monday–Sunday · Times in {session.timezone}. Date-only actions have no assigned time.</p>
    <div className="d-flex flex-wrap gap-3 justify-content-between align-items-center mb-4"><div className="d-flex flex-wrap gap-2"><button className="btn btn-outline-dark" disabled={start<'1900-01-08'} onClick={()=>navigate(shift(start,-7))}>Previous week</button><button className="btn btn-dark" onClick={()=>navigate()}>This week</button><button className="btn btn-outline-dark" disabled={end>'9998-12-20'} onClick={()=>navigate(shift(start,7))}>Next week</button></div><div><label htmlFor="calendar-status" className="form-label me-2">Show actions</label><select id="calendar-status" className="form-select d-inline-block w-auto" value={status} onChange={e=>{setEditing(undefined);const next=new URLSearchParams(params);next.set('status',e.target.value);setParams(next)}}><option value="PENDING">Pending</option><option value="ALL">All statuses</option></select></div></div>
    <h2 className="section-title">{dateLabel(start)} – {dateLabel(end)}</h2>
    {!valid && <p className="alert alert-danger" role="alert">Invalid week date. <button className="btn btn-link" onClick={()=>navigate()}>Return to this week</button></p>}
    <p role="status" className="text-success small">{message}</p>
    {editing && <ActionForm key={editing.id+'-'+editing.version} initial={editing} applicationId={editing.applicationId} timezone={session.timezone} onCancel={()=>setEditing(undefined)} onReload={reload} onSaved={async()=>{setEditing(undefined);setMessage('Action rescheduled.');await refresh()}} />}
    {complete.isError && <div className="alert alert-danger" role="alert">{complete.error.message} <button className="btn btn-outline-danger" onClick={reload}>Reload calendar</button></div>}
    {valid && query.isPending && <p role="status">Loading your week…</p>}
    {query.isError && <p className="alert alert-danger" role="alert">{query.error.message} <button className="btn btn-link" onClick={()=>void query.refetch()}>Retry</button></p>}
    {milestones.isError && <p className="alert alert-danger" role="alert">Application milestones could not load. {milestones.error.message} <button className="btn btn-link" onClick={()=>void milestones.refetch()}>Retry milestones</button></p>}
    {valid && milestones.isPending && <p role="status">Loading application milestones…</p>}
    {valid && query.data && <>
      <p className="small text-secondary">Click an event for details and controls. Blocks mark start times; interview duration isn’t recorded.</p>
      {!query.data.length && milestones.isSuccess && !milestones.data.length && <p className="text-secondary">No {status==='PENDING'?'pending ':''}actions scheduled this week.</p>}
      {/* One scroll container keeps day headers, all-day events, and hourly columns aligned. */}
      <div className="week-grid-scroll" ref={timeScroller} aria-label="Weekly calendar" tabIndex={0}>
        <div className="week-grid-inner">
          <div className="week-day-headers"><div className="week-zone">{session.timezone}</div>{days.map(day=><div key={day} className={'week-day-heading'+(day===today?' is-today':'')}><time dateTime={day}><span>{new Intl.DateTimeFormat(undefined,{weekday:'short',timeZone:'UTC'}).format(new Date(day+'T12:00:00Z'))}</span><strong>{Number(day.slice(-2))}</strong></time></div>)}</div>
          <div className="week-all-day"><div className="week-axis-label">All-day<br /><small>No time set</small></div>{days.map(day=><div key={day} className="week-all-day-cell">{milestones.data?.filter(item=>item.date===day).map(item=><Link key={item.id} className="week-all-day-event calendar-milestone" to={'/applications/'+encodeURIComponent(item.applicationId)} title={item.label+' — '+item.company+' · '+item.role}><strong>{item.label} — {item.company}</strong><span>{item.role}</span></Link>)}{query.data.filter(item=>item.action.dueOn===day && !item.action.scheduledAt).map(item=><button key={item.action.id} className={'week-all-day-event event-'+item.action.status.toLowerCase()} onClick={()=>setSelectedEvent(item)}><strong>{item.action.title}</strong><span>{item.company}</span></button>)}</div>)}</div>
          <div className="week-hour-grid"><div className="week-time-axis">{Array.from({length:24},(_,hour)=><span key={hour} style={{top:hour*64}}>{String(hour).padStart(2,'0')}:00</span>)}</div>
          {days.map(day=><section key={day} aria-label={dateLabel(day)} className={'week-time-column'+(day===today?' is-today':'')}>
            {positionEvents(query.data.filter(item=>item.action.dueOn===day && item.action.scheduledAt)).map(({item,minute,lane,lanes})=><button key={item.action.id} className={'week-timed-event event-'+item.action.status.toLowerCase()} style={{top:minute/60*64,left:`calc(${lane/lanes*100}% + 3px)`,width:`calc(${100/lanes}% - 6px)`}} onClick={()=>setSelectedEvent(item)} aria-label={item.action.scheduledTime+' '+item.action.title+' at '+item.company}><strong>{item.action.scheduledTime}</strong><span>{item.action.title}</span><small>{item.company}</small></button>)}
            {day===today && <div className="week-now-line" style={{top:(Number(new Intl.DateTimeFormat('en-GB',{timeZone:session.timezone,hour:'2-digit',hourCycle:'h23'}).format(now))*60+Number(new Intl.DateTimeFormat('en-GB',{timeZone:session.timezone,minute:'2-digit'}).format(now)))/60*64}} aria-label="Current time" />}
          </section>)}
          </div>
        </div>
      </div>
    </>}
    {selectedEvent && <dialog ref={dialog} className="status-dialog calendar-event-dialog" aria-label="Event details" onCancel={()=>setSelectedEvent(undefined)} onClick={event=>{if(event.target===event.currentTarget)setSelectedEvent(undefined)}}>
      <div className="d-flex justify-content-between gap-3 mb-3"><p className="eyebrow mb-0">{dateLabel(selectedEvent.action.dueOn)} · {session.timezone}</p><button className="btn btn-outline-dark btn-sm" onClick={()=>setSelectedEvent(undefined)}>Close</button></div>
      {eventCard(selectedEvent)}
    </dialog>}
  </>
}
