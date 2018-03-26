package attestationserver;

import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;

import com.github.benmanes.caffeine.cache.Cache;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import co.copperhead.attestation.attestation.Attestation;
import co.copperhead.attestation.attestation.AttestationApplicationId;
import co.copperhead.attestation.attestation.AttestationPackageInfo;
import co.copperhead.attestation.attestation.AuthorizationList;
import co.copperhead.attestation.attestation.RootOfTrust;

class AttestationProtocol {
    static final File ATTESTATION_DATABASE = new File("attestation.db");

    static final int CHALLENGE_LENGTH = 32;
    private static final String SIGNATURE_ALGORITHM = "SHA256WithECDSA";
    private static final HashFunction FINGERPRINT_HASH_FUNCTION = Hashing.sha256();
    private static final int FINGERPRINT_LENGTH = FINGERPRINT_HASH_FUNCTION.bits() / 8;

    // Challenge message:
    //
    // byte maxVersion = PROTOCOL_VERSION
    // byte[] challenge index (length: CHALLENGE_LENGTH)
    // byte[] challenge (length: CHALLENGE_LENGTH)
    //
    // The challenge index is randomly generated by Auditor and used for all future challenge
    // messages from that Auditor. It's used on the Auditee as an index to choose the correct
    // persistent key to satisfy the Auditor, rather than only supporting pairing with one. In
    // theory, the Auditor could authenticate to the Auditee, but this app already provides a
    // better way to do that by doing the same process in reverse for a supported device.
    //
    // The challenge is randomly generated by the Auditor and serves the security function of
    // enforcing that the results are fresh. It's returned inside the attestation certificate
    // which has a signature from the device's provisioned key (not usable by the OS) and the
    // outer signature from the hardware-backed key generated for the initial pairing.
    //
    // Attestation message:
    //
    // The Auditor will eventually start trying to be backwards compatible with older Auditee app
    // versions but not the other way around.
    //
    // Compression is done with raw DEFLATE (no zlib wrapper) with a preset dictionary
    // (DEFLATE_DICTIONARY) generated from sample certificates.
    //
    // signed message {
    // byte version = min(maxVersion, PROTOCOL_VERSION)
    // short compressedChainLength
    // byte[] compressedChain { [short encodedCertificateLength, byte[] encodedCertificate] }
    // byte[] fingerprint (length: FINGERPRINT_LENGTH)
    // byte osEnforcedFlags
    // }
    // byte[] signature (rest of message)
    //
    // For each audit, the Auditee generates a fresh hardware-backed key with key attestation
    // using the provided challenge. It reports back the certificate chain to be verified by the
    // Auditor. The public key certificate of the generated key is signed by a key provisioned on
    // the device (not usable by the OS) chaining up to a known Pixel 2 (XL) intermediate and the
    // Google root. The certificate contains the key attestation metadata including the important
    // fields with the lock state, verified boot state, the verified boot public key fingerprint
    // and the OS version / patch level:
    //
    // https://developer.android.com/training/articles/security-key-attestation.html#certificate_schema
    //
    // The Auditee keeps the first hardware-backed key generated for a challenge index and uses it
    // to sign all future attestations. The fingerprint of the persistent key is included in the
    // attestation message for the Auditor to find the corresponding pinning data. Other keys are
    // never actually used, only generated for fresh key attestation data.
    //
    // The OS can use the persistent generated hardware-backed key for signing but cannot obtain
    // the private key. The key isn't be usable if verified boot fails or the OS is downgraded and
    // the keys are protected against replay attacks via the Replay Protected Memory Block. Future
    // devices launching with Android P or later will be able to provide a StrongBox Keymaster to
    // support storing the keys in a dedicated hardware security module paired with the TEE which
    // will substantially reduce the attack surface for obtaining the keys. The attestation API
    // could also be improved with better guarantees about the certificate chain remaining the
    // same, including rollback indexes in key attestation metadata and adding a per-app-install
    // generated intermediate to the chain to be pinned with the others.
    //
    // The attestation message also includes osEnforcedFlags with data obtained at the OS level,
    // which is vulnerable to tampering by an attacker with control over the OS. However, the OS
    // did get verified by verified boot so without a verified boot bypass they would need to keep
    // exploiting it after booting. The bootloader / TEE verified OS version / OS patch level are
    // a useful mitigation as they reveal that the OS isn't upgraded even if an attacker has root.
    //
    // The Auditor saves the initial certificate chain, using the initial certificate to verify
    // the outer signature and the rest of the chain for pinning the expected chain. It enforces
    // downgrade protection for the OS version/patch (bootloader/TEE enforced) and app version (OS
    // enforced) by keeping them updated.
    static final byte PROTOCOL_VERSION = 1;
    // can become longer in the future, but this is the minimum length
    private static final byte CHALLENGE_MESSAGE_LENGTH = 1 + CHALLENGE_LENGTH * 2;
    private static final int MAX_ENCODED_CHAIN_LENGTH = 3000;
    static final int MAX_MESSAGE_SIZE = 2953;

