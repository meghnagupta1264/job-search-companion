import { Settings } from './auth/Settings'
import { WeeklyCalendar } from './applications/WeeklyCalendar'
import { AllActions } from './applications/AllActions'
import { TodayDashboard } from './applications/TodayDashboard'
import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, NavLink, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { ApplicationList } from './applications/ApplicationList'
import { ApplicationFormPage } from './applications/ApplicationForm'
import { ApplicationDetail } from './applications/ApplicationDetail'
import { getSession, logout } from './api/session'
import type { Session } from './api/session'
import { LoginPage } from './auth/LoginPage'

function Workspace({ session }: { session: Session }) {
  const client = useQueryClient()
  const accountMenu = useRef<HTMLDetailsElement>(null)
  const location = useLocation()
  // Native disclosure supplies keyboard activation; Escape/outside clicks dismiss it.
  useEffect(() => {
    const closeOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !accountMenu.current?.contains(event.target) && accountMenu.current) accountMenu.current.open = false
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && accountMenu.current?.open) {
        accountMenu.current.open = false
        accountMenu.current.querySelector('summary')?.focus()
      }
    }
    document.addEventListener('pointerdown', closeOutside)
    document.addEventListener('keydown', escape)
    return () => {document.removeEventListener('pointerdown', closeOutside); document.removeEventListener('keydown', escape)}
  }, [])
  useEffect(() => {if (accountMenu.current) accountMenu.current.open = false}, [location.pathname])
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
        <NavLink to="/actions"><span aria-hidden="true">✓</span> All actions</NavLink>
        <NavLink to="/calendar"><span aria-hidden="true">▦</span> Calendar</NavLink>
      </nav>
      <div className="sidebar-note"><span className="sidebar-flourish" aria-hidden="true">Every next step</span><p>A little clarity for<br />your next chapter.</p></div>

    </aside>
    <header className="workspace-accountbar">
      <details className="account-menu" ref={accountMenu}>
        <summary aria-label="Account menu" title="Account menu"><span aria-hidden="true" className="hamburger-lines"><span /><span /><span /></span></summary>
        <div className="account-menu-panel"><p className="account-menu-identity"><small>Signed in as</small><strong>{session.username}</strong></p>
          <nav aria-label="Account navigation"><Link to="/settings" onClick={()=>{if(accountMenu.current)accountMenu.current.open=false}}>Settings</Link><button disabled={signOut.isPending} onClick={()=>signOut.mutate()}>{signOut.isPending?'Signing out…':'Sign out'}</button></nav>
        </div>
      </details>
    </header>
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
      <Route path="/settings" element={<Settings />} />
      <Route path="/calendar" element={<WeeklyCalendar />} />
      <Route path="/actions" element={<AllActions />} />
      <Route path="/today" element={<TodayDashboard />} />
      <Route path="/applications" element={<ApplicationList />} />
      <Route path="/applications/new" element={<ApplicationFormPage />} />
      <Route path="/applications/:id" element={<ApplicationDetail />} />
      <Route path="/applications/:id/edit" element={<ApplicationFormPage />} />
      <Route path="*" element={<><h1>Page not found</h1><Link to="/applications">Return to applications</Link></>} />
    </Route>
  </Routes>
}
