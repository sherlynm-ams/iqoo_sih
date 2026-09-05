package com.crosscheck.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PaymentEntity::class, ClaimEntity::class, FlaggedVpaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paymentDao(): PaymentDao
    abstract fun claimDao(): ClaimDao
    abstract fun flaggedVpaDao(): FlaggedVpaDao

    companion object {
        const val NAME = "crosscheck.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()
    }
}
