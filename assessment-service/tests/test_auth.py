from app.auth import TokenProvider


def test_token_is_cached_until_near_expiry():
    calls = []
    clock = {"t": 1_000.0}

    def fake_login(url, user, pwd):
        calls.append((url, user, pwd))
        return {"accessToken": f"tok{len(calls)}", "expiresAt": "1970-01-01T00:20:00Z"}   # epoch 1200

    tp = TokenProvider("http://claim", "assessment-service", "secret", login=fake_login, clock=lambda: clock["t"])
    assert tp.bearer() == "Bearer tok1"
    assert tp.bearer() == "Bearer tok1"          # cached
    assert calls == [("http://claim/api/v1/auth/login", "assessment-service", "secret")]

    clock["t"] = 1150.0                          # within 60 s of expiry -> refresh
    assert tp.bearer() == "Bearer tok2"
    tp.invalidate()
    assert tp.bearer() == "Bearer tok3"
