package com.nimchat.app

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest as officialPostgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.postgresChangeFlow as officialPostgresChangeFlow
import io.github.jan.supabase.realtime.realtime as officialRealtime
import kotlinx.coroutines.flow.Flow

val SupabaseClient.realtime: Realtime
    get() = this.officialRealtime

val SupabaseClient.postgrest: Postgrest
    get() = this.officialPostgrest

inline fun <reified T : PostgresAction> RealtimeChannel.postgresChangeFlow(
    schema: String,
    noinline filter: io.github.jan.supabase.realtime.PostgresChangeFilter.() -> Unit = {}
): Flow<T> = this.officialPostgresChangeFlow(schema, filter)