    private static final int OS_ENFORCED_FLAGS_NONE = 0;
    private static final int OS_ENFORCED_FLAGS_USER_PROFILE_SECURE = 1;
    private static final int OS_ENFORCED_FLAGS_ACCESSIBILITY = 1 << 1;
    private static final int OS_ENFORCED_FLAGS_DEVICE_ADMIN = 1 << 2;
    private static final int OS_ENFORCED_FLAGS_ADB_ENABLED = 1 << 3;
    private static final int OS_ENFORCED_FLAGS_ADD_USERS_WHEN_LOCKED = 1 << 4;
    private static final int OS_ENFORCED_FLAGS_ENROLLED_FINGERPRINTS = 1 << 5;
    private static final int OS_ENFORCED_FLAGS_DENY_NEW_USB = 1 << 6;
    private static final int OS_ENFORCED_FLAGS_DEVICE_ADMIN_NON_SYSTEM = 1 << 7;
    private static final int OS_ENFORCED_FLAGS_ALL =
            OS_ENFORCED_FLAGS_USER_PROFILE_SECURE |
            OS_ENFORCED_FLAGS_ACCESSIBILITY |
            OS_ENFORCED_FLAGS_DEVICE_ADMIN |
            OS_ENFORCED_FLAGS_ADB_ENABLED |
            OS_ENFORCED_FLAGS_ADD_USERS_WHEN_LOCKED |
            OS_ENFORCED_FLAGS_ENROLLED_FINGERPRINTS |
            OS_ENFORCED_FLAGS_DENY_NEW_USB |
            OS_ENFORCED_FLAGS_DEVICE_ADMIN_NON_SYSTEM;

    private static final String ATTESTATION_APP_PACKAGE_NAME = "co.copperhead.attestation";
    private static final int ATTESTATION_APP_MINIMUM_VERSION = 7;
    private static final String ATTESTATION_APP_SIGNATURE_DIGEST_DEBUG =
            "17727D8B61D55A864936B1A7B4A2554A15151F32EBCF44CDAA6E6C3258231890";
    private static final String ATTESTATION_APP_SIGNATURE_DIGEST_RELEASE =
            "BE9FDEEE9EB474CEEB57B7795B75B0DFC0970EAA513574BC37A598E153916A8A";
    private static final int OS_VERSION_MINIMUM = 80000;
    private static final int OS_PATCH_LEVEL_MINIMUM = 201801;

    // Offset from version code to user-facing version: version 1 has version code 10, etc.
    private static final int ATTESTATION_APP_VERSION_CODE_OFFSET = 9;
    // Split displayed fingerprint into groups of 4 characters
    private static final int FINGERPRINT_SPLIT_INTERVAL = 4;

    private static final String BKL_L04 = "Huawei Honor View 10 (BKL-L04)";
    private static final String PIXEL_2 = "Google Pixel 2";
    private static final String PIXEL_2_XL = "Google Pixel 2 XL";
    private static final String SM_G960U = "Samsung Galaxy S9 (SM-G960U)";
    private static final String SM_G965F = "Samsung Galaxy S9+ (SM-G965F)";
    private static final String SM_G965_MSM = "Samsung Galaxy S9+ (Snapdragon)";
    private static final String H3113 = "Sony Xperia XA2 (H3113)";

    static class DeviceInfo {
        final String name;
        final int attestationVersion;
        final int keymasterVersion;
        final boolean rollbackResistant;

        DeviceInfo(final String name, final int attestationVersion, final int keymasterVersion,
                final boolean rollbackResistant) {
            this.name = name;
            this.attestationVersion = attestationVersion;
            this.keymasterVersion = keymasterVersion;
            this.rollbackResistant = rollbackResistant;
        }
    }

    static final ImmutableMap<String, DeviceInfo> fingerprintsCopperheadOS = ImmutableMap
            .<String, DeviceInfo>builder()
            .put("36D067F8517A2284781B99A2984966BFF02D3F47310F831FCDCC4D792426B6DF",
                    new DeviceInfo(PIXEL_2, 2, 3, true))
            .put("815DCBA82BAC1B1758211FF53CAA0B6883CB6C901BE285E1B291C8BDAA12DF75",
                    new DeviceInfo(PIXEL_2_XL, 2, 3, true))
            .build();
    static final ImmutableMap<String, DeviceInfo> fingerprintsStock = ImmutableMap
            .<String, DeviceInfo>builder()
            .put("5341E6B2646979A70E57653007A1F310169421EC9BDD9F1A5648F75ADE005AF1",
                    new DeviceInfo(BKL_L04, 2, 3, false))
            .put("1962B0538579FFCE9AC9F507C46AFE3B92055BAC7146462283C85C500BE78D82",
                    new DeviceInfo(PIXEL_2, 2, 3, true))
            .put("171616EAEF26009FC46DC6D89F3D24217E926C81A67CE65D2E3A9DC27040C7AB",
                    new DeviceInfo(PIXEL_2_XL, 2, 3, true))
            .put("266869F7CF2FB56008EFC4BE8946C8F84190577F9CA688F59C72DD585E696488",
                    new DeviceInfo(SM_G960U, 1, 2, false))
            .put("D1C53B7A931909EC37F1939B14621C6E4FD19BF9079D195F86B3CEA47CD1F92D",
                    new DeviceInfo(SM_G965F, 1, 2, false))
            .put("A4A544C2CFBAEAA88C12360C2E4B44C29722FC8DBB81392A6C1FAEDB7BF63010",
                    new DeviceInfo(SM_G965_MSM, 1, 2, false))
            .put("4285AD64745CC79B4499817F264DC16BF2AF5163AF6C328964F39E61EC84693E",
                    new DeviceInfo(H3113, 2, 3, true))
            .build();

