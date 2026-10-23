package org.kazusa.matter.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "matter_auth_pref")

class AuthPreferences(private val context: Context) {

    private object Keys {
        val UID = stringPreferencesKey("uid")
        val SUB = stringPreferencesKey("sub")
        val SUBP = stringPreferencesKey("subp")
        val GSID = stringPreferencesKey("gsid")
        val RAW_COOKIE = stringPreferencesKey("raw_cookie")
        val TOKEN = stringPreferencesKey("token")
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
                sub = prefs[Keys.SUB] ?: "",
                subp = prefs[Keys.SUBP] ?: "",
                gsid = prefs[Keys.GSID] ?: "",
                rawCookie = prefs[Keys.RAW_COOKIE] ?: "",
                token = prefs[Keys.TOKEN] ?: "",
                screenName = prefs[Keys.SCREEN_NAME] ?: "",
                avatarUrl = prefs[Keys.AVATAR_URL] ?: "",
                description = prefs[Keys.DESCRIPTION] ?: "",
                followersCount = prefs[Keys.FOLLOWERS_COUNT] ?: "",
                friendsCount = prefs[Keys.FRIENDS_COUNT] ?: "",
                statusesCount = prefs[Keys.STATUSES_COUNT] ?: "",
                isLoggedIn = prefs[Keys.IS_LOGGED_IN] ?: false
            )
        }

    suspend fun saveSession(
        uid: String,
        sub: String = "",
        subp: String = "",
        gsid: String = "",
        rawCookie: String = "",
        token: String = "",
        screenName: String = "",
        avatarUrl: String = "",
        description: String = "",
        followersCount: String = "",
        friendsCount: String = "",
        statusesCount: String = ""
    ) {
        context.authDataStore.edit { prefs ->
            prefs[Keys.UID] = uid
            if (sub.isNotBlank()) prefs[Keys.SUB] = sub
            if (subp.isNotBlank()) prefs[Keys.SUBP] = subp
            if (gsid.isNotBlank()) prefs[Keys.GSID] = gsid
            if (rawCookie.isNotBlank()) prefs[Keys.RAW_COOKIE] = rawCookie
            if (token.isNotBlank()) prefs[Keys.TOKEN] = token
            if (screenName.isNotBlank()) prefs[Keys.SCREEN_NAME] = screenName
            if (avatarUrl.isNotBlank()) prefs[Keys.AVATAR_URL] = avatarUrl
            if (description.isNotBlank()) prefs[Keys.DESCRIPTION] = description
            if (followersCount.isNotBlank()) prefs[Keys.FOLLOWERS_COUNT] = followersCount
            if (friendsCount.isNotBlank()) prefs[Keys.FRIENDS_COUNT] = friendsCount
            if (statusesCount.isNotBlank()) prefs[Keys.STATUSES_COUNT] = statusesCount
            prefs[Keys.IS_LOGGED_IN] = true
        }
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

    suspend fun clearSession() {
        context.authDataStore.edit { prefs ->
            // 只清会话与凭据键。onboarding 完成标记和分组偏好不属于会话：
            // 登出不该把用户重新赶回引导页，也不该清空分组显示与排序设置。
            prefs.remove(Keys.UID)
            prefs.remove(Keys.SUB)
            prefs.remove(Keys.SUBP)
            prefs.remove(Keys.GSID)
            prefs.remove(Keys.RAW_COOKIE)
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.SCREEN_NAME)
            prefs.remove(Keys.AVATAR_URL)
            prefs.remove(Keys.DESCRIPTION)
            prefs.remove(Keys.FOLLOWERS_COUNT)
            prefs.remove(Keys.FRIENDS_COUNT)
            prefs.remove(Keys.STATUSES_COUNT)
            prefs.remove(Keys.IS_LOGGED_IN)
        }
    }
}
