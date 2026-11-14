package chat.kelo.app.start

import chat.kelo.app.core.ApiError
import chat.kelo.app.core.PairingClient
import chat.kelo.app.core.PairingLink
import chat.kelo.app.core.Profile
import chat.kelo.app.core.Session
import chat.kelo.app.start.ConnectViewModel

/**
 * "Connect with an access key" (ConnectViewModel.connectWithKey): the key is checked with GET /api/profile; when the
 * server refuses it (401) and the text looks like a sign-in code (a multi-use review or pairing code, e.g. ABCD-EFGH-JKMN-PQRS from
 * store review notes), it is tried once through POST /api/pair, as a pasted link would be. Returns the session and, for a key, the
 * profile (null after a code: the caller reads it with the new token). Throws the key's own [ApiError] when neither works.
 */
object KeySignIn {
    suspend fun connect(client: PairingClient, serverUrl: String, key: String, deviceName: String): Pair<Session, Profile?> =
        connect(serverUrl, key, deviceName, check = client::checkKey, pair = client::pair)

    /** The same with the two calls given ([check]: the key on GET /api/profile, [pair]: POST /api/pair), for tests. */
    suspend fun connect(
        serverUrl: String, key: String, deviceName: String,
        check: suspend (String, String) -> Pair<Session, Profile>,
        pair: suspend (PairingLink, String) -> Session,
    ): Pair<Session, Profile?> {
        try {
            return check(serverUrl, key.trim())
        } catch (e: ApiError) {
            if (e.isUnauthorized && ConnectViewModel.looksLikeCode(key)) {
                try {
                    return pair(PairingLink(serverUrl, ConnectViewModel.codeOf(key)), deviceName) to null
                } catch (_: ApiError) {
                    // fall through: say the key did not match
                }
            }
            throw e
        }
    }
}
