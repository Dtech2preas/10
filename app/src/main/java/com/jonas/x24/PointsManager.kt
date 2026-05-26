package com.jonas.x24

import android.content.Context
import android.content.SharedPreferences

class PointsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("x24_points_prefs", Context.MODE_PRIVATE)

    fun getPoints(): Int {
        return prefs.getInt("current_points", 0)
    }

    fun addPoints(amount: Int) {
        val current = getPoints()
        prefs.edit().putInt("current_points", current + amount).apply()
    }

    fun deductPoints(amount: Int): Boolean {
        val current = getPoints()
        if (current >= amount) {
            prefs.edit().putInt("current_points", current - amount).apply()
            return true
        }
        return false
    }

    fun isPromoCodeUsed(code: String): Boolean {
        return prefs.getBoolean("promo_used_$code", false)
    }

    fun markPromoCodeUsed(code: String) {
        prefs.edit().putBoolean("promo_used_$code", true).apply()
    }

    fun setPoints(amount: Int) {
        prefs.edit().putInt("current_points", amount).apply()
    }
}
