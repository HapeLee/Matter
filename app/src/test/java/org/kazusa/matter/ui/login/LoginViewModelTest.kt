package org.kazusa.matter.ui.login

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private fun keep(viewModel: LoginViewModel) = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("login", vm)
            stores += store
        }
    }

    @Test
    fun 在途登录期间重复触发被忽略() = runTest {
        val gate = CompletableDeferred<Unit>()
        var logins = 0
        val vm = keep(
            LoginViewModel(
                loginByCookie = { _ ->
                    logins += 1
                    gate.await()
                    ApiResult.Success(UserSession(uid = "1", screenName = "甲"))
                },
                enterGuestSession = { }
            )
        )

        vm.loginWithCookie("SUB=first")
        advanceUntilIdle()
        assertEquals(1, logins)
        assertTrue(vm.uiState.value is LoginUiState.Loading)

        // 验证未结束前的重复触发（换了一段 Cookie / 访客模式）一律忽略
        vm.loginWithCookie("SUB=second")
        vm.loginAsGuest()
        advanceUntilIdle()
        assertEquals(1, logins)
        assertTrue(vm.uiState.value is LoginUiState.Loading)

        gate.complete(Unit)
        advanceUntilIdle()
        val state = vm.uiState.value as LoginUiState.Success
        assertEquals("1", state.uid)
    }

    @Test
    fun 登录失败展示错误且不阻塞再次登录() = runTest {
        var fail = true
        val vm = keep(
            LoginViewModel(
                loginByCookie = { _ ->
                    if (fail) ApiResult.Error(message = "Cookie 无效")
                    else ApiResult.Success(UserSession(uid = "2", screenName = "乙"))
                },
                enterGuestSession = { }
            )
        )

        vm.loginWithCookie("SUB=x")
        advanceUntilIdle()
        assertEquals("Cookie 无效", (vm.uiState.value as LoginUiState.Error).message)

        fail = false
        vm.loginWithCookie("SUB=y")
        advanceUntilIdle()
        assertTrue(vm.uiState.value is LoginUiState.Success)
    }

    @Test
    fun resetState取消在途任务且晚到结果不写回() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = keep(
            LoginViewModel(
                loginByCookie = { _ ->
                    gate.await()
                    ApiResult.Success(UserSession(uid = "3", screenName = "丙"))
                },
                enterGuestSession = { }
            )
        )

        vm.loginWithCookie("SUB=z")
        advanceUntilIdle()
        assertTrue(vm.uiState.value is LoginUiState.Loading)

        vm.resetState()
        assertEquals(LoginUiState.Idle, vm.uiState.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(LoginUiState.Idle, vm.uiState.value)
    }

    @Test
    fun 访客登录成功写入成功状态() = runTest {
        val vm = keep(
            LoginViewModel(
                loginByCookie = { ApiResult.Error(message = "未在测试中使用") },
                enterGuestSession = { }
            )
        )

        vm.loginAsGuest()
        advanceUntilIdle()

        val state = vm.uiState.value as LoginUiState.Success
        assertEquals("guest", state.uid)
    }
}
