package com.palmgrade.rns.rns;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * RnsIdentity
 *
 * Models a Reticulum primary identity and its derived LXMF delivery address,
 * matching the behaviour of Sideband / the reference RNS implementation.
 *
 * ── Key material ───────────────────────────────────────────────────────────
 *
 *   Two keypairs are generated at first run and persisted as raw private key
 *   seeds (64 hex chars each).  In production these MUST be Curve25519 (X25519)
 *   and Ed25519 — this implementation carries the correct structure and derivation
 *   logic; the actual scalar-multiplication is stubbed with a deterministic SHA-256
 *   stand-in so the app compiles without a native crypto library.  Wire it up to
 *   BouncyCastle / libsodium-android for production.
 *
 *   enc_private_key  (32 bytes)  ─► enc_public_key  (32 bytes, X25519 scalar mult)
 *   sign_private_key (32 bytes)  ─► sign_public_key (32 bytes, Ed25519 clamp+mult)
 *
 * ── Identity hash ──────────────────────────────────────────────────────────
 *
 *   combined_public_key = enc_public_key || sign_public_key   (64 bytes)
 *   identity_hash       = SHA-256(combined_public_key)[0:16]  (16 bytes)
 *   displayed as:  32 lowercase hex chars, no separators
 *
 *   e.g.  "a3f19c2d44e07b8c5f612a39d8e4b071"
 *
 * ── LXMF delivery address ──────────────────────────────────────────────────
 *
 *   An LXMF delivery destination is a Reticulum SINGLE destination with
 *   app_name="lxmf", aspect="delivery".
 *
 *   name_hash   = SHA-256( "lxmf" + "." + "delivery" )[0:10]   (10 bytes)
 *   dest_hash   = SHA-256( identity_hash || name_hash )[0:16]   (16 bytes)
 *   displayed as:  32 lowercase hex chars, no separators
 *
 *   e.g.  "b2e48f1a903c6d27e5a0f841c39d5b82"
 *
 *   This is the address users share and that appears in Sideband as the
 *   "LXMF address" on the My Identity screen.
 *
 * ── Read-only rule ─────────────────────────────────────────────────────────
 *
 *   Both the identity hash and LXMF address are DERIVED values.
 *   They cannot be set by the user — only the display name (nickname) is editable.
 *   Regenerating the identity creates entirely new keys and therefore new hashes.
 *
 * ── Storage ────────────────────────────────────────────────────────────────
 *
 *   Only the raw private key seeds are persisted.  All public keys and hashes
 *   are re-derived on every load.  This prevents stale cached values.
 */
public class RnsIdentity {

    private static final String TAG        = "RnsIdentity";
    private static final String PREFS_NAME = "rns_identity";

    // SharedPrefs keys — only private seeds and nickname are stored
    private static final String KEY_ENC_PRIV   = "enc_private_key_hex";   // 64 hex chars
    private static final String KEY_SIGN_PRIV  = "sign_private_key_hex";  // 64 hex chars
    private static final String KEY_NICKNAME   = "nickname";

    // LXMF destination name components (must match RNS reference)
    private static final String LXMF_APP_NAME = "lxmf";
    private static final String LXMF_ASPECT   = "delivery";

    private final SharedPreferences prefs;

    // ── Stored private seeds (32 bytes each) ──
    private byte[] encPrivateKey;   // X25519 private key seed
    private byte[] signPrivateKey;  // Ed25519 private key seed

    // ── Derived (never stored, always recomputed) ──
    private byte[] encPublicKey;    // 32 bytes
    private byte[] signPublicKey;   // 32 bytes
    private byte[] identityHash;    // 16 bytes  — SHA-256(enc_pub || sign_pub)[0:16]
    private byte[] lxmfAddress;     // 16 bytes  — destination hash for lxmf.delivery

    // ── User-editable ──
    private String nickname;

    // ─────────────────────────────────────────────────────────────────────────

    public RnsIdentity(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadOrGenerate();
    }

