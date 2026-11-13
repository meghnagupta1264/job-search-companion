import { ApiError, request, write } from './client'

// This DTO deliberately excludes the database password hash.
export interface Session { id: string; username: string; timezone: string }
export interface SetupInput { username: string; password: string; timezone: string }

export async function getSession(): Promise<Session | null> {
  try { return await request<Session>('/api/session') }
  catch (error) {
    // Anonymous is normal; network/database failures must remain visible errors.
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}
export function getSetupStatus() {
  return request<{ setupRequired: boolean }>('/api/session/setup-status')
}

export function setupAccount(input: SetupInput) {
  return write<void>('/api/session/setup', JSON.stringify(input), 'application/json')
}

export function login(username: string, password: string) {
  // Spring's authentication filter expects form-encoded credentials.
  return write<void>('/api/session/login', new URLSearchParams({ username, password }), 'application/x-www-form-urlencoded')
}

export function logout() {
  return write<void>('/api/session/logout', '', 'application/x-www-form-urlencoded')
}