    private static final String GOOGLE_ROOT_CERTIFICATE =
            "-----BEGIN CERTIFICATE-----\n" +
            "MIIFYDCCA0igAwIBAgIJAOj6GWMU0voYMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV" +
            "BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMTYwNTI2MTYyODUyWhcNMjYwNTI0MTYy" +
            "ODUyWjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B" +
            "AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS" +
            "Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7" +
            "tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj" +
            "nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq" +
            "C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ" +
            "oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O" +
            "JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg" +
            "sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi" +
            "igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M" +
            "RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E" +
            "aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um" +
            "AGMCAwEAAaOBpjCBozAdBgNVHQ4EFgQUNmHhAHyIBQlRi0RsR/8aTMnqTxIwHwYD" +
            "VR0jBBgwFoAUNmHhAHyIBQlRi0RsR/8aTMnqTxIwDwYDVR0TAQH/BAUwAwEB/zAO" +
            "BgNVHQ8BAf8EBAMCAYYwQAYDVR0fBDkwNzA1oDOgMYYvaHR0cHM6Ly9hbmRyb2lk" +
            "Lmdvb2dsZWFwaXMuY29tL2F0dGVzdGF0aW9uL2NybC8wDQYJKoZIhvcNAQELBQAD" +
            "ggIBACDIw41L3KlXG0aMiS//cqrG+EShHUGo8HNsw30W1kJtjn6UBwRM6jnmiwfB" +
            "Pb8VA91chb2vssAtX2zbTvqBJ9+LBPGCdw/E53Rbf86qhxKaiAHOjpvAy5Y3m00m" +
            "qC0w/Zwvju1twb4vhLaJ5NkUJYsUS7rmJKHHBnETLi8GFqiEsqTWpG/6ibYCv7rY" +
            "DBJDcR9W62BW9jfIoBQcxUCUJouMPH25lLNcDc1ssqvC2v7iUgI9LeoM1sNovqPm" +
            "QUiG9rHli1vXxzCyaMTjwftkJLkf6724DFhuKug2jITV0QkXvaJWF4nUaHOTNA4u" +
            "JU9WDvZLI1j83A+/xnAJUucIv/zGJ1AMH2boHqF8CY16LpsYgBt6tKxxWH00XcyD" +
            "CdW2KlBCeqbQPcsFmWyWugxdcekhYsAWyoSf818NUsZdBWBaR/OukXrNLfkQ79Iy" +
            "ZohZbvabO/X+MVT3rriAoKc8oE2Uws6DF+60PV7/WIPjNvXySdqspImSN78mflxD" +
            "qwLqRBYkA3I75qppLGG9rp7UCdRjxMl8ZDBld+7yvHVgt1cVzJx9xnyGCC23Uaic" +
            "MDSXYrB4I4WHXPGjxhZuCuPBLTdOLU8YRvMYdEvYebWHMpvwGCF6bAx3JBpIeOQ1" +
            "wDB5y0USicV3YgYGmi+NZfhA4URSh77Yd6uuJOJENRaNVTzk\n" +
            "-----END CERTIFICATE-----";

