package no.vardir.varde

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Local products for one GitHub account. An unreadable portfolio is never overwritten. */
internal class PortfolioStore(context: Context) {
    private val prefs = context.getSharedPreferences("portfolio", Context.MODE_PRIVATE)

    var account by mutableStateOf(""); private set
    var products by mutableStateOf(emptyList<Product>()); private set
    var error by mutableStateOf<String?>(null); private set

    private val key get() = account.lowercase(Locale.ROOT)
    fun find(id: String): Product? = products.firstOrNull { it.id == id }

    fun open(user: String) {
        account = user
        if (user.isBlank()) return
        val raw = prefs.getString(key, null)
        try {
            products = if (raw == null) emptyList() else decodeProducts(raw)
            error = null
        } catch (_: Exception) {
            products = emptyList()
            error = "Your saved portfolio could not be read. It has not been changed."
        }
    }

    private fun persist(updated: List<Product>): Boolean {
        if (account.isBlank() || error != null) return false
        if (!prefs.edit().putString(key, encodeProducts(updated)).commit()) {
            error = "Couldn't save the portfolio. Your previous data has been kept."
            return false
        }
        products = updated
        return true
    }

    fun save(product: Product): Boolean {
        if (product.name.isBlank()) return false
        val sanitized = product.copy(name = product.name.trim().take(100))
        val exists = products.any { it.id == sanitized.id }
        return persist(if (exists) products.map { if (it.id == sanitized.id) sanitized else it } else products + sanitized)
    }

    fun delete(id: String): Boolean = persist(products.filterNot { it.id == id })

    fun backup(): String {
        check(account.isNotBlank() && error == null) { "Portfolio is unavailable." }
        val backup = encodePortfolioBackup(account, products)
        check(backup.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Portfolio is too large to export safely." }
        return backup
    }

    fun restore(raw: String, expectedUser: String) {
        check(account.isNotBlank() && account.equals(expectedUser, ignoreCase = true)) { "Account changed; choose the backup again." }
        val restored = decodePortfolioBackup(raw, account)
        check(prefs.edit().putString(key, encodeProducts(restored)).commit()) { "Could not save restored portfolio." }
        products = restored
        error = null
    }

    fun clear() { account = ""; products = emptyList(); error = null }
}
