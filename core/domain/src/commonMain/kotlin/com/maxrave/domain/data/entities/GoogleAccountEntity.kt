package com.maxrave.domain.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class GoogleAccountEntity(
    @PrimaryKey(autoGenerate = false)
    val email: String = "",
    val name: String = "",
    val thumbnailUrl: String = "",
    val pageId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val authUser: Int = 0,
    val cache: String? = null,
    val isUsed: Boolean = false,
    val netscapeCookie: String? = null,
)