    private static final byte[] DEFLATE_DICTIONARY = BaseEncoding.base64().decode(
            "MIICZjCCAg2gAwIBAgIBATAKBggqhkjOPQQDAjAbMRkwFwYDVQQFExBkNzc1MjM0ODY2ZjM3ZjUz" +
            "MCAXDTE4MDIwNTAxNDM1OVoYDzIxMDYwMjA3MDYyODE1WjAfMR0wGwYDVQQDDBRBbmRyb2lkIEtl" +
            "eXN0b3JlIEtleTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABODxAGPDQUKeGN90LJ30XS5voSvK" +
            "VvEj2a0UP7R6fOy+pob45fFAH1qvqqLv9J6Ajb7PZX7HTpanJ7uaIQ5wpRmjggE6MIIBNjAOBgNV" +
            "HQ8BAf8EBAMCB4AwggEiBgorBgEEAdZ5AgERBIIBEjCCAQ4CAQIKAQECAQMKAQEEIHpMSeMQFv3g" +
            "4qCffZTszv/WNaIc3ePgFDtbvAM/uwLvBAAwZr+DEAgCBgFhY6JZLr+FPQgCBgFhY6Meu7+FRUoE" +
            "SDBGMSAwHgQZY28uY29wcGVyaGVhZC5hdHRlc3RhdGlvbgIBATEiBCAW9DOe5NbEQZ3vCP9JSfcq" +
            "G5CR7Ymx/pRH8xqOO8y8bzB0oQgxBgIBAgIBA6IDAgEDowQCAgEApQUxAwIBBKoDAgEBv4N3AgUA" +
            "v4U+AwIBAL+FPwIFAL+FQCowKAQgFxYW6u8mAJ/EbcbYnz0kIX6SbIGmfOZdLjqdwnBAx6sBAf8K" +
            "AQC/hUEFAgMBOOS/hUIFAgMDFEkwCgYIKoZIzj0EAwIDRwAwRAIgRQm5K1AAPmPc5lcJm3sICuav" +
            "Zfaf3RBuEZHHpmc17YoCIAroE4eLaP5edIVWDGYCR5dTgEY3TOkACdQsQvfZCOKaMIICKTCCAa+g" +
            "AwIBAgIJaDkSRnQoRzlhMAoGCCqGSM49BAMCMBsxGTAXBgNVBAUTEDg3ZjQ1MTQ0NzViYTBhMmIw" +
            "HhcNMTYwNTI2MTcwNzMzWhcNMjYwNTI0MTcwNzMzWjAbMRkwFwYDVQQFExBkNzc1MjM0ODY2ZjM3" +
            "ZjUzMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEqrXOysRNrb+GjpMdrmsXrqq+jyLaahkcgCo6" +
            "rAROyYWOKaERvaFowtGsxkSfMSbqopj3qp//JBOW5iRrHRcp4KOB2zCB2DAdBgNVHQ4EFgQUL78c" +
            "0llO0rDTlgtwnhdE3BoQUEswHwYDVR0jBBgwFoAUMEQj5aL2BuFQq3dfFha7kcxjxlkwDAYDVR0T" +
            "AQH/BAIwADAOBgNVHQ8BAf8EBAMCB4AwJAYDVR0eBB0wG6AZMBeCFWludmFsaWQ7ZW1haWw6aW52" +
            "YWxpZDBSBgNVHR8ESzBJMEegRaBDhkFodHRwczovL2FuZHJvaWQuZ29vZ2xlYXBpcy5jb20vYXR0" +
            "ZXN0YXRpb24vY3JsLzY4MzkxMjQ2NzQyODQ3Mzk2MTAKBggqhkjOPQQDAgNoADBlAjA9rA4BW4Nt" +
            "HoD3nXysHziKlLoAhCup8V4dNmWu6htIt43I3ANmVm7CzetNqgEjNPACMQCBuDKKwLOHBA9a/dHb" +
            "9y8ApGZ+AU6StdxH/rHPYRFq84/5WOmUV7vPeFuRoMPe080wggPDMIIBq6ADAgECAgoDiCZnYGWJ" +
            "loV1MA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNVBAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMTYwNTI2" +
            "MTcwMTUxWhcNMjYwNTI0MTcwMTUxWjAbMRkwFwYDVQQFExA4N2Y0NTE0NDc1YmEwYTJiMHYwEAYH" +
            "KoZIzj0CAQYFK4EEACIDYgAEZDtWaB0n+sSCz2wgTevO8ClcNQwBqowyfz7V9Emu9ClmQl85PYR2" +
            "O12tVrENBFnGLGpPkyVWqJKTw9FOovHf7w48uiJyoyI54bK0faxVC6u8XKdV4qpIYorWPHb/Z9xy" +
            "o4G2MIGzMB0GA1UdDgQWBBQwRCPlovYG4VCrd18WFruRzGPGWTAfBgNVHSMEGDAWgBQ2YeEAfIgF" +
            "CVGLRGxH/xpMyepPEjAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjBQBgNVHR8ESTBH" +
            "MEWgQ6BBhj9odHRwczovL2FuZHJvaWQuZ29vZ2xlYXBpcy5jb20vYXR0ZXN0YXRpb24vY3JsL0U4" +
            "RkExOTYzMTREMkZBMTgwDQYJKoZIhvcNAQELBQADggIBAEA5ios2vJOZs6WeuOci8OhfHNos6AJe" +
            "7b2XsqlsUdpxUvGBi4ZGIgRCrNkGpRhK30+DH4/Yq+ge3P32wRmvbFN7QPDoJMdMCbFZdQV2uW8Q" +
            "ybYbJJ+8lF8w0K5fWghL8zk99ERjZhkfIur+yfWwmvcWNsox1QwGjkBxqZwPcfzCX07/qp+Ff7nu" +
            "JfOgrrIzMlEb8yWSbnz+wWTSmNrJQFyGZQkvQVDoiEpiDLxEoTZQPJco4Tv5kxIxRSQB3PKfY8W/" +
            "tO9C0OTSB7aaRWs2t89KCUzME2+tIMc8GZOS1fPCx5VqAhFPlYj3U6tQ5g8WCiy2x8fjaGznAm0A" +
            "UY/AOD/WY1rxTIf2TVsytGrdKt7PVcbQm7tIY3+41fl2RzC98CZp22Yzs+n6XZUdFhFHrTSMBwzb" +
            "7tuXx6Cp8z8Du/IMtmo8jEPCQtePZ4332clklVoZ8H10fIU6oGoEzpJ3JXoxIivcAeijq74FYhf3" +
            "6ryfWqDkjolZ4R74rnxJtENWg1CCwo/3+I+u5cxTmubbLA/EgJUbKyXUaAA/4Undfqg/S1cVZCVi" +
            "hZ1KWhJUc1lCqPZ6//r2wycaxN4nDVXsjSBH55k0R/F769kPgo/zwrG6I8J73iun4Cqzn9jC4Kjq" +
            "tD4caLk5k0GxBdgi58KVIGN746mNBvscmCKEl3Ojb8gH");

