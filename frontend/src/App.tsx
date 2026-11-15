import { TodayDashboard } from './applications/TodayDashboard'
import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, NavLink, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { ApplicationList } from './applications/ApplicationList'
import { ApplicationFormPage } from './applications/ApplicationForm'
import { ApplicationDetail } from './applications/ApplicationDetail'
import { getSession, logout } from './api/session'
import type { Session } from './api/session'
import { LoginPage } from './auth/LoginPage'

function Workspace({ session }: { session: Session }) {
  const client = useQueryClient()
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
  // A persistent desktop rail becomes a compact top navigation on small screens.
  return <div className="workspace-shell">
    <a href="#workspace-content" className="skip-link">Skip to content</a>
    <aside className="workspace-sidebar" aria-label="Workspace">
      <Link to="/today" className="brand-lockup"><span>Job Search<br />Companion</span><small>Personal tracking system</small></Link>
      <nav className="workspace-nav" aria-label="Main navigation">
        <NavLink to="/today"><span aria-hidden="true">◈</span> Today</NavLink>
        <NavLink to="/applications"><span aria-hidden="true">▤</span> Applications</NavLink>
      </nav>
      <div className="sidebar-note"><span className="sidebar-flourish" aria-hidden="true">Every next step</span><p>A little clarity for<br />your next chapter.</p></div>
      <div className="sidebar-account"><span className="account-label">Signed in as</span><span className="text-break">{session.username}</span><button className="sidebar-signout" disabled={signOut.isPending} onClick={() => signOut.mutate()}>{signOut.isPending ? 'Signing out…' : 'Sign out ↗'}</button></div>
    </aside>
    {/* Feature routes retain the same session context and server-backed behavior. */}
    <main id="workspace-content" className="workspace-content" tabIndex={-1}>
      {signOut.isError && <p className="alert alert-danger" role="alert">{signOut.error.message}</p>}
      <Outlet context={session} />
    </main>
  </div>
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

  // Gate the entire nested workspace; APIs independently enforce the same login requirement.
  if (!session.data) return <Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route path="*" element={<Navigate to="/login" replace />} />
  </Routes>
  return <Routes>
    <Route path="/login" element={<Navigate to="/today" replace />} />
    <Route element={<Workspace session={session.data} />}>
      <Route path="/" element={<Navigate to="/today" replace />} />
      {/* Today is the signed-in landing page; application views remain directly accessible. */}
      <Route path="/today" element={<TodayDashboard />} />
      <Route path="/applications" element={<ApplicationList />} />
      <Route path="/applications/new" element={<ApplicationFormPage />} />
      <Route path="/applications/:id" element={<ApplicationDetail />} />
      <Route path="/applications/:id/edit" element={<ApplicationFormPage />} />
      <Route path="*" element={<><h1>Page not found</h1><Link to="/applications">Return to applications</Link></>} />
    </Route>
  </Routes>
}
