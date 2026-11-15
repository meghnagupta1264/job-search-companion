import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { getApplication, saveApplication, statuses, statusLabel } from '../api/applications'
import type { ApplicationInput, JobApplication } from '../api/applications'
import { ErrorState } from './Shared'

export function ApplicationFormPage() {
  const { id } = useParams()
  const application = useQuery({ queryKey: ['application', id], queryFn: () => getApplication(id!), enabled: Boolean(id), retry: false,
    // Never replace a user's unsaved edits just because the browser regains focus.
    refetchOnWindowFocus: false, staleTime: Infinity })
  if (id && application.isPending) return <p role="status">Loading application…</p>
  if (id && application.isError) return <ErrorState error={application.error} retry={() => void application.refetch()} />
  return <ApplicationForm key={id ? id + '-' + application.data?.version : 'new'} initial={application.data} reload={() => void application.refetch()} />
}

function ApplicationForm({ initial, reload }: { initial?: JobApplication; reload: () => void }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [form, setForm] = useState<ApplicationInput>(() => initial || {
    company: '', role: '', postingUrl: '', source: '', location: '', jobDescription: '', status: 'SAVED', appliedOn: null,
  })
  const save = useMutation({ mutationFn: () => saveApplication(form, initial?.id),
    onSuccess: async app => {
      // Refresh list/source caches and make the detail response available immediately.
      client.setQueryData(['application', app.id], app)
      // Form status changes must refresh the same board and history as drag/drop moves.
      await Promise.all([client.invalidateQueries({ queryKey: ['actions', 'calendar'] }), client.invalidateQueries({ queryKey: ['actions', 'search'] }), client.invalidateQueries({ queryKey: ['dashboard'] }), client.invalidateQueries({ queryKey: ['applications'] }), client.invalidateQueries({ queryKey: ['application-sources'] }), client.invalidateQueries({ queryKey: ['application-board'] }), client.invalidateQueries({ queryKey: ['activities', app.id] }), client.invalidateQueries({ queryKey: ['actions', app.id] })])
      navigate('/applications/' + app.id, { replace: true })
    },
  })
  const fields = save.error instanceof ApiError ? save.error.fieldErrors : {}
  const conflict = save.error instanceof ApiError && save.error.code === 'APPLICATION_CHANGED'
  function change<K extends keyof ApplicationInput>(key: K, value: ApplicationInput[K]) { setForm(current => ({ ...current, [key]: value })) }
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); save.mutate() }
  function fieldError(name: string) { return fields[name] ? <div id={name + '-error'} className="text-danger small mt-1">{fields[name]}</div> : null }
  return <div className="form-panel mx-auto">
    <Link to={initial ? '/applications/' + initial.id : '/applications'} className="small">← {initial ? 'Back to application' : 'All applications'}</Link>
    {/* Editorial headings share the reference palette without changing form labels. */}
    <h1 className="page-title mt-3">{initial ? <>Edit your <em>application</em></> : <>Track a new <em>opportunity</em></>}</h1><p className="text-secondary">Save an interesting role or record an application. Fields marked * are required.</p>
    {save.isError && <div className="alert alert-danger" role="alert">{save.error.message}{conflict && <div className="mt-2"><button className="btn btn-outline-danger btn-sm" onClick={reload}>Reload latest version (discards edits)</button></div>}</div>}
    <form onSubmit={submit} className="card border-0 shadow-sm"><div className="card-body p-4"><fieldset disabled={save.isPending}>
      <div className="row g-3">
        {(['company','role'] as const).map(key => <div className="col-md-6" key={key}><label htmlFor={key} className="form-label">{key === 'company' ? 'Company' : 'Role'} *</label><input id={key} className="form-control" value={form[key]} onChange={e => change(key, e.target.value)} required maxLength={160} aria-invalid={Boolean(fields[key])} aria-describedby={fields[key] ? key + '-error' : undefined} />{fieldError(key)}</div>)}
        <div className="col-md-6"><label htmlFor="status" className="form-label">Status *</label><select id="status" className="form-select" value={form.status} onChange={e => change('status', e.target.value as ApplicationInput['status'])}>{statuses.map(s => <option key={s} value={s}>{statusLabel(s)}</option>)}</select>{fieldError('status')}</div>
        <div className="col-md-6"><label htmlFor="appliedOn" className="form-label">Application date{form.status !== 'SAVED' ? ' *' : ''}</label><input id="appliedOn" type="date" className="form-control" value={form.appliedOn || ''} onChange={e => change('appliedOn', e.target.value || null)} required={form.status !== 'SAVED'} aria-invalid={Boolean(fields.appliedOn)} aria-describedby="date-help" /><div id="date-help" className="form-text">{fields.appliedOn || 'Optional while saved; required once you’ve applied.'}</div></div>
        <div className="col-12"><label htmlFor="postingUrl" className="form-label">Job posting URL</label><input id="postingUrl" type="url" className="form-control" placeholder="https://…" value={form.postingUrl} onChange={e => change('postingUrl', e.target.value)} maxLength={2048} aria-invalid={Boolean(fields.postingUrl)} aria-describedby={fields.postingUrl ? 'postingUrl-error' : undefined} />{fieldError('postingUrl')}</div>
        <div className="col-md-6"><label htmlFor="source" className="form-label">Source</label><input id="source" list="source-suggestions" className="form-control" placeholder="Where did you find it?" value={form.source} onChange={e => change('source', e.target.value)} maxLength={80} aria-invalid={Boolean(fields.source)} /><datalist id="source-suggestions">{['LinkedIn','Company website','Indeed','Naukri','Referral','Other'].map(s => <option key={s} value={s} />)}</datalist>{fieldError('source')}</div>
        <div className="col-md-6"><label htmlFor="location" className="form-label">Location</label><input id="location" className="form-control" placeholder="City, remote, or hybrid" value={form.location} onChange={e => change('location', e.target.value)} maxLength={160} aria-invalid={Boolean(fields.location)} />{fieldError('location')}</div>
        <div className="col-12"><label htmlFor="jobDescription" className="form-label">Job description</label><textarea id="jobDescription" className="form-control" rows={9} value={form.jobDescription} onChange={e => change('jobDescription', e.target.value)} maxLength={30000} aria-invalid={Boolean(fields.jobDescription)} /><div className="form-text">Keep a copy in case the posting disappears. Plain text, up to 30,000 characters.</div>{fieldError('jobDescription')}</div>
      </div>
      <div className="d-flex flex-wrap gap-3 align-items-center mt-4"><button className="btn btn-dark" type="submit">{save.isPending ? 'Saving…' : initial ? 'Save changes' : 'Save application'}</button><Link to={initial ? '/applications/' + initial.id : '/applications'} className="text-secondary">Cancel</Link></div>
    </fieldset></div></form>
  </div>
}
