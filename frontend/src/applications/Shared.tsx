import { Link } from 'react-router-dom'
import { statusLabel } from '../api/applications'
import type { ApplicationStatus } from '../api/applications'

// Shared states give every application screen consistent recovery controls.
export function ErrorState({ error, retry }: { error: Error; retry?: () => void }) {
  return <div className="card border-0 shadow-sm"><div className="card-body p-4"><p className="text-danger" role="alert">{error.message}</p><div className="d-flex gap-3 align-items-center">{retry && <button className="btn btn-outline-dark" onClick={retry}>Try again</button>}<Link to="/applications">Back to applications</Link></div></div></div>
}
export function StatusBadge({ status }: { status: ApplicationStatus }) {
  return <span className={'status-badge status-' + status.toLowerCase()}>{statusLabel(status)}</span>
}
