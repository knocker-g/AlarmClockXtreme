package com.sysadmindoc.alarmclock.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.alarmclock.data.local.ActigraphySessionDao
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.AlarmEventDao
import com.sysadmindoc.alarmclock.data.local.AlarmGroupDao
import com.sysadmindoc.alarmclock.data.local.AlarmIncidentEventDao
import com.sysadmindoc.alarmclock.data.local.DirectBootOccurrenceReceiptDao
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.NewsSourceDao
import com.sysadmindoc.alarmclock.data.local.PreSleepTagDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.SnoreEventDao
import com.sysadmindoc.alarmclock.domain.MainSlotApplier
import com.sysadmindoc.alarmclock.domain.RealMainSlotApplier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.sysadmindoc.alarmclock.data.holiday.FileManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.holiday.ManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverrideSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AlarmDatabase {
        return Room.databaseBuilder(
            context,
            AlarmDatabase::class.java,
            "alarm_clock.db"
        )
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
    }

    @Provides
    @Singleton
    fun provideAlarmDao(database: AlarmDatabase): AlarmDao {
        return database.alarmDao()
    }

    @Provides
    @Singleton
    fun provideAlarmEventDao(database: AlarmDatabase): AlarmEventDao {
        return database.alarmEventDao()
    }

    @Provides
    @Singleton
    fun provideActigraphySessionDao(database: AlarmDatabase): ActigraphySessionDao {
        return database.actigraphySessionDao()
    }

    @Provides
    @Singleton
    fun provideAlarmIncidentEventDao(database: AlarmDatabase): AlarmIncidentEventDao {
        return database.alarmIncidentEventDao()
    }

    @Provides
    @Singleton
    fun provideSnoreEventDao(database: AlarmDatabase): SnoreEventDao {
        return database.snoreEventDao()
    }

    @Provides
    @Singleton
    fun providePreSleepTagDao(database: AlarmDatabase): PreSleepTagDao {
        return database.preSleepTagDao()
    }

    @Provides
    @Singleton
    fun provideAlarmGroupDao(database: AlarmDatabase): AlarmGroupDao {
        return database.alarmGroupDao()
    }

    @Provides
    @Singleton
    fun provideNewsSourceDao(database: AlarmDatabase): NewsSourceDao {
        return database.newsSourceDao()
    }

    @Provides
    @Singleton
    fun provideScheduleAuthorityDao(database: AlarmDatabase): ScheduleAuthorityDao {
        return database.scheduleAuthorityDao()
    }

    @Provides
    @Singleton
    fun provideScheduleExceptionDao(database: AlarmDatabase): com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao {
        return database.scheduleExceptionDao()
    }

    @Provides
    @Singleton
    fun provideDurableOperationIntentDao(database: AlarmDatabase): DurableOperationIntentDao {
        return database.durableOperationIntentDao()
    }

    @Provides
    @Singleton
    fun provideDirectBootOccurrenceReceiptDao(database: AlarmDatabase): DirectBootOccurrenceReceiptDao {
        return database.directBootOccurrenceReceiptDao()
    }

    @Provides
    @Singleton
    fun provideDurableOneTimeOverrideAdapter(
        database: AlarmDatabase,
        coordinator: com.sysadmindoc.alarmclock.domain.projection.UpcomingScheduleReconcileCoordinator,
        alarmScheduler: com.sysadmindoc.alarmclock.domain.AlarmScheduler
    ): com.sysadmindoc.alarmclock.domain.evaluator.DurableOneTimeOverrideAdapter {
        return com.sysadmindoc.alarmclock.domain.evaluator.DurableOneTimeOverrideAdapter(
            database = database,
            coordinator = coordinator,
            alarmScheduler = alarmScheduler
        )
    }

    @Provides
    @Singleton
    fun provideMainSlotApplier(impl: RealMainSlotApplier): MainSlotApplier {
        return impl
    }

    @Provides
    @Singleton
    fun provideExactPermissionChecker(@ApplicationContext context: Context): () -> Boolean {
        return {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                alarmManager?.canScheduleExactAlarms() == true
            } else {
                true
            }
        }
    }

    @Provides
    @Singleton
    fun provideManualHolidayOverrideStore(
        @ApplicationContext context: Context,
        coordinatorProvider: javax.inject.Provider<com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator>
    ): ManualHolidayOverrideStore {
        return FileManualHolidayOverrideStore(
            context = context,
            reconcileCoordinatorProvider = { coordinatorProvider.get() }
        )
    }

    @Provides
    @Singleton
    fun provideManualHolidayOverrideSource(store: ManualHolidayOverrideStore): ManualHolidayOverrideSource {
        return store
    }
}
