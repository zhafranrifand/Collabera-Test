import assert from "node:assert/strict";
import test from "node:test";

import { apiRequest, assertHttpStatus } from "./helpers.mjs";

test(
  "Scenario 2: verify the first user's status is active or inactive",
  { timeout: 30_000 },
  async () => {
    const listResult = await apiRequest("/users");

    assertHttpStatus(listResult, 200);
    assert.ok(Array.isArray(listResult.body), "The response body must be an array.");
    assert.ok(listResult.body.length > 0, "The response must contain at least one user.");

    const firstUser = listResult.body[0];
    assert.ok(
      ["active", "inactive"].includes(firstUser.status),
      `Expected status to be active or inactive, received ${JSON.stringify(firstUser.status)}.`
    );
  }
);
