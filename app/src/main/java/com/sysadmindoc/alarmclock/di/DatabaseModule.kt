package com.sysadmindoc.alarmclock.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.alarmclock.data.local.ActigraphySessionDao
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.AlarmEventDao
import com.sysadmindoc.alarmclock.data.local.AlarmGroupDao
import com.sysadmindoc.alarmclock.data.local.AlarmIncidentEventDao
import com.sysadmindoc.alarmclock.data.local.NewsSourceDao
import com.sysadmindoc.alarmclock.data.local.PreSleepTagDao
import com.sysadmindoc.alarmclock.data.local.SnoreEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO alarm_groups (name) " +
                            "VALUES ('Work'), ('School'), ('Gym'), ('Medication'), ('Personal'), ('Calendar')"
                    )
                }
            })
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
}
