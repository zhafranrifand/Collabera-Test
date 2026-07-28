# API Test Execution Results

- Execution date: 28 July 2026
- Environment: GoRest Public API v2
- Runner: Node.js built-in test runner
- Result: 2 passed, 0 failed, 0 skipped

## Scenario 1 - Create a user

**Result: Passed**

- The API returned `201 Created`.
- The response contained an `id` with a numeric integer value.
- The returned name, gender, email, and status matched the request.
- The created test user was deleted successfully with `204 No Content` during cleanup.

## Scenario 2 - Validate the first user's status

**Result: Passed**

- The API returned `200 OK`.
- The response was a non-empty array.
- The first user's status was one of the two allowed values: `active` or `inactive`.

## Security note

The personal access token was supplied only as a temporary process environment variable. It is not stored in this repository, collection, test-results file, or source code.
