import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, Navigate, Route, Routes } from 'react-router-dom'
import { getHealth } from './api/health'
import { getSession, logout } from './api/session'
import type { Session } from './api/session'
import { LoginPage } from './auth/LoginPage'

function Workspace({ session }: { session: Session }) {
  const client = useQueryClient()
  const health = useQuery({ queryKey: ['health'], queryFn: getHealth, retry: false })
  const signOut = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      // Remove private cached data before another account/session can see the UI.
      await client.cancelQueries()
      // Preserve the active session query so its observers receive the logged-out state.
      client.setQueryData(['session'], null)
      client.removeQueries({ predicate: query => query.queryKey[0] !== 'session' })
    },
  })
  return <>
    <header className="bg-white border-bottom"><div className="container d-flex flex-wrap gap-3 justify-content-between align-items-center py-3">
      <Link to="/" className="text-decoration-none text-dark fw-semibold">Job Search Companion</Link>
      <div className="d-flex gap-3 align-items-center"><span className="small text-secondary">{session.username}</span><button className="btn btn-outline-dark btn-sm" disabled={signOut.isPending} onClick={() => signOut.mutate()}>{signOut.isPending ? 'Signing out…' : 'Sign out'}</button></div>
    </div></header>
    <main className="container py-5"><div className="companion-panel mx-auto">
      {signOut.isError && <p className="alert alert-danger" role="alert">{signOut.error.message}</p>}
      <p className="eyebrow">Your workspace</p><h1 className="display-6 fw-semibold">Hello, {session.username}.</h1>
      <p className="text-secondary">You’re signed in. Your applications, follow-ups, and interviews will live here as we build the next features.</p>
      <section className="card border-0 shadow-sm mt-4"><div className="card-body p-4"><h2 className="h5">Account ready</h2><p className="mb-1">Timezone: {session.timezone}</p><p className="text-secondary mb-0">Refresh this page to pick up your existing session.</p></div></section>
      <section className="mt-4" aria-labelledby="connection-title"><h2 id="connection-title" className="h6">Backend connection</h2><div aria-live="polite">{health.isPending ? 'Checking…' : health.isError ? health.error.message : health.data?.application + ': ' + health.data?.status}</div></section>
    </div></main>
  </>
}

export default function App() {
  const client = useQueryClient()
  // The server cookie is the source of truth; a refresh never trusts localStorage.
  const session = useQuery({ queryKey: ['session'], queryFn: getSession, retry: false })
  useEffect(() => {
    const expired = () => {
      void client.cancelQueries().then(() => {
        client.setQueryData(['session'], null)
        client.removeQueries({ predicate: query => query.queryKey[0] !== 'session' })
      })
    }
    window.addEventListener('session-expired', expired)
    return () => window.removeEventListener('session-expired', expired)
  }, [client])
  if (session.isPending) return <p className="container py-5" role="status">Opening your workspace…</p>
  if (session.isError) return <main className="container py-5"><p className="alert alert-danger" role="alert">{session.error.message}</p><button className="btn btn-dark" onClick={() => void session.refetch()}>Try again</button></main>

  // Gate all workspace routes, including direct URLs and browser back navigation.
  return <Routes>
    <Route path="/login" element={session.data ? <Navigate to="/" replace /> : <LoginPage />} />
    <Route path="/" element={session.data ? <Workspace session={session.data} /> : <Navigate to="/login" replace />} />
    <Route path="*" element={session.data ? <main className="container py-5"><h1>Page not found</h1><Link to="/">Return home</Link></main> : <Navigate to="/login" replace />} />
  </Routes>
}
