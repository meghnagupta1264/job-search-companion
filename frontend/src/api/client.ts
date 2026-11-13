// Centralize cookie handling and error parsing so every feature uses the same contract.
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: Record<string, string>

  constructor(status: number, code: string, message: string, fields: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fields
  }
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store' })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the app. Check that the backend is running.')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    // Notify the route gate when a protected feature discovers an expired session.
    if (response.status === 401 && path !== '/api/session' && path !== '/api/session/login') {
      window.dispatchEvent(new Event('session-expired'))
    }
    throw new ApiError(response.status, body?.code ?? 'REQUEST_FAILED',
      body?.message ?? 'Something went wrong. Please try again.', body?.fieldErrors ?? {})
  }
  // Login, logout, and setup intentionally return no JSON body.
  return response.status === 204 || response.status === 201 ? undefined as T : response.json()
}

export async function write<T>(path: string, body: BodyInit, contentType: string): Promise<T> {
  // Login and logout rotate CSRF tokens. Fetch one immediately before each write;
  // never persist tokens or passwords in localStorage, and never auto-retry a POST.
  const csrf = await request<{ headerName: string; token: string }>('/api/csrf')
  return request<T>(path, {
    method: 'POST', body,
    headers: { 'Content-Type': contentType, [csrf.headerName]: csrf.token },
  })
}
