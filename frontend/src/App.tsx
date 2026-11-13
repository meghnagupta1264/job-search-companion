import { useQuery } from '@tanstack/react-query'
import { Route, Routes } from 'react-router-dom'
import { getHealth } from './api/health'

function ConnectionPage() {
  const health = useQuery({ queryKey: ['health'], queryFn: getHealth, retry: false })
  return (
    <main className="container py-5">
      <div className="mx-auto companion-panel">
        <p className="text-uppercase text-secondary small fw-semibold">Job Search Companion</p>
        <h1 className="display-6 fw-semibold">A fresh start for your job search.</h1>
        <p className="text-secondary mt-3">Your workspace is taking shape. Application tracking, follow-ups, and interviews will live here.</p>
        <section className="card border-0 shadow-sm mt-4" aria-labelledby="connection-title">
          <div className="card-body p-4">
            <h2 id="connection-title" className="h5">Connection check</h2>
            <div aria-live="polite">
              {health.isPending && <p className="mb-0">Connecting to the backend…</p>}
              {health.isError && <p className="text-danger mb-0">Cannot reach the backend. Start Spring Boot and try again.</p>}
              {health.data && <p className="text-success mb-0">{health.data.application}: {health.data.status}</p>}
            </div>
            <button className="btn btn-outline-dark btn-sm mt-3" onClick={() => void health.refetch()} disabled={health.isFetching}>Check again</button>
          </div>
        </section>
      </div>
    </main>
  )
}
export default function App() {
  return <Routes><Route path="/" element={<ConnectionPage />} /><Route path="*" element={<main className="container py-5"><h1>Page not found</h1><a href="/">Return home</a></main>} /></Routes>
}
