import type { ActionSnapshot } from './actions'
import { request, write } from './client'

// Keep HTTP DTOs separate from form state; an empty date becomes null on the wire.
export const statuses = ['SAVED', 'APPLIED', 'INTERVIEWING', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'] as const
export type ApplicationStatus = typeof statuses[number]
export interface ApplicationInput {
  company: string; role: string; postingUrl: string; source: string; location: string
  jobDescription: string; status: ApplicationStatus; appliedOn: string | null; version?: number
}
export interface JobApplication extends ApplicationInput {
  id: string; version: number; createdAt: string; updatedAt: string
}
export interface ApplicationPage {
  items: JobApplication[]; page: number; size: number; totalElements: number; totalPages: number
}
export function listApplications(params: URLSearchParams) {
  return request<ApplicationPage>('/api/applications?' + params.toString())
}
export function getApplication(id: string) {
  return request<JobApplication>('/api/applications/' + encodeURIComponent(id))
}
export function getSources() { return request<string[]>('/api/applications/sources') }
export function saveApplication(input: ApplicationInput, id?: string) {
  return write<JobApplication>('/api/applications' + (id ? '/' + encodeURIComponent(id) : ''),
    JSON.stringify(input), 'application/json', id ? 'PATCH' : 'POST')
}
export function statusLabel(status: ApplicationStatus) {
  return status.charAt(0) + status.slice(1).toLowerCase()
}
export function dateLabel(value: string | null) {
  // A date-only value must not shift to the previous day in another timezone.
  return value ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' })
    .format(new Date(value + 'T00:00:00Z')) : 'Not applied yet'
}

// Timeline entries are immutable snapshots; owner information is never part of these DTOs.
export interface Activity {
  id: string; type: 'APPLICATION_CREATED' | 'STATUS_CHANGED' | 'NOTE' | 'ACTION_CREATED' | 'ACTION_UPDATED' | 'ACTION_COMPLETED' | 'ACTION_CANCELLED'; content: string
  previousStatus: ApplicationStatus | null; status: ApplicationStatus | null; createdAt: string
  beforeAction: ActionSnapshot | null; afterAction: ActionSnapshot | null
}
export interface ActivityPage { items: Activity[]; page: number; size: number; totalElements: number; totalPages: number }
export function getActivities(id: string, page: number) {
  return request<ActivityPage>('/api/applications/' + encodeURIComponent(id) + '/activities?page=' + page + '&size=20')
}
export function addNote(id: string, content: string) {
  return write<Activity>('/api/applications/' + encodeURIComponent(id) + '/notes', JSON.stringify({ content }), 'application/json')
}
export function changeStatus(app: JobApplication, status: ApplicationStatus, appliedOn?: string) {
  return write<JobApplication>('/api/applications/' + encodeURIComponent(app.id) + '/status',
    JSON.stringify({ status, version: app.version, appliedOn: appliedOn || null }), 'application/json', 'PATCH')
}
