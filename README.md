# Collabera QA Engineer API Assessment

Runnable API tests for the GoRest Users API, prepared by Zhafran Rifandi.

## Test coverage

### Scenario 1 - Create a user

- Sends `POST /users` with name, gender, a unique email, and status.
- Verifies the response status is `201 Created`.
- Verifies the returned `id` is a numeric integer.
- Verifies the returned user data matches the request.
- Deletes the test user after validation to keep the sandbox clean.

### Scenario 2 - Validate the first user's status

- Sends `GET /users?page=1&per_page=1`.
- Verifies the response status is `200 OK`.
- Verifies the response is a non-empty array.
- Verifies the first entry's status is either `active` or `inactive`.

## Prerequisites

- Node.js 20 or newer.
- A GoRest personal access token for Scenario 1. Sign in at <https://gorest.co.in/consumer/login> and create a token.

No npm packages are required. The project uses Node.js's built-in test runner and `fetch` API.

Reviewers who prefer Postman can also import the collection in `postman/`. Both runners cover the same two assessment scenarios.

## Run the full assessment

Set the access token as an environment variable. Do not save a real token in the repository.

PowerShell:

```powershell
$env:GOREST_TOKEN="your_token_here"
npm test
```

macOS or Linux:

```bash
export GOREST_TOKEN="your_token_here"
npm test
```

Scenario 1 is reported as skipped when `GOREST_TOKEN` is not set. Once the token is set, `npm test` executes both scenarios.

## Run the public scenario without a token

```bash
npm run test:public
```

## Run with Postman

1. Import `postman/Zhafran_Rifandi_GoRest_Assessment.postman_collection.json` into Postman.
2. Open the collection's **Variables** tab.
3. Enter the GoRest token as the **current value** of `gorestToken`. Keep the initial value blank so the token is not exported or committed.
4. Run the collection in its listed order.

The collection generates a unique email for every run, validates both scenarios, and deletes the created test user at the end.

## Optional configuration

The suite uses `https://gorest.co.in/public/v2` by default. Override it when needed:

PowerShell:

```powershell
$env:GOREST_BASE_URL="https://gorest.co.in/public/v2"
```

macOS or Linux:

```bash
export GOREST_BASE_URL="https://gorest.co.in/public/v2"
```

## Project structure

```text
.
|-- tests/
|   |-- create-user.test.mjs
|   |-- helpers.mjs
|   `-- user-status.test.mjs
|-- postman/
|   `-- Zhafran_Rifandi_GoRest_Assessment.postman_collection.json
|-- .env.example
|-- .gitignore
|-- package.json
`-- README.md
```

## Troubleshooting

- `401 Unauthorized`: confirm `GOREST_TOKEN` exists in the same terminal session and has not been revoked.
- `422 Unprocessable Entity`: inspect the response body. The test generates a unique email on every run to avoid duplicate-email failures.
- `429 Too Many Requests`: wait for the GoRest rate-limit window to reset, then rerun the tests.

API documentation: <https://gorest.co.in/>
