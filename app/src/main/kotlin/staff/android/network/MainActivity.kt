package staff.android.network

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

class CatalogOwner : ViewModel() {
    private val client = CatalogClient("http://127.0.0.1:8787/".toHttpUrl())
    private val store = CatalogStore(client)
    val state = store.state
    fun refresh() { viewModelScope.launch { store.refresh() } }
    fun loadMore() { viewModelScope.launch { store.loadMore() } }
    fun switchAccount(account: String) {
        viewModelScope.launch { store.switchAccount(account); store.refresh() }
    }
    override fun onCleared() { client.close() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val owner = ViewModelProvider(this)[CatalogOwner::class.java]
        setContent {
            CommonGoodsTheme {
                val state by owner.state.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CommonGoodsHeader()
                        Text("Shop", style = MaterialTheme.typography.headlineLarge)
                        Text("Good things for everyday.", style = MaterialTheme.typography.bodyLarge)
                        Button(owner::refresh, modifier = Modifier.fillMaxWidth().testTag("refresh")) {
                            Text(stringResource(R.string.refresh))
                        }
                        if (state.loading) ShopMessage("Loading…", Modifier.testTag("loading"))
                        state.problem?.let { ShopMessage(it, Modifier.testTag("problem")) }
                        if (!state.loading && state.items.isEmpty() && state.problem == null) {
                            ShopMessage(if (state.snapshot == null) "Refresh to load products." else "No products.")
                        }
                        state.items.forEach { product ->
                            val whole = product.priceMinor / 100
                            val fraction = (product.priceMinor % 100).toString().padStart(2, '0')
                            val amount = "$whole.$fraction"
                            ProductPhoto(product.id)
                            Text("${product.title} · ${product.currency} $amount",
                                Modifier.fillMaxWidth().testTag("product:${product.id}"),
                                style = MaterialTheme.typography.titleLarge)
                            HorizontalDivider()
                        }
                        OutlinedButton(owner::loadMore,
                            enabled = !state.loading && state.nextCursor != null,
                            modifier = Modifier.fillMaxWidth().testTag("load_more")) {
                            Text(stringResource(R.string.load_more))
                        }
                        LabControls {
                            Text("Account ${state.account}", Modifier.testTag("account"))
                            Text("Disposable lab accounts; " +
                                "USD prices come from the local service.")
                            Button({ owner.switchAccount("a") }, modifier = Modifier.testTag("switch_a")) {
                                Text(stringResource(R.string.switch_a))
                            }
                            Button({ owner.switchAccount("b") }, modifier = Modifier.testTag("switch_b")) {
                                Text(stringResource(R.string.switch_b))
                            }
                        }
                    }
                }
            }
        }
    }
}
