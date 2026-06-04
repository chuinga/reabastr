package com.reabastr.app.di

import android.content.Context
import com.reabastr.app.scanner.ScannerService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScannerModule {

    @Provides
    @Singleton
    fun provideScannerService(
        @ApplicationContext context: Context
    ): ScannerService {
        return ScannerService(context)
    }
}