    // =========================================================================
    // Public read-only accessors
    // =========================================================================

    /**
     * LXMF delivery address — 32 lowercase hex chars, no separators.
     * This is what users share so others can send them LXMF messages.
     * Matches the "Address" field on Sideband's My Identity screen.
     *
     * Example: "b2e48f1a903c6d27e5a0f841c39d5b82"
     */
    public String getLxmfAddressHex() {
        return bytesToHex(lxmfAddress);
    }

    /** Raw 16-byte LXMF address for packet construction. */
    public byte[] getLxmfAddressBytes() {
        return lxmfAddress.clone();
    }

    /**
     * RNS identity hash — 32 lowercase hex chars, no separators.
     * Uniquely identifies the primary identity key pair.
     * Matches the "Identity hash" field on Sideband's My Identity screen.
     *
     * Example: "a3f19c2d44e07b8c5f612a39d8e4b071"
     */
    public String getIdentityHashHex() {
        return bytesToHex(identityHash);
    }

    /** Raw 16-byte identity hash. */
    public byte[] getIdentityHashBytes() {
        return identityHash.clone();
    }

    /**
     * Combined public key (enc_pub || sign_pub), 64 bytes.
     * Embedded in RNS announce packets.
     */
    public byte[] getCombinedPublicKey() {
        byte[] combined = new byte[64];
        System.arraycopy(encPublicKey,  0, combined,  0, 32);
        System.arraycopy(signPublicKey, 0, combined, 32, 32);
        return combined;
    }

    // ── Nickname (only user-editable field) ──────────────────────────────────

    public String getNickname() {
        return nickname != null ? nickname : "";
    }

    public void setNickname(String n) {
        nickname = (n != null) ? n.trim() : "";
        prefs.edit().putString(KEY_NICKNAME, nickname).apply();
    }

    // =========================================================================
    // Identity regeneration  (destroys current identity — warn user first)
    // =========================================================================

    /**
     * Generate a brand-new primary identity.
     *
     * WARNING: This creates a completely new LXMF address.
     * Any contacts who have the old address will no longer be able to reach
     * this node until they are given the new address.
     * Sideband shows a confirmation dialog before doing this — mirror that here.
     */
    public void regenerate() {
        SecureRandom rng = new SecureRandom();
        encPrivateKey  = new byte[32];
        signPrivateKey = new byte[32];
        rng.nextBytes(encPrivateKey);
        rng.nextBytes(signPrivateKey);
        derive();
        persist();
        Log.i(TAG, "New RNS identity generated");
        Log.i(TAG, "  Identity hash : " + getIdentityHashHex());
        Log.i(TAG, "  LXMF address  : " + getLxmfAddressHex());
    }

    // =========================================================================
    // LXMF packet builders
    // =========================================================================

    /**
     * Build an RNS announce packet for the LXMF delivery destination.
     *
     * RNS wire format (interface-agnostic header):
     *   [flags 1B] [hops 1B] [dest_hash 16B] [random_hash 16B]
     *   [combined_public_key 64B] [app_data_len 2B] [app_data]
     *
     * flags byte:
     *   bits 7-6: IFAC flag (00 = no IFAC)
     *   bits 5-4: header type (00 = type 1, single destination)
     *   bits 3-2: propagation type (00 = broadcast)
     *   bits 1-0: dest type (01=SINGLE) + context (10=ANNOUNCE) → 0x04
     */
    public byte[] buildAnnouncePacket() {
        byte[] dest       = lxmfAddress;
        byte[] pubKey     = getCombinedPublicKey();
        byte[] randomHash = new byte[16];
        new SecureRandom().nextBytes(randomHash);
        byte[] appData    = buildLxmfAnnounceAppData();

        int len = 1 + 1 + 16 + 16 + 64 + 2 + appData.length;
        byte[] pkt = new byte[len];
        int p = 0;

        pkt[p++] = (byte) 0x04; // flags: type-1 header, SINGLE dest, ANNOUNCE context
        pkt[p++] = (byte) 0x00; // hops = 0 (originating node)

        System.arraycopy(dest,       0, pkt, p, 16); p += 16;
        System.arraycopy(randomHash, 0, pkt, p, 16); p += 16;
        System.arraycopy(pubKey,     0, pkt, p, 64); p += 64;

        pkt[p++] = (byte)(appData.length >> 8);
        pkt[p++] = (byte)(appData.length);
        System.arraycopy(appData, 0, pkt, p, appData.length);

        return pkt;
    }

