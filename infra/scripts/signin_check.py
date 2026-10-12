#!/usr/bin/env python3
"""Headless authorization-code + PKCE sign-in of the bootstrap administrator.

Proves the realm import works end to end: the user from BOOTSTRAP_ADMIN_EMAIL
can sign in with the temporary password, is forced to set a new one, and gets an
access token with audience tender-api, azp tender-spa and one realm role.
Development only. Prints claims (no tokens or passwords).
"""
import base64, hashlib, html, http.cookiejar, json, os, re, secrets, sys
import urllib.error, urllib.parse, urllib.request

KC = os.environ["KEYCLOAK_PUBLIC_URL"].rstrip("/")
SPA = os.environ["SPA_BASE_URL"].rstrip("/")
EMAIL = os.environ["BOOTSTRAP_ADMIN_EMAIL"]
TEMP = os.environ["BOOTSTRAP_ADMIN_TEMP_PASSWORD"]
NEW = os.environ.get("BOOTSTRAP_ADMIN_NEW_PASSWORD", "dev-Changed-Password-1x")
REALM = f"{KC}/realms/tender-eval/protocol/openid-connect"
REDIRECT = SPA + "/"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


# Keycloak marks its cookies Secure even over http://localhost, which browsers
# accept; the stdlib jar must be told to send them too.
jar = http.cookiejar.CookieJar(http.cookiejar.DefaultCookiePolicy(secure_protocols=("http", "https")))
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)


def call(url, data=None):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    try:
        r = opener.open(urllib.request.Request(url, data=body), timeout=20)
        return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def form_action(page):
    m = re.search(r'<form[^>]*action="([^"]+)"', page)
    return html.unescape(m.group(1)) if m else None


def run(password):
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    q = urllib.parse.urlencode({
        "client_id": "tender-spa", "redirect_uri": REDIRECT, "response_type": "code",
        "scope": "openid", "state": "s", "code_challenge": challenge, "code_challenge_method": "S256"})
    st, h, page = call(f"{REALM}/auth?{q}")
    action = form_action(page)
    assert st == 200 and action, f"login page not served (status {st})"
    st, h, page = call(action, {"username": EMAIL, "password": password, "credentialId": ""})
    for _ in range(6):
        loc = h.get("Location") or h.get("location")
        if loc and loc.startswith(REDIRECT):
            code = urllib.parse.parse_qs(urllib.parse.urlparse(loc).query)["code"][0]
            st, _, tok = call(f"{REALM}/token", {
                "grant_type": "authorization_code", "client_id": "tender-spa",
                "redirect_uri": REDIRECT, "code": code, "code_verifier": verifier})
            assert st == 200, f"token endpoint refused the code (status {st})"
            return json.loads(tok)["access_token"]
        if loc:
            st, h, page = call(loc)
            continue
        action = form_action(page)
        if action is None:
            return None
        if "password-new" in page:
            st, h, page = call(action, {"password-new": NEW, "password-confirm": NEW})
        elif "Invalid username or password" in page:
            return None
        else:
            st, h, page = call(action, {})
    return None


token = run(NEW) or run(TEMP)
if not token:
    sys.exit("sign-in failed for the bootstrap administrator")
claims = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "=="))
aud = claims.get("aud")
aud = [aud] if isinstance(aud, str) else aud
checks = {
    "iss": claims.get("iss") == f"{KC}/realms/tender-eval",
    "aud contains tender-api": "tender-api" in (aud or []),
    "azp is tender-spa": claims.get("azp") == "tender-spa",
    "exactly one role": claims.get("roles") == ["procurement_admin"],
}
for k, v in checks.items():
    print(("ok   " if v else "FAIL ") + k)
print("claims:", json.dumps({k: claims.get(k) for k in ("iss", "aud", "azp", "roles", "email")}))
sys.exit(0 if all(checks.values()) else 1)
