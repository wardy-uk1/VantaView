// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.di

import android.content.Context
import androidx.room.Room
import com.vantaview.app.data.db.VantaDb
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): VantaDb =
        Room.databaseBuilder(context, VantaDb::class.java, "vanta.db")
            .addMigrations(VantaDb.MIGRATION_1_2, VantaDb.MIGRATION_3_4, VantaDb.MIGRATION_4_5, VantaDb.MIGRATION_5_6, VantaDb.MIGRATION_6_7, VantaDb.MIGRATION_7_8, VantaDb.MIGRATION_8_9, VantaDb.MIGRATION_9_10, VantaDb.MIGRATION_10_11)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        // Servers that forget their middle certificate still connect, as they do in a web browser.
        .apply { runCatching { com.vantaview.app.data.security.AiaTrustManager.create() }.getOrNull()?.let { (f, tm) -> sslSocketFactory(f, tm) } }
        // Certificates the user chose to trust are accepted even when their name doesn't match.
        .hostnameVerifier(com.vantaview.app.data.security.CertPins.hostnameVerifier(okhttp3.internal.tls.OkHostnameVerifier))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
