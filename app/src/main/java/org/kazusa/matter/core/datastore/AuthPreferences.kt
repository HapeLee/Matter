package org.kazusa.matter.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.kazusa.matter.core.session.CredentialCipher
import org.kazusa.matter.core.session.SessionExpiryReason
import java.io.IOException

val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "matter_auth_pref")

class AuthPreferences(
    private val context: Context,
    private val credentialCipher: CredentialCipher = CredentialCipher.None
) {

    /** 凭据字段写入前加密；空白与旧语义一致（不覆盖已有值）。 */
    private fun encodeCredential(value: String): String = credentialCipher.encrypt(value)

    /** 凭据字段读出后解密；密文解不开按空处理，会话层会把用户引导回登录页。 */
    private fun decodeCredential(value: String?): String =
        value?.takeIf { it.isNotBlank() }?.let { credentialCipher.decrypt(it) }.orEmpty()

    private object Keys {
        val UID = stringPreferencesKey("uid")
        val SUB = stringPreferencesKey("sub")
        val SUBP = stringPreferencesKey("subp")
        val RAW_COOKIE = stringPreferencesKey("raw_cookie")
        val SCREEN_NAME = stringPreferencesKey("screen_name")
        val AVATAR_URL = stringPreferencesKey("avatar_url")
        val DESCRIPTION = stringPreferencesKey("description")
        val FOLLOWERS_COUNT = stringPreferencesKey("followers_count")
        val FRIENDS_COUNT = stringPreferencesKey("friends_count")
        val STATUSES_COUNT = stringPreferencesKey("statuses_count")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val IS_ONBOARDING_COMPLETED = booleanPreferencesKey("is_onboarding_completed")
        val LAST_GROUP_ID = stringPreferencesKey("last_group_id")
        val LAST_GROUP_NAME = stringPreferencesKey("last_group_name")
        val HIDDEN_GROUP_IDS = stringSetPreferencesKey("hidden_group_ids")
        val GROUP_CUSTOM_ORDER = stringPreferencesKey("group_custom_order")
        val LAST_EXPIRE_REASON = stringPreferencesKey("last_expire_reason")
        val LAST_EXPIRE_AT = longPreferencesKey("last_expire_at")
    }

    val lastGroupFlow: Flow<Pair<String?, String?>> = context.authDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val gid = prefs[Keys.LAST_GROUP_ID]
            val name = prefs[Keys.LAST_GROUP_NAME]
            Pair(gid, name)
        }

    suspend fun saveSelectedGroup(gid: String?, groupName: String?) {
        context.authDataStore.edit { prefs ->
            if (gid != null) {
                prefs[Keys.LAST_GROUP_ID] = gid
            } else {
                prefs.remove(Keys.LAST_GROUP_ID)
            }
            if (groupName != null) {
                prefs[Keys.LAST_GROUP_NAME] = groupName
            } else {
                prefs.remove(Keys.LAST_GROUP_NAME)
            }
        }
    }

    val hiddenGroupIdsFlow: Flow<Set<String>> = context.authDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs -> prefs[Keys.HIDDEN_GROUP_IDS] ?: emptySet() }

    suspend fun setGroupVisibility(gid: String, isVisible: Boolean) {
        if (gid == "all" || gid.isBlank()) return // 全部关注不能被隐藏
        context.authDataStore.edit { prefs ->
            val currentHidden = prefs[Keys.HIDDEN_GROUP_IDS]?.toMutableSet() ?: mutableSetOf()
            if (isVisible) {
                currentHidden.remove(gid)
            } else {
                currentHidden.add(gid)
            }
            prefs[Keys.HIDDEN_GROUP_IDS] = currentHidden
        }
    }

    val groupCustomOrderFlow: Flow<List<String>> = context.authDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val raw = prefs[Keys.GROUP_CUSTOM_ORDER]
            if (!raw.isNullOrBlank()) raw.split(",").filter { it.isNotBlank() } else emptyList()
        }

    suspend fun saveGroupCustomOrder(order: List<String>) {
        context.authDataStore.edit { prefs ->
            if (order.isNotEmpty()) {
                prefs[Keys.GROUP_CUSTOM_ORDER] = order.joinToString(",")
            } else {
                prefs.remove(Keys.GROUP_CUSTOM_ORDER)
            }
        }
    }

    val isOnboardingCompletedFlow: Flow<Boolean> = context.authDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs -> prefs[Keys.IS_ONBOARDING_COMPLETED] ?: false }

    suspend fun setOnboardingCompleted() {
        context.authDataStore.edit { prefs ->
            prefs[Keys.IS_ONBOARDING_COMPLETED] = true
        }
    }

    val sessionFlow: Flow<UserSession> = context.authDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserSession(
                uid = prefs[Keys.UID] ?: "",
                sub = decodeCredential(prefs[Keys.SUB]),
                subp = decodeCredential(prefs[Keys.SUBP]),
                rawCookie = decodeCredential(prefs[Keys.RAW_COOKIE]),
                screenName = prefs[Keys.SCREEN_NAME] ?: "",
                avatarUrl = prefs[Keys.AVATAR_URL] ?: "",
                description = prefs[Keys.DESCRIPTION] ?: "",
                followersCount = prefs[Keys.FOLLOWERS_COUNT] ?: "",
                friendsCount = prefs[Keys.FRIENDS_COUNT] ?: "",
                statusesCount = prefs[Keys.STATUSES_COUNT] ?: "",
                isLoggedIn = prefs[Keys.IS_LOGGED_IN] ?: false
            )
        }
        // 解密必须在后台线程完成。
        //
        // 上面这个 map 会做 3 次 AndroidKeyStore 解密（SUB / SUBP / 原始 Cookie），
        // 而 Flow 的 map 默认跑在**收集者上下文**：导航根的 collectAsStateWithLifecycle、
        // 各 Repository 的 sessionFlow.first()（发起请求的 viewModelScope = 主线程）
        // 都会把这段解密拉到主线程上，并且每个请求都要付一次。
        // 放在这里统一改，比在 29 个调用点各自包线程可靠。
        .flowOn(Dispatchers.Default)

    suspend fun saveSession(
        uid: String,
        sub: String = "",
        subp: String = "",
        rawCookie: String = "",
        screenName: String = "",
        avatarUrl: String = "",
        description: String = "",
        followersCount: String = "",
        friendsCount: String = "",
        statusesCount: String = ""
    ) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.UID] = uid
            // 凭据字段无条件覆盖：新登录串里没有 SUB 时必须把旧值清掉。
            // 之前的 `if (isNotBlank())` 会让旧账号的 SUB 留下来，与新的 uid / rawCookie
            // 混搭成一份自相矛盾的凭据（服务端只认旧 SUB，表现为「刚登录就失效」）。
            writeCredential(prefs, Keys.SUB, sub)
            writeCredential(prefs, Keys.SUBP, subp)
            writeCredential(prefs, Keys.RAW_COOKIE, rawCookie)
            if (screenName.isNotBlank()) prefs[Keys.SCREEN_NAME] = screenName
            if (avatarUrl.isNotBlank()) prefs[Keys.AVATAR_URL] = avatarUrl
            if (description.isNotBlank()) prefs[Keys.DESCRIPTION] = description
            if (followersCount.isNotBlank()) prefs[Keys.FOLLOWERS_COUNT] = followersCount
            if (friendsCount.isNotBlank()) prefs[Keys.FRIENDS_COUNT] = friendsCount
            if (statusesCount.isNotBlank()) prefs[Keys.STATUSES_COUNT] = statusesCount
            prefs[Keys.IS_LOGGED_IN] = true
            // 登录成功即刷新生命周期：上一次的失效原因不再有意义
            prefs.remove(Keys.LAST_EXPIRE_REASON)
            prefs.remove(Keys.LAST_EXPIRE_AT)
        }
    }

    /** 空白即删除，避免旧凭据残留（登出语义之外，登录成功时也按此覆盖）。 */
    private fun writeCredential(prefs: MutablePreferences, key: Preferences.Key<String>, value: String) {
        if (value.isNotBlank()) {
            prefs[key] = encodeCredential(value)
        } else {
            prefs.remove(key)
        }
    }

    /**
     * 服务端轮换后的长期凭据回写（cookie jar 语义）。
     *
     * 微博的 `SUB` / `SUBP` 是滑动凭据：几乎每次响应都可能带着新值或新过期时间。
     * 浏览器跟随它，Matter 此前只发不收，本地凭据只能越来越旧——这是「用一阵子
     * 就掉登录」的结构性原因，与本地有没有设过期时间无关。
     *
     * 只写 `SUB` / `SUBP`：[UserSession.cookieHeader] 会用它们覆盖 `rawCookie` 里的同名项，
     * 所以这两个跟上就够，不必每次响应重写整串 `rawCookie`。
     *
     * @param expectedUid 发起请求时读到的会话 uid。换号之后旧响应晚到会带着上一账号的
     *   轮换值，必须丢弃，否则新会话被旧凭据污染。
     * @return 是否真的写入（同值不写，避免无意义的 Flow 发射与磁盘 IO）。
     */
    suspend fun rotateCredentials(
        expectedUid: String,
        sub: String? = null,
        subp: String? = null
    ): Boolean {
        val newSub = sub?.takeIf { it.isNotBlank() }
        val newSubp = subp?.takeIf { it.isNotBlank() }
        if (newSub == null && newSubp == null) return false
        var changed = false
        context.authDataStore.edit { prefs ->
            if ((prefs[Keys.UID] ?: "") != expectedUid) return@edit
            if (newSub != null && decodeCredential(prefs[Keys.SUB]) != newSub) {
                prefs[Keys.SUB] = encodeCredential(newSub)
                changed = true
            }
            if (newSubp != null && decodeCredential(prefs[Keys.SUBP]) != newSubp) {
                prefs[Keys.SUBP] = encodeCredential(newSubp)
                changed = true
            }
        }
        return changed
    }

    /**
     * 把会话标记为未登录，但**保留凭据密文**。
     *
     * 与 [clearSession] 的区别是这条路径可逆：单次 `-100` 或一次探活失败都不足以
     * 证明凭据真的作废（风控、限流、游客降级、解密瞬时失败都会给出同样信号）。
     * 保留密文后，重新登录成功会覆盖；若只是误判，凭据下次仍然可用。
     *
     * 同时记下 [reason] 与时间：服务端给登录态的 `ALF` 是 30 天且不续期，所以
     * 「一周就掉」一定是某条通道提前判定，这个记录是用户唯一能看到的原因。
     */
    suspend fun markSessionInvalid(reason: SessionExpiryReason) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.IS_LOGGED_IN] = false
            prefs[Keys.LAST_EXPIRE_REASON] = reason.name
            prefs[Keys.LAST_EXPIRE_AT] = System.currentTimeMillis()
        }
    }

    /** 上一次会话被判失效的原因与时间；从未失效过则为 null。 */
    val lastSessionExpiryFlow: Flow<LastSessionExpiry?> = context.authDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val name = prefs[Keys.LAST_EXPIRE_REASON] ?: return@map null
            val at = prefs[Keys.LAST_EXPIRE_AT] ?: return@map null
            val reason = SessionExpiryReason.entries.firstOrNull { it.name == name } ?: return@map null
            LastSessionExpiry(reason = reason, atEpochMillis = at)
        }

    suspend fun updateProfile(
        screenName: String,
        avatarUrl: String,
        description: String = "",
        followersCount: String = "",
        friendsCount: String = "",
        statusesCount: String = ""
    ) {
        context.authDataStore.edit { prefs ->
            if (screenName.isNotBlank()) prefs[Keys.SCREEN_NAME] = screenName
            if (avatarUrl.isNotBlank()) prefs[Keys.AVATAR_URL] = avatarUrl
            if (description.isNotBlank()) prefs[Keys.DESCRIPTION] = description
            if (followersCount.isNotBlank()) prefs[Keys.FOLLOWERS_COUNT] = followersCount
            if (friendsCount.isNotBlank()) prefs[Keys.FRIENDS_COUNT] = friendsCount
            if (statusesCount.isNotBlank()) prefs[Keys.STATUSES_COUNT] = statusesCount
        }
    }

    /**
     * 就地把历史版本明文落盘的凭据改写为密文。幂等：已是密文或没有凭据时不动。
     * 由应用启动时调用一次（见 MatterApplication）。
     */
    suspend fun migrateLegacyCredentials() {
        if (credentialCipher === CredentialCipher.None) return
        context.authDataStore.edit { prefs ->
            listOf(Keys.SUB, Keys.SUBP, Keys.RAW_COOKIE).forEach { key ->
                val value = prefs[key]
                if (!value.isNullOrBlank() && !credentialCipher.isEncrypted(value)) {
                    prefs[key] = credentialCipher.encrypt(value)
                }
            }
        }
    }

    suspend fun clearSession() {
        context.authDataStore.edit { prefs ->
            // 只清会话与凭据键。onboarding 完成标记和分组偏好不属于会话：
            // 登出不该把用户重新赶回引导页，也不该清空分组显示与排序设置。
            prefs.remove(Keys.UID)
            prefs.remove(Keys.SUB)
            prefs.remove(Keys.SUBP)
            prefs.remove(Keys.RAW_COOKIE)
            prefs.remove(Keys.SCREEN_NAME)
            prefs.remove(Keys.AVATAR_URL)
            prefs.remove(Keys.DESCRIPTION)
            prefs.remove(Keys.FOLLOWERS_COUNT)
            prefs.remove(Keys.FRIENDS_COUNT)
            prefs.remove(Keys.STATUSES_COUNT)
            prefs.remove(Keys.IS_LOGGED_IN)
            // 主动登出后不该再显示「上次失效原因」，那是给自动失效用的解释
            prefs.remove(Keys.LAST_EXPIRE_REASON)
            prefs.remove(Keys.LAST_EXPIRE_AT)
        }
    }
}

/** 上一次会话被判失效的原因与时间，用于在登录页解释「为什么又退出了」。 */
data class LastSessionExpiry(
    val reason: SessionExpiryReason,
    val atEpochMillis: Long
)