    /**
     * Build an LXMF message packet targeting a 16-byte destination address.
     *
     * LXMF payload (the content field carried inside an RNS DATA packet):
     *   [source_lxmf_address 16B] [timestamp_double_be 8B] [fields_msgpack]
     *   [title_len 1B] [title_utf8] [content_utf8]
     */
    public byte[] buildLxmfMessage(byte[] destinationAddress, String title, String content) {
        if (destinationAddress == null || destinationAddress.length < 16)
            return new byte[0];

        byte[] src          = lxmfAddress;
        byte[] titleBytes   = utf8(title   != null ? title   : "");
        byte[] contentBytes = utf8(content != null ? content : "");
        byte[] fields       = new byte[]{ (byte) 0x80 }; // empty msgpack map

        // Timestamp as IEEE 754 double, big-endian 8 bytes
        long tsBits = Double.doubleToRawLongBits(System.currentTimeMillis() / 1000.0);
        byte[] ts = new byte[8];
        for (int i = 7; i >= 0; i--) { ts[i] = (byte)(tsBits & 0xFF); tsBits >>= 8; }

        int len = 16 + 16 + 8 + 1 + 1 + titleBytes.length + contentBytes.length + fields.length;
        byte[] pkt = new byte[len];
        int p = 0;

        System.arraycopy(destinationAddress, 0, pkt, p, 16); p += 16; // destination
        System.arraycopy(src,                0, pkt, p, 16); p += 16; // source
        System.arraycopy(ts,                 0, pkt, p, 8);  p += 8;  // timestamp
        pkt[p++] = fields[0];                                          // fields map
        pkt[p++] = (byte) titleBytes.length;
        System.arraycopy(titleBytes,   0, pkt, p, titleBytes.length);   p += titleBytes.length;
        System.arraycopy(contentBytes, 0, pkt, p, contentBytes.length);

        return pkt;
    }

    // =========================================================================
    // Private — key derivation
    // =========================================================================

    /**
     * Derive all public values from the stored private seeds.
     *
     * ⚠ The publicKeyFromPrivate() call below uses SHA-256 as a placeholder.
     * Replace with real Curve25519 / Ed25519:
     *
     *   Option A — BouncyCastle (add to build.gradle):
     *     implementation 'org.bouncycastle:bcprov-jdk15on:1.70'
     *
     *     // X25519 encryption key:
     *     X25519PrivateKeyParameters encPriv = new X25519PrivateKeyParameters(encPrivateKey, 0);
     *     encPublicKey = encPriv.generatePublicKey().getEncoded();
     *
     *     // Ed25519 signing key:
     *     Ed25519PrivateKeyParameters signPriv = new Ed25519PrivateKeyParameters(signPrivateKey, 0);
     *     signPublicKey = signPriv.generatePublicKey().getEncoded();
     *
     *   Option B — libsodium via Android JNI wrapper (lazysodium-android):
     *     implementation 'com.goterl:lazysodium-android:5.1.0@aar'
     *     implementation 'net.java.dev.jna:jna:5.12.1@aar'
     */
    private void derive() {
        encPublicKey  = publicKeyFromPrivate(encPrivateKey,  (byte) 0x01);
        signPublicKey = publicKeyFromPrivate(signPrivateKey, (byte) 0x02);

        // identity_hash = SHA-256( enc_pub || sign_pub )[0:16]
        byte[] combined = getCombinedPublicKey(); // 64 bytes
        identityHash = sha256Truncated(combined, 16);

        // name_hash = SHA-256( "lxmf.delivery" )[0:10]
        byte[] nameBytes = utf8(LXMF_APP_NAME + "." + LXMF_ASPECT);
        byte[] nameHash  = sha256Truncated(nameBytes, 10);

        // lxmf_address = SHA-256( identity_hash || name_hash )[0:16]
        byte[] addrInput = new byte[16 + 10];
        System.arraycopy(identityHash, 0, addrInput,  0, 16);
        System.arraycopy(nameHash,     0, addrInput, 16, 10);
        lxmfAddress = sha256Truncated(addrInput, 16);
    }

