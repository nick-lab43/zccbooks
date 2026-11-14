# Church positions and member appointments

**Delivery:** Pull request #36 — date-effective member appointments.

## Purpose

Church appointments describe a member's church office, position or responsibility. They are separate from member classifications and application access roles. Recording a church appointment never grants software permissions.

## Governance

- Central Administration owns the church-position catalogue.
- Position codes are unique without regard to letter case.
- Only the global Central Administrator can create or change catalogue entries.
- Local appointment assignment requires `MEMBERS_MANAGE` permission for both the member's current Cell and the selected appointment scope.
- The appointment scope must be the member's Cell or an ancestor of that Cell in the same organisation.
- Supported appointment scopes are Nyika, Tabhera and Cell.
- Only active members and active geographical scopes can receive new appointments.

## Appointment history

Each appointment stores:

- Member and centrally configured position.
- Geographical scope.
- Effective start date and optional effective end date.
- Optional explanatory note.
- Administrator who recorded the appointment and creation timestamp.

Appointments are not deleted. Ending an appointment sets its effective end date and creates an audit event. An end date cannot precede its start date.

A position may optionally permit concurrent appointments at different scopes. When it does not, overlapping date ranges for the same member and position are rejected across all scopes. When it does, overlapping appointments are still rejected at the same scope.

## API

- `GET /api/appointments/positions` — list active position definitions for administrators with membership-view access.
- `POST /api/appointments/positions` — create a position; Central Administration only.
- `PATCH /api/appointments/positions/{positionId}` — update or deactivate a position; Central Administration only.
- `GET /api/members/{memberId}/appointments` — read current and historical appointments within the member's permitted Cell scope.
- `POST /api/members/{memberId}/appointments` — record a date-effective appointment.
- `PATCH /api/appointments/{appointmentId}/end` — end an appointment without deleting its history.

## Data integrity

Flyway migration V10 adds `church_positions` and `member_appointments`. It is additive and does not modify released migrations. Composite foreign keys keep appointments within the same organisation as the member and geographical scope.

Position changes, appointment creation and appointment ending are recorded in the existing append-only administrator audit log. Financial transactions and member appointment records remain separate domains.
