import { apiGet } from './client';
import { Page, Project, Scheme } from './types';

export function fetchProjects(): Promise<Page<Project>> {
  return apiGet<Page<Project>>('/projects?size=50');
}

export function fetchScheme(projectId: string): Promise<Scheme> {
  return apiGet<Scheme>(`/projects/${projectId}/scheme`);
}
