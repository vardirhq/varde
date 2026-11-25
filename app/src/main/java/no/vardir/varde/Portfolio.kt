package no.vardir.varde

import org.json.JSONArray
import org.json.JSONObject

internal data class WorkItem(
    val id: String,
    val title: String,
    val kind: String = "Task",
    val details: String = "",
    val url: String = "",
    val done: Boolean = false
)

internal data class Product(
    val id: String,
    val name: String,
    val description: String = "",
    val stage: String = "Exploring",
    val priority: String = "Normal",
    val next: String = "",
    val milestones: String = "",
    val ideas: String = "",
    val repositoryIds: List<Long> = emptyList(),
    val focused: Boolean = false,
    val work: List<WorkItem> = emptyList()
)

internal fun encodeProducts(products: List<Product>): String {
    val array = JSONArray()
    products.forEach { p ->
        val ids = JSONArray()
        p.repositoryIds.distinct().forEach { ids.put(it) }
        val work = JSONArray()
        p.work.forEach { w -> work.put(JSONObject().put("id", w.id).put("title", w.title)
            .put("kind", w.kind).put("details", w.details).put("url", w.url).put("done", w.done)) }
        array.put(JSONObject().put("work", work).put("id", p.id).put("name", p.name).put("description", p.description)
            .put("stage", p.stage).put("priority", p.priority).put("next", p.next)
            .put("milestones", p.milestones).put("ideas", p.ideas).put("repositories", ids).put("focused", p.focused))
    }
    return JSONObject().put("version", 2).put("products", array).toString()
}

internal fun decodeProducts(raw: String): List<Product> {
    val root = JSONObject(raw)
    require(root.getInt("version") in 1..2) { "Unsupported portfolio version" }
    val array = root.getJSONArray("products")
    val products = (0 until array.length()).map { index ->
        val p = array.getJSONObject(index)
        val ids = p.optJSONArray("repositories") ?: JSONArray()
        val work = p.optJSONArray("work") ?: JSONArray()
        val items = (0 until work.length()).map { i ->
            val w = work.getJSONObject(i)
            WorkItem(w.getString("id"), w.getString("title"), w.optString("kind", "Task"),
                w.optString("details", ""), w.optString("url", ""), w.optBoolean("done", false))
        }
        require(items.all { it.id.isNotBlank() && it.title.isNotBlank() && it.kind in setOf("Task", "Idea", "Milestone") } &&
            items.map { it.id }.distinct().size == items.size) { "Invalid work items" }
        Product(
            id = p.getString("id"), name = p.getString("name"),
            description = p.optString("description", ""), stage = p.optString("stage", "Exploring"),
            priority = p.optString("priority", "Normal"), next = p.optString("next", ""),
            milestones = p.optString("milestones", ""), ideas = p.optString("ideas", ""),
            repositoryIds = (0 until ids.length()).map { ids.getLong(it) }.distinct(),
            focused = p.optBoolean("focused", false), work = items
        )
    }
    require(products.all { it.id.isNotBlank() && it.name.isNotBlank() } &&
        products.map { it.id }.distinct().size == products.size) { "Invalid portfolio data" }
    return products
}

internal fun moveRepository(ids: List<Long>, index: Int, delta: Int): List<Long> {
    val target = index + delta
    if (index !in ids.indices || target !in ids.indices) return ids
    return ids.toMutableList().also { it[index] = ids[target]; it[target] = ids[index] }
}

internal fun productAttention(product: Product, items: List<AttentionItem>, excluded: Set<Long>): List<AttentionItem> =
    items.filter { it.repositoryId in product.repositoryIds && it.repositoryId !in excluded }
