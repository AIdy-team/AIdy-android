package com.aidy.petrobot.data

import com.aidy.petrobot.three.RoomDecor

/** ホームのお部屋 (リビング・自分の部屋・子ども部屋)。端末に保存する。 */
object RoomPref {
    private const val Key = "aidy.room.v1"

    var current: String
        get() = Prefs.getString(Key, RoomDecor.Living).takeIf { it in RoomDecor.RoomIds } ?: RoomDecor.Living
        set(value) {
            if (value in RoomDecor.RoomIds) Prefs.putString(Key, value)
        }
}
