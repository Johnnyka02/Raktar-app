package hu.hanizso.raktar.data

import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val id: String,
    val ean: String = "",
    val sku: String = "",
    val name: String,
    val qty: Int = 0,
    val minQty: Int = 0,
    val buyPrice: Double? = null,
    val sellPrice: Double? = null,
    val note: String = "",
) {
    val level: StockLevel
        get() = when {
            qty <= 0 -> StockLevel.OUT
            qty <= minQty -> StockLevel.LOW
            else -> StockLevel.OK
        }
}

enum class StockLevel { OK, LOW, OUT }

@Serializable
data class Move(
    val id: String,
    val productId: String,
    val ean: String = "",
    val name: String = "",
    val delta: Int,
    val type: String,
    val at: Long,
    val qtyAfter: Int? = null,
    val device: String = "",
)

object MoveType {
    const val OUT = "kiadás"
    const val IN = "bevét"
    const val FIX = "korrekció"
    const val MANUAL = "kézi"
}

@Serializable
data class Settings(
    val url: String = "",
    val key: String = "",
    val deviceName: String = "",
)

/** Ami a telefonon el van mentve. */
@Serializable
data class LocalState(
    val serverProducts: List<Product> = emptyList(),
    val serverMoves: List<Move> = emptyList(),
    val pendingMoves: List<Move> = emptyList(),
    val pendingProducts: List<Product> = emptyList(),
    val pendingDeletes: List<String> = emptyList(),
    val lastSync: Long = 0,
) {
    val pendingCount: Int get() = pendingMoves.size + pendingProducts.size + pendingDeletes.size

    /** A telefonon látható készlet: szerver állapot + még el nem küldött változások. */
    fun products(): List<Product> {
        val map = LinkedHashMap<String, Product>()
        serverProducts.forEach { map[it.id] = it }
        pendingProducts.forEach { p -> map[p.id] = p.copy(qty = map[p.id]?.qty ?: 0) }
        pendingDeletes.forEach { map.remove(it) }
        pendingMoves.forEach { m -> map[m.productId]?.let { map[m.productId] = it.copy(qty = it.qty + m.delta) } }
        return map.values.toList()
    }
}
