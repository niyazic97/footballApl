#!/usr/bin/env python3
import urllib.parse, webbrowser, hashlib, base64, os, secrets, json
import urllib.request, ssl

CLIENT_ID = "54518630"
REDIRECT_URI = "https://oauth.vk.com/blank.html"

# PKCE
code_verifier = base64.urlsafe_b64encode(os.urandom(32)).rstrip(b'=').decode()
code_challenge = base64.urlsafe_b64encode(
    hashlib.sha256(code_verifier.encode()).digest()
).rstrip(b'=').decode()
state = secrets.token_urlsafe(24)

auth_url = (
    f"https://id.vk.ru/authorize"
    f"?response_type=code"
    f"&client_id={CLIENT_ID}"
    f"&redirect_uri={urllib.parse.quote(REDIRECT_URI, safe='')}"
    f"&state={state}"
    f"&code_challenge={code_challenge}"
    f"&code_challenge_method=S256"
    f"&scope=photos+wall+groups+offline"
)

print("\nОткрываю браузер для авторизации...")
webbrowser.open(auth_url)
print(f"\nЕсли браузер не открылся:\n{auth_url}\n")
print("После авторизации браузер перейдёт на страницу (может быть 404).")
print("Скопируй полный URL из адресной строки браузера и вставь сюда:")

url = input("URL: ").strip()
parsed = urllib.parse.urlparse(url)
params = urllib.parse.parse_qs(parsed.query)

code = params.get("code", [None])[0]
device_id = params.get("device_id", [None])[0]

if not code:
    print(f"\n❌ Не найден code в URL: {url}")
    exit(1)

print(f"\nПолучен code, обмениваю на токен...")

token_data = urllib.parse.urlencode({
    "grant_type": "authorization_code",
    "code": code,
    "code_verifier": code_verifier,
    "redirect_uri": REDIRECT_URI,
    "client_id": CLIENT_ID,
    "device_id": device_id or "",
    "state": state,
}).encode()

req = urllib.request.Request(
    "https://id.vk.ru/oauth2/auth",
    data=token_data,
    headers={"Content-Type": "application/x-www-form-urlencoded"},
    method="POST"
)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

try:
    with urllib.request.urlopen(req, context=ctx) as resp:
        result = json.loads(resp.read())
except urllib.error.HTTPError as e:
    result = json.loads(e.read())

print(f"\nОтвет VK:\n{json.dumps(result, indent=2, ensure_ascii=False)}")
if "access_token" in result:
    print(f"\n✅ Токен получен!\naccess_token = {result['access_token']}")
    if "refresh_token" in result:
        print(f"refresh_token = {result['refresh_token']}")
