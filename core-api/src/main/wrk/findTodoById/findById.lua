wrk.method = "POST"

wrk.body = [[
{
  "query": "query { q_demo_findTodoById(id: \"019fc33c-76e4-7aec-8322-eb7396932332\") { id title done note createdAt updatedAt } }"
}
]]

wrk.headers["Content-Type"] = "application/json"
wrk.headers["Accept"] = "application/json"
wrk.headers["x-app-id"] = "79139c45-7fc9-4d63-bc0e-2a6d9ca78d18"
wrk.headers["x-install-id"] = "48c651ed-84d7-4a54-a7be-6ec365bac52c"
wrk.headers["x-client-platform"] = "ios"
