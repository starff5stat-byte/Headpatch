package com.zx.ffhs

data class PatchEntry(
    val name: String,
    val rva: String,
    val orig: String,
    val patch: String,
    val enabled: Boolean
)

data class PatchSet(
    val gameVersion: String,
    val lib: String,
    val entries: List<PatchEntry>,
    val strings: List<PatchEntry>
)
