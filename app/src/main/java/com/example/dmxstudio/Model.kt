package com.example.dmxstudio

// Per-universe output route (destination IP and KiNET port of a power/data supply)
data class Route(val ip: String, val port: Int)

// ColorPlay-like effect preset
data class EffectPreset(
    val name: String,
    val type: Int,
    val c1: Int,
    val c2: Int,
    val periodMs: Int,
    val size: Float,
    val dir: Int,
    val level: Int,
    val universe: Int
)

// One step of a show timeline. kind: 0 = scene, 1 = effect, 2 = media clip
data class ShowItem(val kind: Int, val index: Int, val durMs: Int, val fadeMs: Int)

data class Show(val name: String, val items: List<ShowItem>, val loop: Boolean)

// Schedule entry (LSM-like)
// kind: 0 = fixed time, 1 = sunset + offset, 2 = sunrise + offset
// minutes: time of day (kind 0) or offset in minutes (kind 1/2, may be negative)
// days: bit 0 = Monday ... bit 6 = Sunday
// action: 0 = off, 1 = scene, 2 = effect, 3 = show
data class Sched(
    val enabled: Boolean,
    val kind: Int,
    val minutes: Int,
    val days: Int,
    val action: Int,
    val target: Int
)

// Image or video file used for pixel mapping (VSM-like)
data class MediaClip(val name: String, val uri: String, val fps: Int, val isVideo: Boolean)

fun rgbOf(r: Int, g: Int, b: Int): Int = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
fun rOf(c: Int): Int = (c shr 16) and 255
fun gOf(c: Int): Int = (c shr 8) and 255
fun bOf(c: Int): Int = c and 255
