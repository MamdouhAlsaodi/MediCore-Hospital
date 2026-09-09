import { apiFetch } from '../../api.js';

// The only staff transport adapter (plan1.md Task 8). The professional
// directory behind appointment professional selection is the existing
// GET /api/staff contract (StaffMemberDtos.StaffMemberResponse: id,
// employeeCode, fullName, profession, licenseNumber, department). Every
// request goes through the shared apiFetch so session headers, JSON parsing,
// 401 expiry handling, and error mapping stay in one place. Components never
// call fetch directly.
export function fetchStaff({ token, onUnauthorized } = {}) {
  return apiFetch('/api/staff', { method: 'GET', token, onUnauthorized });
}

// Selection label: name plus role/specialty as available. Never invents a
// fallback for a record-shaped value — callers render an honest placeholder
// when the result is empty.
export function staffDisplayName(member) {
  const name = typeof member?.fullName === 'string' ? member.fullName.trim() : '';
  if (!name) return '';
  const profession = typeof member?.profession === 'string' ? member.profession.trim() : '';
  const department = typeof member?.department === 'string' ? member.department.trim() : '';
  if (profession && department) return `${name} — ${profession} (${department})`;
  if (profession) return `${name} — ${profession}`;
  if (department) return `${name} (${department})`;
  return name;
}
