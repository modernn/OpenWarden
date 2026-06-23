package com.openwarden.parent.pairing

/**
 * The pinned six-emoji SAS table + bit→emoji mapping (PROTOCOL §7.4, ADR-038 D2/D3).
 *
 * [EMOJIS] is the Matrix/Element `m.sas.v1` 64-emoji list in canonical index order 0–63. The order
 * is **load-bearing canon** — both pairing peers index this exact list, so a reorder is a breaking
 * change requiring a superseding ADR. It is mirrored byte-for-byte by `docs/test-vectors/pairing/
 * pair-09-*` and the [SixEmojiSas] golden vector.
 *
 * Pure: no platform / crypto dependency, so the mapping is host-tested directly in `commonTest`.
 */
object SasEmojiTable {
    /** Number of emojis displayed (§7.4). */
    const val EMOJI_COUNT: Int = 6

    /** Bits consumed per emoji — 64-entry table ⇒ 6-bit index (ADR-038 D3). */
    const val BITS_PER_EMOJI: Int = 6

    /**
     * Index 0–63 → emoji (Matrix/Element `m.sas.v1`, ADR-038 appendix). Exactly 64 distinct entries;
     * `init` asserts both invariants so a typo can never silently shrink the table or alias an index.
     */
    val EMOJIS: List<String> =
        listOf(
            "🐶", // 0  Dog
            "🐱", // 1  Cat
            "🦁", // 2  Lion
            "🐎", // 3  Horse
            "🦄", // 4  Unicorn
            "🐷", // 5  Pig
            "🐘", // 6  Elephant
            "🐰", // 7  Rabbit
            "🐼", // 8  Panda
            "🐓", // 9  Rooster
            "🐧", // 10 Penguin
            "🐢", // 11 Turtle
            "🐟", // 12 Fish
            "🐙", // 13 Octopus
            "🦋", // 14 Butterfly
            "🌷", // 15 Flower
            "🌳", // 16 Tree
            "🌵", // 17 Cactus
            "🍄", // 18 Mushroom
            "🌏", // 19 Globe
            "🌙", // 20 Moon
            "☁️", // 21 Cloud
            "🔥", // 22 Fire
            "🍌", // 23 Banana
            "🍎", // 24 Apple
            "🍓", // 25 Strawberry
            "🌽", // 26 Corn
            "🍕", // 27 Pizza
            "🎂", // 28 Cake
            "❤️", // 29 Heart
            "😀", // 30 Smiley
            "🤖", // 31 Robot
            "🎩", // 32 Hat
            "👓", // 33 Glasses
            "🔧", // 34 Spanner
            "🎅", // 35 Santa
            "👍", // 36 Thumbs Up
            "☂️", // 37 Umbrella
            "⌛", //       38 Hourglass
            "⏰", //       39 Clock
            "🎁", // 40 Gift
            "💡", // 41 Light Bulb
            "📕", // 42 Book
            "✏️", // 43 Pencil
            "📎", // 44 Paperclip
            "✂️", // 45 Scissors
            "🔒", // 46 Padlock
            "🔑", // 47 Key
            "🔨", // 48 Hammer
            "☎️", // 49 Telephone
            "🏁", // 50 Flag
            "🚂", // 51 Train
            "🚲", // 52 Bicycle
            "✈️", // 53 Aeroplane
            "🚀", // 54 Rocket
            "🏆", // 55 Trophy
            "⚽", //       56 Ball
            "🎸", // 57 Guitar
            "🎺", // 58 Trumpet
            "🔔", // 59 Bell
            "⚓", //       60 Anchor
            "🎧", // 61 Headphones
            "📁", // 62 Folder
            "📌", // 63 Pin
        )

    init {
        require(EMOJIS.size == 64) { "SAS emoji table MUST be exactly 64 entries, got ${EMOJIS.size}" }
        require(EMOJIS.toSet().size == 64) { "SAS emoji table entries MUST be distinct" }
    }

    /**
     * Map a HKDF output to [EMOJI_COUNT] emojis (ADR-038 D3). Reads the first
     * `EMOJI_COUNT * BITS_PER_EMOJI` (= 36) bits big-endian — bit *k* = `(out[k/8] >> (7 - k%8)) & 1`,
     * bit 0 = MSB of `out[0]` — as six 6-bit indices into [EMOJIS]. Remaining bits are unused.
     *
     * Fail-closed: requires enough bytes to cover 36 bits (the §7.4 output is 16 bytes).
     */
    fun lookup(out: ByteArray): List<String> {
        val bitsNeeded = EMOJI_COUNT * BITS_PER_EMOJI
        require(out.size * 8 >= bitsNeeded) {
            "SAS lookup needs >= $bitsNeeded bits, got ${out.size * 8}"
        }
        return (0 until EMOJI_COUNT).map { i ->
            var idx = 0
            for (b in 0 until BITS_PER_EMOJI) {
                val bitPos = i * BITS_PER_EMOJI + b
                val bit = (out[bitPos / 8].toInt() ushr (7 - (bitPos % 8))) and 1
                idx = (idx shl 1) or bit
            }
            EMOJIS[idx]
        }
    }
}