    /**
     * Stub — replace with real scalar multiplication before release.
     * domain byte differentiates enc vs sign derivations.
     */
    private byte[] publicKeyFromPrivate(byte[] privateSeed, byte domain) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(domain);
            sha.update(privateSeed);
            return Arrays.copyOf(sha.digest(), 32);
        } catch (Exception e) {
            return new byte[32];
        }
    }

    // =========================================================================
    // Private — load / persist
    // =========================================================================

    private void loadOrGenerate() {
        nickname = prefs.getString(KEY_NICKNAME, "");

        String encHex  = prefs.getString(KEY_ENC_PRIV,  null);
        String signHex = prefs.getString(KEY_SIGN_PRIV, null);

        if (encHex != null && encHex.length() == 64 &&
            signHex != null && signHex.length() == 64) {
            try {
                encPrivateKey  = hexToBytes(encHex);
                signPrivateKey = hexToBytes(signHex);
                derive();
                Log.d(TAG, "Identity loaded — LXMF addr : " + getLxmfAddressHex());
                Log.d(TAG, "                  Identity  : " + getIdentityHashHex());
                return;
            } catch (Exception e) {
                Log.w(TAG, "Identity load failed, regenerating: " + e.getMessage());
            }
        }
        regenerate();
    }

    private void persist() {
        prefs.edit()
            .putString(KEY_ENC_PRIV,  bytesToHex(encPrivateKey))
            .putString(KEY_SIGN_PRIV, bytesToHex(signPrivateKey))
            .putString(KEY_NICKNAME,  nickname != null ? nickname : "")
            .apply();
    }

    // =========================================================================
    // Private — LXMF app_data for announce
    // =========================================================================

    /**
     * Encodes { "display_name": "<nickname>" } as a minimal msgpack map.
     *
     *   0x81            fixmap, 1 entry
     *   0xAC            fixstr, len=12
     *   "display_name"  12 bytes
     *   0xA0+n / 0xD9   fixstr or str8 for value
     *   <nickname_utf8>
     */
    private byte[] buildLxmfAnnounceAppData() {
        byte[] keyBytes = utf8("display_name"); // exactly 12 bytes
        byte[] valBytes = utf8(getNickname());
        int valLen = valBytes.length;

        boolean useFixStr = valLen <= 31;
        int size = 1 + 1 + keyBytes.length + (useFixStr ? 1 : 2) + valLen;
        byte[] out = new byte[size];
        int p = 0;

        out[p++] = (byte) 0x81;               // fixmap(1)
        out[p++] = (byte)(0xA0 + 12);         // fixstr(12) for "display_name"
        System.arraycopy(keyBytes, 0, out, p, keyBytes.length); p += keyBytes.length;

        if (useFixStr) {
            out[p++] = (byte)(0xA0 + valLen);
        } else {
            out[p++] = (byte) 0xD9;           // str8
            out[p++] = (byte) valLen;
        }
        System.arraycopy(valBytes, 0, out, p, valLen);
        return out;
    }

    // =========================================================================
    // Private — crypto utilities
    // =========================================================================

    private byte[] sha256Truncated(byte[] input, int outputLen) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Arrays.copyOf(md.digest(input), outputLen);
        } catch (Exception e) {
            return new byte[outputLen];
        }
    }

    private byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    private byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
