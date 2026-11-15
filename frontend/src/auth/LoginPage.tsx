import { useSearchParams } from 'react-router-dom'
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { getSession, getSetupStatus, login, setupAccount } from '../api/session'

export function LoginPage() {
  const [params] = useSearchParams()
  const client = useQueryClient()
  const [passwordChanged] = useState(() => client.getQueryData(['password-change-notice']) === true)
  useEffect(() => {client.removeQueries({queryKey:['password-change-notice']})}, [client])
  const setup = useQuery({ queryKey: ['setup-status'], queryFn: getSetupStatus, retry: false })
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [timezone, setTimezone] = useState(() => Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Kolkata')
  const [created, setCreated] = useState(false)
  const [localError, setLocalError] = useState('')
  const isSetup = setup.data?.setupRequired === true

  const submit = useMutation({
    mutationFn: async () => {
      if (isSetup) {
        await setupAccount({ username, password, timezone })
      } else {
        await login(username, password)
        const session = await getSession()
        if (!session) throw new ApiError(401, 'SESSION_MISSING', 'Your session could not be restored. Please sign in again.')
        return session
      }
    },
    onSuccess: async (session) => {
      // Credentials stay only in component memory and are cleared after use.
      setPassword('')
      setConfirmation('')
      if (isSetup) {
        setCreated(true)
        await client.invalidateQueries({ queryKey: ['setup-status'] })
      } else {
        // Prevent an older anonymous session query from overwriting successful login.
        await client.cancelQueries({ queryKey: ['session'] })
        client.setQueryData(['session'], session)
      }
    },
    onError: (error) => {
      setPassword('')
      setConfirmation('')
      // Another tab might have completed first-run setup while this form was open.
      if (error instanceof ApiError && error.code === 'SETUP_CLOSED') {
        void client.invalidateQueries({ queryKey: ['setup-status'] })
      }
    },
  })

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLocalError('')
    if (isSetup && password !== confirmation) { setLocalError('The passwords do not match.'); return }
    if (isSetup && new TextEncoder().encode(password).length > 72) {
      setLocalError('Password must be at most 72 UTF-8 bytes.'); return
    }
    submit.mutate()
  }
  const fields = submit.error instanceof ApiError ? submit.error.fieldErrors : {}
  if (setup.isPending) return <p className="container py-5" role="status">Checking account setup…</p>
  if (setup.isError) return <main className="container py-5"><div className="alert alert-danger" role="alert">{setup.error.message}</div><button className="btn btn-dark" onClick={() => void setup.refetch()}>Try again</button></main>

  return (
    <main className="auth-layout">
      {/* The entry screen shares the forest/ivory palette with the signed-in workspace. */}
      <aside className="auth-intro"><p className="auth-brand">Job Search<br />Companion</p><p className="auth-tagline">Your next chapter<br />starts with a <em>little clarity.</em></p><span>Personal tracking system</span></aside>
      <div className="auth-panel">
        <p className="eyebrow">Job Search Companion</p>
        <h1 className="page-title">{isSetup ? 'Make room for your next chapter.' : 'Welcome back.'}</h1>
        <p className="text-secondary mb-4">{isSetup ? 'Create your private account to get started. This app supports one owner.' : 'Sign in to your personal job search workspace.'}</p>
        <div className="card border-0 shadow-sm"><div className="card-body p-4 p-sm-5">
          <h2 className="h4 mb-4">{isSetup ? 'Create your account' : 'Sign in'}</h2>
          {(passwordChanged || params.get('passwordChanged')==='1') && !isSetup && <p className="alert alert-success" role="status">Password changed. Sign in with your new password.</p>}
          {created && !isSetup && <p className="alert alert-success" role="status">Account created. Sign in with your new password.</p>}
          {(localError || submit.isError) && <p className="alert alert-danger" role="alert">{localError || submit.error?.message}</p>}
          <form onSubmit={handleSubmit}>
            <fieldset disabled={submit.isPending}>
              <div className="mb-3">
                <label className="form-label" htmlFor="username">Username</label>
                <input id="username" className="form-control" value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" required minLength={isSetup ? 3 : undefined} maxLength={40} aria-invalid={Boolean(fields.username)} aria-describedby="username-help" />
                <div id="username-help" className={fields.username ? 'text-danger small mt-1' : 'form-text'}>{fields.username || (isSetup ? '3–40 letters, numbers, dots, underscores, or hyphens. Case-insensitive.' : 'Usernames are case-insensitive.')}</div>
              </div>
              <div className="mb-3">
                <label className="form-label" htmlFor="password">Password</label>
                <input id="password" className="form-control" type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete={isSetup ? 'new-password' : 'current-password'} required minLength={isSetup ? 12 : undefined} maxLength={72} aria-invalid={Boolean(fields.password)} aria-describedby={isSetup || fields.password ? 'password-help' : undefined} />
                {(isSetup || fields.password) && <div id="password-help" className={fields.password ? 'text-danger small mt-1' : 'form-text'}>{fields.password || 'At least 12 characters, up to 72 UTF-8 bytes. A long passphrase works well.'}</div>}
              </div>
              {isSetup && <>
                <div className="mb-3">
                  <label className="form-label" htmlFor="confirmation">Confirm password</label>
                  <input id="confirmation" className="form-control" type="password" value={confirmation} onChange={e => setConfirmation(e.target.value)} autoComplete="new-password" required maxLength={72} />
                </div>
                <div className="mb-4">
                  <label className="form-label" htmlFor="timezone">Timezone</label>
                  <input id="timezone" className="form-control" value={timezone} onChange={e => setTimezone(e.target.value)} required maxLength={100} aria-invalid={Boolean(fields.timezone)} aria-describedby="timezone-help" />
                  <div id="timezone-help" className="form-text">{fields.timezone || 'Used for follow-up dates. For example: Asia/Kolkata or Europe/London.'}</div>
                </div>
              </>}
              <button className="btn btn-dark w-100 py-2" type="submit">{submit.isPending ? 'Please wait…' : isSetup ? 'Create account' : 'Sign in'}</button>
            </fieldset>
          </form>
        </div></div>
        <p className="text-secondary small mt-3">Runs on your laptop. Your password is stored as a salted hash.</p>
      </div>
    </main>
  )
}
