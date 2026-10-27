package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import net.minecraft.nbt.NBTBase
import net.minecraft.nbt.NBTTagByteArray
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagIntArray
import net.minecraft.nbt.NBTTagList
import net.minecraft.nbt.NBTTagString

/**
 * Lossy NBT -> JSON: compounds become objects, lists and arrays become arrays, numbers become JSON numbers
 * (the NBT numeric type is not preserved).
 */
object NbtJson {
    fun toJson(tag: NBTBase?): JsonElement = when (tag) {
        null -> JsonNull.INSTANCE
        is NBTTagCompound -> JsonObject().also { obj ->
            for (key in tag.func_150296_c().map { it as String }.sorted()) obj.add(key, toJson(tag.getTag(key)))
        }
        is NBTTagList -> JsonArray().also { arr ->
            // NBTTagList has no public element getter for arbitrary tag types; drain a copy instead.
            val copy = tag.copy() as NBTTagList
            while (copy.tagCount() > 0) arr.add(toJson(copy.removeTag(0)))
        }
        is NBTTagString -> JsonPrimitive(tag.func_150285_a_())
        is NBTTagByteArray -> JsonArray().also { arr -> tag.func_150292_c().forEach { arr.add(JsonPrimitive(it)) } }
        is NBTTagIntArray -> JsonArray().also { arr -> tag.func_150302_c().forEach { arr.add(JsonPrimitive(it)) } }
        is NBTBase.NBTPrimitive -> when (tag.id.toInt()) {
            5 -> JsonPrimitive(tag.func_150288_h()) // float
            6 -> JsonPrimitive(tag.func_150286_g()) // double
            4 -> JsonPrimitive(tag.func_150291_c()) // long
            else -> JsonPrimitive(tag.func_150287_d()) // byte, short, int
        }
        else -> JsonPrimitive(tag.toString())
    }
}
