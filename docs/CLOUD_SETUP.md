# Connecting Google Drive, OneDrive and Dropbox

MB File Manager talks to the cloud services directly from your phone. There is no MB Network server in between, and nobody else
sees your files. Because of that, every service needs an **app key (Client ID)** that you create yourself, once, for free.
A Client ID is not a password. Paste it into the app: **Browse → Network & cloud → (service) → Tap to set up**.

For all three services use this **redirect address** (the app listens on it for a few seconds while you sign in):

```
http://localhost:53682/
```

## Dropbox
1. Open <https://www.dropbox.com/developers/apps> → **Create app** → *Scoped access* → *Full Dropbox* → give it any name.
2. **Permissions** tab: tick `files.metadata.read`, `files.metadata.write`, `files.content.read`, `files.content.write` → **Submit**.
3. **Settings** tab: under *Redirect URIs* add `http://localhost:53682/` → **Add**. Make sure *Allow public clients (Implicit Grant & PKCE)* is **Allow**.
4. Copy the **App key** → paste it as *Client ID* in the app.

## OneDrive (Microsoft)
1. Open <https://entra.microsoft.com> → *App registrations* → **New registration**.
2. Supported account types: *Accounts in any organizational directory and personal Microsoft accounts*.
3. Redirect URI: platform **Public client/native (mobile & desktop)**, value `http://localhost:53682/`.
4. **API permissions** → Microsoft Graph → *Delegated* → `Files.ReadWrite` and `offline_access`.
5. Copy the **Application (client) ID** → paste it as *Client ID* in the app.

## Google Drive
1. Open <https://console.cloud.google.com> → create a project → **APIs & Services → Library → Google Drive API → Enable**.
2. **OAuth consent screen**: user type *External*, fill the name and your e-mail, add your own Google account under **Test users**.
3. **Credentials → Create credentials → OAuth client ID → Application type: Desktop app**.
4. Copy the **Client ID** and **Client secret** → paste both in the app. (For a Desktop app Google does not treat the secret as confidential.)
5. While the project is in *Testing* mode Google asks you to sign in again every 7 days. Publishing the project removes that limit
   (Google may show an "unverified app" warning for your own account; that is expected).

## Good to know
* Needs Android 6 or newer (the refresh token is encrypted with the phone's secure key store).
* Google Docs / Sheets / Slides are downloaded as Word / Excel / PowerPoint files. Delete on Google Drive moves to the Drive bin.
* Dropbox uploads are limited to 150 MB per file here.
