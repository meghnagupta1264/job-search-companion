export interface HealthResponse {
  status: string
  application: string
}
export async function getHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health')
  if (!response.ok) throw new Error('Backend returned ' + response.status)
  return response.json()
}
