package com.nimchat.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest as officialPostgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.realtime.PostgresAction
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
}

class NimChatRealtime(private val delegate: Realtime) {
    suspend fun connect() = delegate.connect()
    fun channel(id: String): RealtimeChannel = delegate.officialChannel(id)
    fun removeAllChannels() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { delegate.removeAllChannels() }
    }
}

val SupabaseClient.postgrest: NimChatPostgrest
    get() = NimChatPostgrest(this.officialPostgrest)

val SupabaseClient.realtime: NimChatRealtime
    get() = NimChatRealtime(this.officialRealtime)

inline fun <reified T : PostgresAction> RealtimeChannel.postgresChangeFlow(
    schema: String,
    noinline filter: io.github.jan.supabase.realtime.PostgresChangeFilter.() -> Unit = {}
): Flow<T> = this.officialPostgresChangeFlow(schema, filter)
