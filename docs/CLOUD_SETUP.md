# Cloud sign-in

## Default: through the phone's own cloud apps (no setup, no keys)
Tap *Network & cloud → Google Drive / OneDrive / Dropbox*. The app opens Android's folder picker; the user chooses the service
(its official app must be installed and signed in, personal or Microsoft 365 accounts) and taps *Use this folder*.
Files can then be browsed, opened, edited, uploaded, renamed and deleted from MB File Manager. This works for everyone out of the box.

## Optional: direct browser sign-in (one-time setup by the app owner)
If you add the keys below as GitHub Secrets, tapping a service opens the normal sign-in page in the browser instead.

**Users never type keys.** They tap *Network & cloud → Google Drive / OneDrive / Dropbox* and sign in on the normal Google /
Microsoft / Dropbox page in their browser (personal accounts, and Microsoft 365 work / school accounts for OneDrive).

For that to work, the **app itself** must be registered once with each service. You do this once, then add the ids as
**GitHub Secrets**; every build then contains them. (They are public client ids; only the Google "client secret" of a Desktop
client is included too, and Google does not treat that one as confidential.)

| GitHub Secret | From |
|---|---|
| `GDRIVE_CLIENT_ID`, `GDRIVE_CLIENT_SECRET` | Google Cloud Console |
| `MS_CLIENT_ID` | Microsoft Entra (Azure) app registration |
| `DROPBOX_APP_KEY` | Dropbox App Console |

Add them in GitHub: **Settings → Secrets and variables → Actions → New repository secret**. A service with no secret shows
"Not available in this build yet" (users can still use the *Advanced* option to enter their own key).

Redirect address to register everywhere (the app listens on it for a few seconds while the user signs in):

```
http://localhost:53682/
```

## Dropbox
1. <https://www.dropbox.com/developers/apps> → **Create app** → *Scoped access* → *Full Dropbox*.
2. **Permissions**: `files.metadata.read`, `files.metadata.write`, `files.content.read`, `files.content.write` → Submit.
3. **Settings**: add the redirect address above; keep *Allow public clients (PKCE)* on **Allow**.
4. Copy the **App key** → secret `DROPBOX_APP_KEY`.
5. Until you click *Apply for production* only 50 users can link the app.

## OneDrive / Microsoft 365
1. <https://entra.microsoft.com> → *App registrations* → **New registration**.
2. Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**.
3. Redirect URI: platform **Mobile and desktop applications**, value `http://localhost:53682/`.
4. **API permissions** → Microsoft Graph → Delegated → `Files.ReadWrite`, `offline_access`.
5. Copy the **Application (client) ID** → secret `MS_CLIENT_ID`. (Work / school accounts whose admin blocks user consent need their admin to approve once.)

## Google Drive
1. <https://console.cloud.google.com> → new project → **Library → Google Drive API → Enable**.
2. **OAuth consent screen**: *External*; app name, support e-mail, logo. Scope: `.../auth/drive`.
3. **Credentials → OAuth client ID → Desktop app**. Copy **Client ID** and **Client secret** → secrets `GDRIVE_CLIENT_ID`, `GDRIVE_CLIENT_SECRET`.
4. **Important:** the full-Drive scope is a *restricted* scope. While the project is in *Testing* only the Google accounts you add under
   *Test users* (max 100) can sign in, and they must sign in again every 7 days. To let any Google user sign in, Google must
   **verify the app** (a review that includes a security assessment). For private use, adding your own accounts as test users is enough.

## Good to know
* Needs Android 6 or newer (the refresh token is encrypted with the phone's secure key store).
* Google Docs / Sheets / Slides download as Word / Excel / PowerPoint; saving an edited copy back creates a normal file next to the original. Delete on Google Drive moves to the Drive bin.
* Dropbox uploads are limited to 150 MB per file here. OneDrive covers the user's own OneDrive (not SharePoint sites).
