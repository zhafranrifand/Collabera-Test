import assert from "node:assert/strict";
import test from "node:test";

import {
  TOKEN,
  apiRequest,
  assertHttpStatus,
  buildUniqueUser
} from "./helpers.mjs";

test(
  "Scenario 1: create a user and verify the returned id is numeric",
  {
    skip: TOKEN ? false : "Set GOREST_TOKEN to run the authenticated create-user scenario.",
    timeout: 30_000
  },
  async (t) => {
    const newUser = buildUniqueUser();
    const createResult = await apiRequest("/users", {
      method: "POST",
      token: TOKEN,
      body: newUser
    });

    assertHttpStatus(createResult, 201);
    assert.equal(typeof createResult.body?.id, "number", "The returned id must be numeric.");
    assert.ok(Number.isInteger(createResult.body.id), "The returned id must be an integer.");

    assert.equal(createResult.body.name, newUser.name);
    assert.equal(createResult.body.gender, newUser.gender);
    assert.equal(createResult.body.email, newUser.email);
    assert.equal(createResult.body.status, newUser.status);

    t.after(async () => {
      const deleteResult = await apiRequest(`/users/${createResult.body.id}`, {
        method: "DELETE",
        token: TOKEN
      });

      assertHttpStatus(deleteResult, 204);
    });
  }
);