    static byte[] getChallenge() {
        final SecureRandom random = new SecureRandom();
        final byte[] challenge = new byte[CHALLENGE_LENGTH];
        random.nextBytes(challenge);
        return challenge;
    }

    private static byte[] getFingerprint(final Certificate certificate)
            throws CertificateEncodingException {
        return FINGERPRINT_HASH_FUNCTION.hashBytes(certificate.getEncoded()).asBytes();
    }

    private static class Verified {
        final String device;
        final String verifiedBootKey;
        final int osVersion;
        final int osPatchLevel;
        final int appVersion;
        final boolean isStock;

        Verified(final String device, final String verifiedBootKey, final int osVersion,
                final int osPatchLevel, final int appVersion, final boolean isStock) {
            this.device = device;
            this.verifiedBootKey = verifiedBootKey;
            this.osVersion = osVersion;
            this.osPatchLevel = osPatchLevel;
            this.appVersion = appVersion;
            this.isStock = isStock;
        }
    }

    private static X509Certificate generateCertificate(final InputStream in)
            throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    }

    private static Verified verifyStateless(final Certificate[] certificates,
            final Cache<ByteBuffer, Boolean> pendingChallenges, final Certificate root) throws GeneralSecurityException {

        verifyCertificateSignatures(certificates);

        // check that the root certificate is the Google key attestation root
        if (!Arrays.equals(root.getEncoded(), certificates[certificates.length - 1].getEncoded())) {
            throw new GeneralSecurityException("root certificate is not the Google key attestation root");
        }

        final Attestation attestation = new Attestation((X509Certificate) certificates[0]);

        // prevent replay attacks
        final byte[] challenge = attestation.getAttestationChallenge();
        if (pendingChallenges.asMap().remove(ByteBuffer.wrap(challenge)) == null) {
            throw new GeneralSecurityException("challenge not pending");
        }

        // enforce communicating with the attestation app via OS level security
        final AuthorizationList softwareEnforced = attestation.getSoftwareEnforced();
        final AttestationApplicationId attestationApplicationId = softwareEnforced.getAttestationApplicationId();
        final List<AttestationPackageInfo> infos = attestationApplicationId.getAttestationPackageInfos();
        if (infos.size() != 1) {
            throw new GeneralSecurityException("wrong number of attestation packages");
        }
        final AttestationPackageInfo info = infos.get(0);
        if (!ATTESTATION_APP_PACKAGE_NAME.equals(info.getPackageName())) {
            throw new GeneralSecurityException("wrong attestation app package name");
        }
        final int appVersion = info.getVersion();
        if (appVersion < ATTESTATION_APP_MINIMUM_VERSION) {
            throw new GeneralSecurityException("attestation app is too old");
        }
        final List<byte[]> signatureDigests = attestationApplicationId.getSignatureDigests();
        if (signatureDigests.size() != 1) {
            throw new GeneralSecurityException("wrong number of attestation app signature digests");
        }
        final String signatureDigest = BaseEncoding.base16().encode(signatureDigests.get(0));
        if (!ATTESTATION_APP_SIGNATURE_DIGEST_RELEASE.equals(signatureDigest)) {
            if (!BuildConfig.DEBUG || !ATTESTATION_APP_SIGNATURE_DIGEST_DEBUG.equals(signatureDigest)) {
                throw new GeneralSecurityException("wrong attestation app signature digest");
            }
        }

        final AuthorizationList teeEnforced = attestation.getTeeEnforced();

        // verified boot security checks
        final int osVersion = teeEnforced.getOsVersion();
        if (osVersion < OS_VERSION_MINIMUM) {
            throw new GeneralSecurityException("OS version too old");
        }
        final int osPatchLevel = teeEnforced.getOsPatchLevel();
        if (osPatchLevel < OS_PATCH_LEVEL_MINIMUM) {
            throw new GeneralSecurityException("OS patch level too old");
        }
        final RootOfTrust rootOfTrust = teeEnforced.getRootOfTrust();
        if (rootOfTrust == null) {
            throw new GeneralSecurityException("missing root of trust");
        }
        if (!rootOfTrust.isDeviceLocked()) {
            throw new GeneralSecurityException("device is not locked");
        }

        final int verifiedBootState = rootOfTrust.getVerifiedBootState();
        final String verifiedBootKey = BaseEncoding.base16().encode(rootOfTrust.getVerifiedBootKey());
        final DeviceInfo device;
        final boolean stock;
        if (verifiedBootState == RootOfTrust.KM_VERIFIED_BOOT_SELF_SIGNED) {
            device = fingerprintsCopperheadOS.get(verifiedBootKey);
            stock = false;
        } else if (verifiedBootState == RootOfTrust.KM_VERIFIED_BOOT_VERIFIED) {
            device = fingerprintsStock.get(verifiedBootKey);
            stock = true;
        } else {
            throw new GeneralSecurityException("verified boot state is not verified or self signed");
        }

        if (device == null) {
            throw new GeneralSecurityException("invalid key fingerprint");
        }

        // key sanity checks
        if (teeEnforced.getOrigin() != AuthorizationList.KM_ORIGIN_GENERATED) {
            throw new GeneralSecurityException("not a generated key");
        }
        if (teeEnforced.isAllApplications()) {
            throw new GeneralSecurityException("expected key only usable by attestation app");
        }
        if (device.rollbackResistant && !teeEnforced.isRollbackResistant()) {
            throw new GeneralSecurityException("expected rollback resistant key");
        }

        // version sanity checks
        if (attestation.getAttestationVersion() < device.attestationVersion) {
            throw new GeneralSecurityException("attestation version below " + device.attestationVersion);
        }
        if (attestation.getAttestationSecurityLevel() != Attestation.KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT) {
            throw new GeneralSecurityException("attestation security level is software");
        }
        if (attestation.getKeymasterVersion() < device.keymasterVersion) {
            throw new GeneralSecurityException("keymaster version below " + device.keymasterVersion);
        }
        if (attestation.getKeymasterSecurityLevel() != Attestation.KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT) {
            throw new GeneralSecurityException("keymaster security level is software");
        }

        return new Verified(device.name, verifiedBootKey, osVersion, osPatchLevel, appVersion, stock);
    }

    private static void verifyCertificateSignatures(Certificate[] certChain)
            throws GeneralSecurityException {
        for (int i = 1; i < certChain.length; ++i) {
            final PublicKey pubKey = certChain[i].getPublicKey();
            try {
                ((X509Certificate) certChain[i - 1]).checkValidity();
                certChain[i - 1].verify(pubKey);
            } catch (InvalidKeyException | CertificateException | NoSuchAlgorithmException
                    | NoSuchProviderException | SignatureException e) {
                throw new GeneralSecurityException("Failed to verify certificate "
                        + certChain[i - 1] + " with public key " + certChain[i].getPublicKey(), e);
            }
            if (i == certChain.length - 1) {
                // Last cert is self-signed.
                try {
                    ((X509Certificate) certChain[i]).checkValidity();
                    certChain[i].verify(pubKey);
                } catch (CertificateException e) {
                    throw new GeneralSecurityException(
                            "Root cert " + certChain[i] + " is not correctly self-signed", e);
                }
            }
        }
    }

    private static void appendVerifiedInformation(final StringBuilder builder,
            final Verified verified, final Date now) {
        final String osVersion = String.format(Locale.US, "%06d", verified.osVersion);
        builder.append(String.format("OS version: %s\n",
                Integer.parseInt(osVersion.substring(0, 2)) + "." +
                Integer.parseInt(osVersion.substring(2, 4)) + "." +
                Integer.parseInt(osVersion.substring(4, 6))));

        final String osPatchLevel = Integer.toString(verified.osPatchLevel);
        builder.append(String.format("OS patch level: %s\n",
                osPatchLevel.substring(0, 4) + "-" + osPatchLevel.substring(4, 6)));

        builder.append(String.format("Time: %s\n", now));
    }

    private static void verifySignature(final PublicKey key, final ByteBuffer message,
            final byte[] signature) throws GeneralSecurityException {
        final Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initVerify(key);
        sig.update(message);
        if (!sig.verify(signature)) {
            throw new GeneralSecurityException("signature verification failed");
        }
    }

    static class VerificationResult {
        final boolean strong;
        final String teeEnforced;
        final String osEnforced;

        VerificationResult(final boolean strong, final String teeEnforced,
                final String osEnforced) {
            this.strong = strong;
            this.teeEnforced = teeEnforced;
            this.osEnforced = osEnforced;
        }
    }

    private static String toYesNoString(final boolean value) {
        return value ? "yes" : "no";
    }

    private static VerificationResult verify(final byte[] fingerprint,
            final Cache<ByteBuffer, Boolean> pendingChallenges, final ByteBuffer signedMessage, final byte[] signature,
            final Certificate[] attestationCertificates, final boolean userProfileSecure,
            final boolean accessibility, final boolean deviceAdmin,
            final boolean deviceAdminNonSystem, final boolean adbEnabled,
            final boolean addUsersWhenLocked, final boolean enrolledFingerprints,
            final boolean denyNewUsb) throws GeneralSecurityException, IOException {

        final String fingerprintHex = BaseEncoding.base16().encode(fingerprint);
        final byte[] currentFingerprint = getFingerprint(attestationCertificates[0]);
        final boolean hasPersistentKey = !Arrays.equals(currentFingerprint, fingerprint);

        final SQLiteConnection conn = new SQLiteConnection(ATTESTATION_DATABASE);
        try {
            conn.open();
            conn.setBusyTimeout(AttestationServer.BUSY_TIMEOUT);

            final byte[][] pinnedCertificates = new byte[3][];
            byte[] pinnedVerifiedBootKey = null;
            int pinnedOsVersion = Integer.MAX_VALUE;
            int pinnedOsPatchLevel = Integer.MAX_VALUE;
            int pinnedAppVersion = Integer.MAX_VALUE;
            long verifiedTimeFirst = 0;
            long verifiedTimeLast = 0;
            if (hasPersistentKey) {
                final SQLiteStatement st = conn.prepare("SELECT pinned_certificate_0, pinned_certificate_1, pinned_certificate_2, pinned_verified_boot_key, pinned_os_version, pinned_os_patch_level, pinned_app_version, verified_time_first, verified_time_last from Devices WHERE fingerprint = ?");
                st.bind(1, fingerprint);
                if (st.step()) {
                    pinnedCertificates[0] = st.columnBlob(0);
                    pinnedCertificates[1] = st.columnBlob(1);
                    pinnedCertificates[2] = st.columnBlob(2);
                    pinnedVerifiedBootKey = st.columnBlob(3);
                    pinnedOsVersion = st.columnInt(4);
                    pinnedOsPatchLevel = st.columnInt(5);
                    pinnedAppVersion = st.columnInt(6);
                    verifiedTimeFirst = st.columnLong(7);
                    verifiedTimeLast = st.columnLong(8);
                    st.dispose();
                } else {
                    st.dispose();
                    throw new GeneralSecurityException(
                            "Pairing data for this Auditee is missing. Cannot perform paired attestation.\n" +
                            "\nEither the initial pairing was incomplete or the device is compromised.\n" +
                            "\nIf the initial pairing was simply not completed, clear the pairing data on either the Auditee or the Auditor via the menu and try again.\n");
                }
            }

            final Verified verified = verifyStateless(attestationCertificates, pendingChallenges,
                    generateCertificate(new ByteArrayInputStream(GOOGLE_ROOT_CERTIFICATE.getBytes())));
            final byte[] verifiedBootKey = BaseEncoding.base16().decode(verified.verifiedBootKey);

            final StringBuilder teeEnforced = new StringBuilder();

            if (attestationCertificates.length != 4) {
                throw new GeneralSecurityException("currently only support certificate chains with length 4");
            }

            if (hasPersistentKey) {
                for (int i = 1; i < attestationCertificates.length - 1; i++) {
                    if (!Arrays.equals(attestationCertificates[i].getEncoded(), pinnedCertificates[i])) {
                        throw new GeneralSecurityException("certificate chain mismatch");
                    }
                }

                final Certificate persistentCertificate = generateCertificate(
                        new ByteArrayInputStream(pinnedCertificates[0]));
                if (!Arrays.equals(fingerprint, getFingerprint(persistentCertificate))) {
                    throw new GeneralSecurityException("corrupt Auditor pinning data");
                }
                verifySignature(persistentCertificate.getPublicKey(), signedMessage, signature);

                if (!Arrays.equals(verifiedBootKey, pinnedVerifiedBootKey)) {
                    throw new GeneralSecurityException("pinned verified boot key mismatch");
                }
                if (verified.osVersion < pinnedOsVersion) {
                    throw new GeneralSecurityException("OS version downgrade detected");
                }
                if (verified.osPatchLevel < pinnedOsPatchLevel) {
                    throw new GeneralSecurityException("OS patch level downgrade detected");
                }
                if (verified.appVersion < pinnedAppVersion) {
                    throw new GeneralSecurityException("App version downgraded");
                }

                final Date now = new Date();
                appendVerifiedInformation(teeEnforced, verified, now);

                final SQLiteStatement update = conn.prepare("UPDATE Devices SET pinned_os_version = ?, pinned_os_patch_level = ?, pinned_app_version = ?, verified_time_last = ? WHERE fingerprint = ?");
                update.bind(1, verified.osVersion);
                update.bind(2, verified.osPatchLevel);
                update.bind(3, verified.appVersion);
                update.bind(4, now.getTime());
                update.bind(5, fingerprint);
                update.step();
                update.dispose();
             } else {
                verifySignature(attestationCertificates[0].getPublicKey(), signedMessage, signature);

                final SQLiteStatement insert = conn.prepare("INSERT INTO Devices VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                insert.bind(1, fingerprint);
                insert.bind(2, attestationCertificates[0].getEncoded());
                insert.bind(3, attestationCertificates[1].getEncoded());
                insert.bind(4, attestationCertificates[2].getEncoded());
                insert.bind(5, verifiedBootKey);
                insert.bind(6, verified.osVersion);
                insert.bind(7, verified.osPatchLevel);
                insert.bind(8, verified.appVersion);
                final Date now = new Date();
                insert.bind(9, now.getTime());
                insert.bind(10, now.getTime());
                insert.step();
                insert.dispose();

                appendVerifiedInformation(teeEnforced, verified, now);
            }

            final StringBuilder osEnforced = new StringBuilder();
            osEnforced.append(String.format("Auditor app version: %s\n",
                    verified.appVersion - ATTESTATION_APP_VERSION_CODE_OFFSET));
            osEnforced.append(String.format("User profile secure: %s\n",
                    toYesNoString(userProfileSecure)));
            osEnforced.append(String.format("Enrolled fingerprints: %s\n",
                    toYesNoString(enrolledFingerprints)));
            osEnforced.append(String.format("Accessibility service(s) enabled: %s\n",
                    toYesNoString(accessibility)));

            final String deviceAdminState;
            if (deviceAdminNonSystem) {
                deviceAdminState = "yes, but only system apps";
            } else if (deviceAdmin) {
                deviceAdminState = "yes, with non-system apps";
            } else {
                deviceAdminState = "no";
            }
            osEnforced.append(String.format("Device administrator(s) enabled: %s\n", deviceAdminState));

            osEnforced.append(String.format("Android Debug Bridge enabled: %s\n",
                    toYesNoString(adbEnabled)));
            osEnforced.append(String.format("Add users from lock screen: %s\n",
                    toYesNoString(addUsersWhenLocked)));
            osEnforced.append(String.format("Disallow new USB peripherals when locked: %s\n",
                    toYesNoString(denyNewUsb)));

            final String teeEnforcedString = teeEnforced.toString();
            final String osEnforcedString = osEnforced.toString();

            final SQLiteStatement insert = conn.prepare("INSERT into Attestations VALUES(NULL, ?, ?, ?, ?)");
            insert.bind(1, fingerprint);
            insert.bind(2, hasPersistentKey ? 1 : 0);
            insert.bind(3, teeEnforcedString);
            insert.bind(4, osEnforcedString);
            insert.step();
            insert.dispose();

            return new VerificationResult(hasPersistentKey, teeEnforcedString, osEnforcedString);
        } catch (final SQLiteException e) {
            throw new IOException(e);
        } finally {
            conn.dispose();
        }
    }

    static VerificationResult verifySerialized(final byte[] attestationResult,
            final Cache<ByteBuffer, Boolean> pendingChallenges) throws DataFormatException, GeneralSecurityException, IOException {
        final ByteBuffer deserializer = ByteBuffer.wrap(attestationResult);
        final byte version = deserializer.get();
        if (version > PROTOCOL_VERSION) {
            throw new GeneralSecurityException("unsupported protocol version: " + version);
        }

        final short compressedChainLength = deserializer.getShort();
        final byte[] compressedChain = new byte[compressedChainLength];
        deserializer.get(compressedChain);

        final byte[] chain = new byte[MAX_ENCODED_CHAIN_LENGTH];
        final Inflater inflater = new Inflater(true);
        inflater.setInput(compressedChain);
        inflater.setDictionary(DEFLATE_DICTIONARY);
        final int chainLength = inflater.inflate(chain);
        if (!inflater.finished()) {
            throw new GeneralSecurityException("certificate chain is too large");
        }
        inflater.end();
        //Log.d(TAG, "encoded length: " + chainLength + ", compressed length: " + compressedChain.length);

        final ByteBuffer chainDeserializer = ByteBuffer.wrap(chain, 0, chainLength);
        final List<Certificate> certs = new ArrayList<>();
        while (chainDeserializer.hasRemaining()) {
            final short encodedLength = chainDeserializer.getShort();
            final byte[] encoded = new byte[encodedLength];
            chainDeserializer.get(encoded);
            certs.add(generateCertificate(new ByteArrayInputStream(encoded)));
        }
        final Certificate[] certificates = certs.toArray(new Certificate[certs.size() + 1]);

        final byte[] fingerprint = new byte[FINGERPRINT_LENGTH];
        deserializer.get(fingerprint);

        final byte osEnforcedFlags = deserializer.get();
        if ((osEnforcedFlags & ~OS_ENFORCED_FLAGS_ALL) != 0) {
            //Log.w(TAG, "unknown OS enforced flag set (flags: " + Integer.toBinaryString(osEnforcedFlags) + ")");
        }
        final boolean userProfileSecure = (osEnforcedFlags & OS_ENFORCED_FLAGS_USER_PROFILE_SECURE) != 0;
        final boolean accessibility = (osEnforcedFlags & OS_ENFORCED_FLAGS_ACCESSIBILITY) != 0;
        final boolean deviceAdmin = (osEnforcedFlags & OS_ENFORCED_FLAGS_DEVICE_ADMIN) != 0;
        final boolean deviceAdminNonSystem = (osEnforcedFlags & OS_ENFORCED_FLAGS_DEVICE_ADMIN_NON_SYSTEM) != 0;
        final boolean adbEnabled = (osEnforcedFlags & OS_ENFORCED_FLAGS_ADB_ENABLED) != 0;
        final boolean addUsersWhenLocked = (osEnforcedFlags & OS_ENFORCED_FLAGS_ADD_USERS_WHEN_LOCKED) != 0;
        final boolean enrolledFingerprints = (osEnforcedFlags & OS_ENFORCED_FLAGS_ENROLLED_FINGERPRINTS) != 0;
        final boolean denyNewUsb = (osEnforcedFlags & OS_ENFORCED_FLAGS_DENY_NEW_USB) != 0;

        if (deviceAdminNonSystem && !deviceAdmin) {
            throw new GeneralSecurityException("invalid device administrator state");
        }

        final int signatureLength = deserializer.remaining();
        final byte[] signature = new byte[signatureLength];
        deserializer.get(signature);

        certificates[certificates.length - 1] =
                generateCertificate(new ByteArrayInputStream(GOOGLE_ROOT_CERTIFICATE.getBytes()));

        deserializer.rewind();
        deserializer.limit(deserializer.capacity() - signature.length);

        return verify(fingerprint, pendingChallenges, deserializer.asReadOnlyBuffer(), signature,
                certificates, userProfileSecure, accessibility, deviceAdmin, deviceAdminNonSystem,
                adbEnabled, addUsersWhenLocked, enrolledFingerprints, denyNewUsb);
    }
}
