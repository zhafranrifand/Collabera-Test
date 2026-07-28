import assert from "node:assert/strict";

export const BASE_URL = (
  process.env.GOREST_BASE_URL ?? "https://gorest.co.in/public/v2"
).replace(/\/$/, "");

export const TOKEN = process.env.GOREST_TOKEN?.trim();

export function buildUniqueUser() {
  const uniqueSuffix = `${Date.now()}-${crypto.randomUUID()}`;

  return {
    name: "Zhafran Rifandi QA Assessment",
    gender: "male",
    email: `zhafran.rifandi.qa.${uniqueSuffix}@example.com`,
    status: "active"
  };
}

export async function apiRequest(path, { method = "GET", token, body } = {}) {
  const headers = {
    Accept: "application/json"
  };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });

  const responseText = await response.text();
  let responseBody = null;

  if (responseText) {
    try {
      responseBody = JSON.parse(responseText);
    } catch {
      responseBody = responseText;
    }
  }

  return { response, body: responseBody };
}

export function assertHttpStatus(result, expectedStatus) {
  assert.equal(
    result.response.status,
    expectedStatus,
    `Expected HTTP ${expectedStatus}, received ${result.response.status}. Response: ${JSON.stringify(result.body)}`
  );
}
