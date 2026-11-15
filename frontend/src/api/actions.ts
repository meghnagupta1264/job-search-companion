import { request, write } from './client'

export const actionTypes = ['FOLLOW_UP', 'INTERVIEW', 'PREPARATION', 'ASSIGNMENT', 'OTHER'] as const
export type ActionType = typeof actionTypes[number]
export type ActionStatus = 'PENDING' | 'COMPLETED' | 'CANCELLED'
export interface ActionInput { title: string; type: ActionType; dueOn: string; scheduledTime: string; details: string; version?: number }
export interface JobAction extends ActionInput {
  id: string; applicationId: string; status: ActionStatus; scheduledAt: string | null; timezone: string
  completedAt: string | null; cancelledAt: string | null; cancellationReason: string
  createdAt: string; updatedAt: string; version: number; overdue: boolean
}
export interface ActionSnapshot { id: string; title: string; type: ActionType; status: ActionStatus; dueOn: string; scheduledAt: string | null; details: string; cancellationReason: string }
export interface ActionPage { items: JobAction[]; page: number; size: number; totalElements: number; totalPages: number }
export function getActions(applicationId: string, status: string, page: number) {
  const params = new URLSearchParams({ page: String(page), size: '20' })
  if (status) params.set('status', status)
  return request<ActionPage>('/api/applications/' + encodeURIComponent(applicationId) + '/actions?' + params)
}
export function saveAction(applicationId: string, input: ActionInput, id?: string) {
  return write<JobAction>(id ? '/api/actions/' + encodeURIComponent(id) : '/api/applications/' + encodeURIComponent(applicationId) + '/actions', JSON.stringify(input), 'application/json', id ? 'PATCH' : 'POST')
}
export function finishAction(action: JobAction, cancel: boolean) {
  return write<JobAction>('/api/actions/' + encodeURIComponent(action.id) + (cancel ? '/cancel' : '/complete'), JSON.stringify({ version: action.version }), 'application/json')
}
export function actionTypeLabel(type: ActionType) {
  return ({ FOLLOW_UP: 'Follow-up', INTERVIEW: 'Interview', PREPARATION: 'Preparation', ASSIGNMENT: 'Assignment', OTHER: 'Other' })[type]
}

// Cross-application search includes parent labels while preserving existing action DTOs.
export interface ActionSearchPage { items: { action: JobAction; company: string; role: string }[]; page: number; size: number; totalElements: number; totalPages: number }
export function searchActions(params: URLSearchParams) {
  return request<ActionSearchPage>('/api/actions/search?' + params)
}
