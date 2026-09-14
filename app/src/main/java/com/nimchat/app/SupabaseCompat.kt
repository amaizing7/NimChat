package com.nimchat.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest as officialPostgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.postgrest.query.filter.FilterOperation
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.PostgresChangeFilter
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel as officialChannel
import io.github.jan.supabase.realtime.postgresChangeFlow as officialPostgresChangeFlow
import io.github.jan.supabase.realtime.realtime as officialRealtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NimChatPostgrest(private val delegate: Postgrest) {
    suspend fun rpc(function: String): PostgrestResult = delegate.rpc(function)
    suspend fun rpc(function: String, params: CreateConversationParams): PostgrestResult =
        delegate.rpc(function, buildJsonObject { put("target_user", params.targetUser) })
    suspend fun rpc(function: String, params: MarkConversationReadParams): PostgrestResult =
        delegate.rpc(function, buildJsonObject { put("target_conversation", params.targetConversation) })
}

class NimChatRealtime(private val delegate: Realtime) {
    suspend fun connect() = delegate.connect()
    fun channel(id: String): RealtimeChannel = delegate.officialChannel(id)
    fun removeAllChannels() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { delegate.removeAllChannels() }
    }
}

class NimChatRealtimeFilter {
    var table: String? = null
    private var filterOperation: FilterOperation? = null

    fun eq(column: String, value: Any) {
        filterOperation = FilterOperation(column, FilterOperator.EQ, value)
    }

    fun build(): Pair<String?, FilterOperation?> = table to filterOperation
}

val SupabaseClient.postgrest: NimChatPostgrest
    get() = NimChatPostgrest(this.officialPostgrest)

val SupabaseClient.realtime: NimChatRealtime
    get() = NimChatRealtime(this.officialRealtime)

inline fun <reified T : PostgresAction> RealtimeChannel.postgresChangeFlow(
    schema: String,
    noinline filter: NimChatRealtimeFilter.() -> Unit = {}
): Flow<T> {
    val builder = NimChatRealtimeFilter().apply(filter)
    val (table, operation) = builder.build()
    return this.officialPostgresChangeFlow<T>(schema) {
        this.table = table
        operation?.let { this.filter(it) }
    }
}

fun PostgresChangeFilter.filter(builder: NimChatRealtimeFilter.() -> Unit) {
    val spec = NimChatRealtimeFilter().apply(builder).build().second
    spec?.let { this.filter(it) }
}
