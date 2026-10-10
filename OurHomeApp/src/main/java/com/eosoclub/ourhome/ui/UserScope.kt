package com.eosoclub.ourhome.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Holds the signed-in user's screen ViewModels. Screens use `viewModel {}`,
 * which would otherwise be scoped to the activity and survive a sign-out — the
 * next account on a shared phone would start from the last one's data and
 * permission grid. Kept in an activity ViewModel so a rotation still keeps it.
 */
class UserScopes : ViewModel() {
    private var userId: String? = null
    private var store = ViewModelStore()

    /** The store for [id]; a different user (or null = signed out) starts empty. */
    fun storeFor(id: String?): ViewModelStore {
        if (id != userId) {
            store.clear()
            store = ViewModelStore()
            userId = id
        }
        return store
    }

    override fun onCleared() = store.clear()
}

/** Runs [content] with ViewModels that belong to [userId] only. */
@Composable
fun UserScope(userId: String, content: @Composable () -> Unit) {
    val scopes = viewModel { UserScopes() }
    val store = scopes.storeFor(userId)
    val owner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = store
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

/** Drops the last user's ViewModels (call when signed out). */
@Composable
fun ClearUserScope() {
    viewModel { UserScopes() }.storeFor(null)
}
