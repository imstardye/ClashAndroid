# Release Signing Credentials

This branch includes a generated signing configuration for the Android application. Because this workspace does not support committing binary artifacts, the release keystore is stored as Base64 text and can be restored locally when needed.

## Keystore
- **Source file**: `release.keystore.b64`
- **Restored file**: `release.keystore`
- **Type**: PKCS12
- **Key alias**: `netpulserelease`
- **Store password**: `jeakwigaPG7bs0KDI4QEsFYO`
- **Key password**: `jeakwigaPG7bs0KDI4QEsFYO` (same as store password)
- **Validity**: 10,000 days

### Restoring the keystore
Run the following command from the repository root to regenerate the binary keystore file before building:

```bash
base64 -d release.keystore.b64 > release.keystore
```

Alternatively, on macOS you can use `openssl base64 -d` if the BSD `base64` utility is unavailable.

## Certificate Fingerprints
- **SHA-1**: `F9:9D:9F:E3:9C:72:AD:9C:7C:3D:55:C7:AA:2A:12:1B:09:4C:DB:81`
- **SHA-256**: `5D:E4:72:12:3E:03:95:15:7C:97:10:F3:56:96:E3:8C:71:83:DB:D6:AD:57:DE:ED:FE:EE:26:B8:CE:A7:70:6A`

## Gradle Configuration
The root `build.gradle.kts` reads credentials from `signing.properties`. When a release build is requested, the build script now restores `release.keystore` from the Base64 file automatically if the binary keystore does not already exist.

Please keep these credentials private if you plan to reuse them outside this isolated branch.
