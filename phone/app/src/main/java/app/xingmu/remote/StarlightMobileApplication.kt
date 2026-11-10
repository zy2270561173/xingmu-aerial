package app.xingmu.remote

import android.app.Application
import app.xingmu.remote.network.BackendClient
import app.xingmu.remote.security.TokenStore

class StarlightMobileApplication : Application() {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var backendClient: BackendClient
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        backendClient = BackendClient(this)
    }
}
