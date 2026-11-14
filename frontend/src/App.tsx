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
  return <>
    <header className="bg-white border-bottom"><div className="container d-flex flex-wrap gap-3 justify-content-between align-items-center py-3">
      <div className="d-flex flex-wrap gap-4 align-items-center"><Link to="/" className="text-decoration-none text-dark fw-semibold">Job Search Companion</Link><NavLink to="/applications" className="small text-secondary">Applications</NavLink></div>
      <div className="d-flex gap-3 align-items-center"><span className="small text-secondary">{session.username}</span><button className="btn btn-outline-dark btn-sm" disabled={signOut.isPending} onClick={() => signOut.mutate()}>{signOut.isPending ? 'Signing out…' : 'Sign out'}</button></div>
    </div></header>
    {/* Nested feature routes share authentication, navigation, and logout controls. */}
    <main className="container py-4 py-md-5">
      {signOut.isError && <p className="alert alert-danger" role="alert">{signOut.error.message}</p>}
      <Outlet context={session} />
    </main>
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

  // Gate the entire nested workspace; APIs independently enforce the same login requirement.
  if (!session.data) return <Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route path="*" element={<Navigate to="/login" replace />} />
  </Routes>
  return <Routes>
    <Route path="/login" element={<Navigate to="/applications" replace />} />
    <Route element={<Workspace session={session.data} />}>
      <Route path="/" element={<Navigate to="/applications" replace />} />
      <Route path="/applications" element={<ApplicationList />} />
      <Route path="/applications/new" element={<ApplicationFormPage />} />
      <Route path="/applications/:id" element={<ApplicationDetail />} />
      <Route path="/applications/:id/edit" element={<ApplicationFormPage />} />
      <Route path="*" element={<><h1>Page not found</h1><Link to="/applications">Return to applications</Link></>} />
    </Route>
  </Routes>
}
