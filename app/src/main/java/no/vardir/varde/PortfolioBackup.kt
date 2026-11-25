package no.vardir.varde

import org.json.JSONObject
import java.util.Locale

internal fun encodePortfolioBackup(account: String, products: List<Product>): String =
    JSONObject().put("format", "varde-portfolio").put("backupVersion", 1)
        .put("account", account.lowercase(Locale.ROOT))
        .put("portfolio", JSONObject(encodeProducts(products))).toString(2)

internal fun decodePortfolioBackup(raw: String, account: String): List<Product> {
    require(raw.length <= 2_000_000) { "Backup is too large." }
    val backup = JSONObject(raw)
    require(backup.getString("format") == "varde-portfolio" && backup.getInt("backupVersion") == 1) { "Unsupported backup." }
    require(account.isNotBlank() && backup.getString("account").equals(account, ignoreCase = true)) {
        "This backup belongs to a different GitHub account."
    }
    return decodeProducts(backup.getJSONObject("portfolio").toString())
}
