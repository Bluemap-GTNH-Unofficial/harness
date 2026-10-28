package dev.fywinds.bluemapgtnh.harness

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import cpw.mods.fml.common.registry.GameData
import net.minecraft.nbt.JsonToNBT
import net.minecraft.nbt.NBTBase
import net.minecraft.nbt.NBTTagByte
import net.minecraft.nbt.NBTTagByteArray
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagDouble
import net.minecraft.nbt.NBTTagFloat
import net.minecraft.nbt.NBTTagInt
import net.minecraft.nbt.NBTTagIntArray
import net.minecraft.nbt.NBTTagList
import net.minecraft.nbt.NBTTagLong
import net.minecraft.nbt.NBTTagShort
import net.minecraft.nbt.NBTTagString

/**
 * Merges a patch into an NBT compound. JSON patches are typed as follows (see README "Tile entity NBT"):
 * - `{"@byte": 1}`, `@short`, `@int`, `@long` (number or decimal string), `@float`, `@double`, `@string`,
 *   `@bytes` / `@ints` (arrays), `@list` (list of patch values): explicit type;
 * - `{"@itemId": "modid:name"}` / `{"@blockId": "modid:name"}`: the numeric item / block id of that registry name in
 *   this world (an int; also accepted as an element of `@ints` / `@bytes`);
 * - otherwise the type of the tag already at that key (numbers and booleans are converted to it, arrays become a
 *   byte/int array or a list of the existing element type);
 * - otherwise: integral number -> int (long if out of int range), other number -> double, boolean -> byte,
 *   string -> string, array -> list, object -> compound;
 * - objects merge into an existing compound key by key, `null` removes the key; lists and arrays are replaced.
 */
object NbtPatch {
    private const val BYTE = 1
    private const val SHORT = 2
    private const val INT = 3
    private const val LONG = 4
    private const val FLOAT = 5
    private const val DOUBLE = 6
    private const val BYTE_ARRAY = 7
    private const val STRING = 8
    private const val LIST = 9
    private const val COMPOUND = 10
    private const val INT_ARRAY = 11

    fun merge(target: NBTTagCompound, patch: JsonObject) {
        for ((key, value) in patch.entrySet()) {
            if (value.isJsonNull) {
                target.removeTag(key)
                continue
            }
            val existing = if (target.hasKey(key)) target.getTag(key) else null
            target.setTag(key, convert(value, existing))
        }
    }

    /** SNBT (1.7.10 `JsonToNBT` syntax: `{a:1b,b:2s,c:3,d:4L,e:5.0f,f:6.0d,g:"x",h:[1,2,3]}`), merged recursively. */
    fun mergeSnbt(target: NBTTagCompound, snbt: String) {
        val patch = try {
            JsonToNBT.func_150315_a(snbt)
        } catch (e: Exception) {
            throw BmhException("invalid SNBT: ${e.message}")
        }
        if (patch !is NBTTagCompound) throw BmhException("SNBT must be a compound")
        mergeTag(target, patch)
    }

    private fun mergeTag(target: NBTTagCompound, patch: NBTTagCompound) {
        for (key in patch.func_150296_c().map { it as String }) {
            val value = patch.getTag(key)
            val existing = if (target.hasKey(key)) target.getTag(key) else null
            if (value is NBTTagCompound && existing is NBTTagCompound) mergeTag(existing, value)
            else target.setTag(key, value.copy())
        }
    }

    private fun convert(json: JsonElement, existing: NBTBase?): NBTBase {
        val hint = existing?.id?.toInt() ?: 0
        typed(json)?.let { return it }
        return when {
            json.isJsonObject -> {
                val compound = (existing as? NBTTagCompound)?.copy() as NBTTagCompound? ?: NBTTagCompound()
                merge(compound, json.asJsonObject)
                compound
            }
            json.isJsonArray -> array(json.asJsonArray, hint, (existing as? NBTTagList)?.func_150303_d() ?: 0)
            json.isJsonPrimitive -> primitive(json.asJsonPrimitive, hint)
            else -> throw BmhException("cannot convert $json to NBT")
        }
    }

