package com.example.scrolltrack.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(appMetadata: AppMetadata)

    @Query("SELECT * FROM app_metadata WHERE package_name = :packageName")
    suspend fun getByPackageName(packageName: String): AppMetadata?

    @Query("UPDATE app_metadata SET is_installed = 0, is_icon_cached = 0, last_update_timestamp = :timestamp WHERE package_name = :packageName")
    suspend fun markAsUninstalled(packageName: String, timestamp: Long)

    @Query("SELECT package_name FROM app_metadata")
    suspend fun getAllKnownPackageNames(): List<String>

    @Query("SELECT package_name FROM app_metadata WHERE is_user_visible = 0")
    suspend fun getNonVisiblePackageNames(): List<String>

    @Query("SELECT * FROM app_metadata")
    suspend fun getAll(): List<AppMetadata>

    @Query("UPDATE app_metadata SET user_hides_override = :userHidesOverride WHERE package_name = :packageName")
    suspend fun updateUserHidesOverride(packageName: String, userHidesOverride: Boolean?)
} 