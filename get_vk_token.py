#!/usr/bin/env python3
import urllib.parse, urllib.request, json

CLIENT_ID = "54518630"
REDIRECT_URI = "https://oauth.vk.com/blank.html"
SCOPE = "photos,wall,groups,offline"

# Защищённый ключ из настроек VK приложения
CLIENT_SECRET = input("Вставь Защищённый ключ из настроек приложения: ").strip()

auth_url = (
    f"https://oauth.vk.com/authorize"
    f"?client_id={CLIENT_ID}"
    f"&redirect_uri={urllib.parse.quote(REDIRECT_URI)}"
    f"&response_type=code"
    f"&scope={SCOPE}"
    f"&display=page"
)

print("\n1. Открой эту ссылку в браузере:")
print(auth_url)
print("\n2. Авторизуйся и разреши доступ.")
print("3. Браузер перекинет на страницу вида:")
print("   https://oauth.vk.com/blank.html?code=XXXX")
print("4. Скопируй значение code= из URL и вставь сюда:\n")

code = input("code: ").strip()

data = urllib.parse.urlencode({
    "grant_type": "authorization_code",
    "code": code,
    "redirect_uri": REDIRECT_URI,
    "client_id": CLIENT_ID,
    "client_secret": CLIENT_SECRET,
}).encode()

req = urllib.request.Request("https://oauth.vk.com/access_token", data=data, method="POST")
req.add_header("Content-Type", "application/x-www-form-urlencoded")

try:
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read())
    if "access_token" in result:
        print(f"\n✅ Токен получен!")
        print(f"\naccess_token = {result['access_token']}")
    else:
        print(f"\n❌ Ошибка: {result}")
except Exception as e:
    print(f"\n❌ Ошибка: {e}")
