Each *.py here adds sample endpoints to mock_server.py:

    def route(h, method: str, path: str, q: dict, s) -> bool

h is the request handler (h.send_json(obj, code), h.body(), h.server.server_port), s the server's State (s.t is the
language's TEXT, s.profile, s.messages, s.lock). Return True when the request was answered. Modules are tried in name order,
before the built-in endpoints, so they may also replace one. Files starting with "_" are skipped.
