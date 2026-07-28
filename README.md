# Collabera API Test

Postman collection for the GoRest Users API assessment, prepared by Zhafran Rifandi.

## Test scenarios

1. Create a user with name, gender, email, and status. Verify that the returned ID is a numeric integer and that the response matches the request.
2. Get the users list and verify that the first user's status is either `active` or `inactive`.
3. Delete the created test user after validation.

## Project structure

```text
.
|-- postman_collection/
|   `-- test/
|       `-- Collabera.postman_collection.json
`-- README.md
```

## Run in Postman

1. Clone or download this repository.
2. Import `postman_collection/test/Collabera.postman_collection.json` into Postman.
3. Open the collection's **Variables** tab.
4. Enter a valid GoRest personal access token as the **current value** of `gorestToken`.
5. Run the collection in its listed order.

The collection generates a unique email for each run and removes the created test user during cleanup. The committed `gorestToken` value is blank; no access token is stored in this repository.

GoRest access tokens can be created at <https://gorest.co.in/consumer/login>.