    private fun typed(json: JsonElement): NBTBase? {
        if (!json.isJsonObject) return null
        val entries = json.asJsonObject.entrySet()
        if (entries.size != 1) return null
        val (key, v) = entries.first()
        if (!key.startsWith("@")) return null
        return try {
            when (key) {
                "@byte" -> NBTTagByte(v.asByte)
                "@short" -> NBTTagShort(v.asShort)
                "@int" -> NBTTagInt(v.asInt)
                "@itemId", "@blockId" -> NBTTagInt(intOf(json))
                "@long" -> NBTTagLong(v.asLong)
                "@float" -> NBTTagFloat(v.asFloat)
                "@double" -> NBTTagDouble(v.asDouble)
                "@string" -> NBTTagString(v.asString)
                "@bytes" -> NBTTagByteArray(v.asJsonArray.map { intOf(it).toByte() }.toByteArray())
                "@ints" -> NBTTagIntArray(v.asJsonArray.map { intOf(it) }.toIntArray())
                "@list" -> array(v.asJsonArray, LIST, 0)
                else -> throw BmhException("unknown NBT type $key")
            }
        } catch (e: BmhException) {
            throw e
        } catch (e: Exception) {
            throw BmhException("bad $key value $v: $e")
        }
    }

    private fun array(json: JsonArray, hint: Int, elementHint: Int): NBTBase = when (hint) {
        BYTE_ARRAY -> NBTTagByteArray(json.map { intOf(it).toByte() }.toByteArray())
        INT_ARRAY -> NBTTagIntArray(json.map { intOf(it) }.toIntArray())
        else -> NBTTagList().also { list ->
            for (element in json) {
                val tag = if (elementHint != 0 && typed(element) == null) {
                    convert(element, placeholder(elementHint))
                } else {
                    convert(element, null)
                }
                list.appendTag(tag)
            }
        }
    }

    /** A number, or `{"@itemId": "modid:name"}` / `{"@blockId": "modid:name"}` resolved through the FML registries. */
    private fun intOf(json: JsonElement): Int {
        if (!json.isJsonObject) return json.asInt
        val (key, v) = json.asJsonObject.entrySet().singleOrNull() ?: throw BmhException("expected a number, got $json")
        val name = v.asString
        return when (key) {
            "@itemId" -> {
                val registry = GameData.getItemRegistry()
                if (!registry.containsKey(name)) throw BmhException("unknown item $name")
                registry.getId(name)
            }
            "@blockId" -> {
                val registry = GameData.getBlockRegistry()
                if (!registry.containsKey(name)) throw BmhException("unknown block $name")
                registry.getId(name)
            }
            else -> throw BmhException("expected a number, @itemId or @blockId, got $json")
        }
    }

    /** An empty tag of the given type, only used to steer [convert] for list elements. */
    private fun placeholder(type: Int): NBTBase? = when (type) {
        BYTE -> NBTTagByte(0)
        SHORT -> NBTTagShort(0)
        INT -> NBTTagInt(0)
        LONG -> NBTTagLong(0)
        FLOAT -> NBTTagFloat(0f)
        DOUBLE -> NBTTagDouble(0.0)
        BYTE_ARRAY -> NBTTagByteArray(ByteArray(0))
        STRING -> NBTTagString("")
        LIST -> NBTTagList()
        COMPOUND -> NBTTagCompound()
        INT_ARRAY -> NBTTagIntArray(IntArray(0))
        else -> null
    }

    private fun primitive(p: JsonPrimitive, hint: Int): NBTBase {
        if (hint == STRING) return NBTTagString(p.asString)
        if (p.isString && hint !in BYTE..DOUBLE) return NBTTagString(p.asString)
        val number: Number = when {
            p.isBoolean -> if (p.asBoolean) 1 else 0
            p.isNumber -> p.asNumber
            else -> p.asString.toBigDecimalOrNull() ?: throw BmhException("\"${p.asString}\" is not a number")
        }
        return when (hint) {
            BYTE -> NBTTagByte(number.toByte())
            SHORT -> NBTTagShort(number.toShort())
            INT -> NBTTagInt(number.toInt())
            LONG -> NBTTagLong(number.toLong())
            FLOAT -> NBTTagFloat(number.toFloat())
            DOUBLE -> NBTTagDouble(number.toDouble())
            else -> {
                if (p.isBoolean) return NBTTagByte(number.toByte())
                val decimal = p.asBigDecimal
                val integral = decimal.stripTrailingZeros().scale() <= 0 && !p.asString.contains('.')
                when {
                    !integral -> NBTTagDouble(decimal.toDouble())
                    decimal.toLong() in Int.MIN_VALUE..Int.MAX_VALUE -> NBTTagInt(decimal.toInt())
                    else -> NBTTagLong(decimal.toLong())
                }
            }
        }
    }
}
