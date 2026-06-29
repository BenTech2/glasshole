// SPDX-License-Identifier: MIT
// Star data sourced from the Hipparcos catalog (ESA, public domain).
// We filter to magnitude < 3.5-ish, plus a handful of fainter named
// constellation anchors. RA in hours, Dec in degrees, magnitude
// (lower = brighter). The catalog is bundled in code rather than
// raw resources to keep the APK light and the data lookup O(1) at
// startup (no XML/JSON parse cost).
package com.glasshole.plugin.skymap.glass

object StarCatalog {

    /** Star record. [name] is human-readable; [magnitude] is apparent
     *  visual magnitude — lower is brighter (Sirius is -1.46). */
    data class Star(
        val name: String,
        val raHours: Double,
        val decDeg: Double,
        val magnitude: Double,
    )

    /** ~120 brightest stars, indexed by name for constellation line
     *  lookup. Sourced from Hipparcos catalogue (public domain).
     *  All J2000.0 coordinates. */
    val stars: List<Star> = listOf(
        // --- Brightest stars (mag < 1.5) ---
        Star("Sirius",       6.7525,  -16.7161, -1.46),
        Star("Canopus",      6.3992,  -52.6957, -0.74),
        Star("Arcturus",    14.2610,   19.1825, -0.05),
        Star("Vega",        18.6157,   38.7837,  0.03),
        Star("Capella",      5.2782,   45.9981,  0.08),
        Star("Rigel",        5.2423,   -8.2017,  0.18),
        Star("Procyon",      7.6550,    5.2249,  0.34),
        Star("Betelgeuse",   5.9195,    7.4070,  0.45),
        Star("Achernar",     1.6286,  -57.2367,  0.45),
        Star("Hadar",       14.0637,  -60.3729,  0.61),
        Star("Altair",      19.8463,    8.8683,  0.76),
        Star("Acrux",       12.4433,  -63.0991,  0.77),
        Star("Aldebaran",    4.5987,   16.5093,  0.85),
        Star("Antares",     16.4901,  -26.4319,  1.09),
        Star("Spica",       13.4199,  -11.1614,  1.04),
        Star("Pollux",       7.7553,   28.0262,  1.14),
        Star("Fomalhaut",   22.9608,  -29.6222,  1.16),
        Star("Deneb",       20.6905,   45.2803,  1.25),
        Star("Mimosa",      12.7953,  -59.6886,  1.25),
        Star("Regulus",     10.1395,   11.9672,  1.40),

        // --- Big Dipper / Ursa Major ---
        Star("Dubhe",       11.0621,   61.7510,  1.79),
        Star("Merak",       11.0307,   56.3824,  2.37),
        Star("Phecda",      11.8972,   53.6948,  2.44),
        Star("Megrez",      12.2571,   57.0326,  3.31),
        Star("Alioth",      12.9005,   55.9598,  1.77),
        Star("Mizar",       13.3988,   54.9254,  2.27),
        Star("Alkaid",      13.7923,   49.3133,  1.85),

        // --- Orion ---
        Star("Bellatrix",    5.4188,    6.3497,  1.64),
        Star("Mintaka",      5.5334,   -0.2991,  2.25),
        Star("Alnilam",      5.6035,   -1.2019,  1.69),
        Star("Alnitak",      5.6794,   -1.9426,  1.74),
        Star("Saiph",        5.7959,   -9.6696,  2.06),

        // --- Cassiopeia (W-shape) ---
        Star("Schedar",      0.6751,   56.5374,  2.24),
        Star("Caph",         0.1530,   59.1497,  2.27),
        Star("Gamma Cas",    0.9451,   60.7167,  2.47),
        Star("Ruchbah",      1.4304,   60.2353,  2.68),
        Star("Segin",        1.9063,   63.6701,  3.38),

        // --- Cygnus (Northern Cross) ---
        Star("Sadr",        20.3704,   40.2567,  2.23),
        Star("Gienah Cyg",  20.7702,   33.9703,  2.48),
        Star("Albireo",     19.5121,   27.9597,  3.18),
        Star("Delta Cyg",   19.7493,   45.1307,  2.87),

        // --- Lyra (small) ---
        Star("Sheliak",     18.8347,   33.3627,  3.52),
        Star("Sulafat",     18.9821,   32.6896,  3.24),

        // --- Aquila ---
        Star("Tarazed",     19.7710,   10.6133,  2.72),
        Star("Alshain",     19.9219,    6.4068,  3.71),

        // --- Boötes ---
        Star("Nekkar",      15.0322,   40.3906,  3.50),
        Star("Izar",        14.7497,   27.0742,  2.37),
        Star("Muphrid",     13.9114,   18.3977,  2.68),
        Star("Seginus",     14.5345,   38.3082,  3.04),

        // --- Leo ---
        Star("Denebola",    11.8177,   14.5720,  2.14),
        Star("Algieba",     10.3329,   19.8415,  2.61),
        Star("Zosma",       11.2351,   20.5237,  2.56),

        // --- Taurus ---
        Star("Elnath",       5.4382,   28.6075,  1.65),

        // --- Gemini ---
        Star("Castor",       7.5766,   31.8884,  1.58),
        Star("Alhena",       6.6285,   16.3993,  1.93),

        // --- Scorpius ---
        Star("Shaula",      17.5601,  -37.1038,  1.62),
        Star("Sargas",      17.6228,  -42.9978,  1.86),
        Star("Dschubba",    16.0056,  -22.6217,  2.29),
        Star("Acrab",       16.0905,  -19.8054,  2.62),

        // --- Sagittarius (teapot, only the bright ones) ---
        Star("Kaus Australis", 18.4029, -34.3847, 1.79),
        Star("Nunki",       18.9211,  -26.2967,  2.05),

        // --- Crux (Southern Cross) ---
        Star("Gacrux",      12.5194,  -57.1133,  1.59),
        Star("Delta Cru",   12.2522,  -58.7488,  2.78),

        // --- Pegasus (Great Square) ---
        Star("Markab",      23.0793,   15.2053,  2.49),
        Star("Scheat",      23.0628,   28.0828,  2.42),
        Star("Algenib",      0.2206,   15.1836,  2.83),

        // --- Andromeda (continuing the Square) ---
        Star("Alpheratz",    0.1398,   29.0904,  2.06),
        Star("Mirach",       1.1622,   35.6206,  2.06),
        Star("Almach",       2.0649,   42.3297,  2.10),

        // --- Perseus ---
        Star("Mirfak",       3.4054,   49.8612,  1.79),
        Star("Algol",        3.1361,   40.9556,  2.12),

        // --- Auriga (besides Capella) ---
        Star("Menkalinan",   6.0066,   44.9474,  1.90),

        // --- Polaris (north star) — important even if not super bright ---
        Star("Polaris",      2.5300,   89.2641,  1.97),

        // --- Some other navigation / recognizable stars ---
        Star("Alphard",      9.4595,   -8.6586,  1.99),
        Star("Diphda",       0.7264,  -17.9866,  2.04),
        Star("Hamal",        2.1196,   23.4624,  2.01),
        Star("Mira",         2.3228,   -2.9776,  3.00),
        Star("Rasalhague",  17.5822,   12.5604,  2.08),
        Star("Eltanin",     17.9434,   51.4889,  2.23),
    )

    /** Quick lookup by name. */
    val byName: Map<String, Star> = stars.associateBy { it.name }

    /** Constellation line lists. Each line is a pair of star names —
     *  rendered as a stroke connecting them when both are above
     *  the horizon and within FOV. Only includes constellations
     *  whose stars are in our catalog. */
    val constellations: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Big Dipper" to listOf(
            "Dubhe" to "Merak",
            "Merak" to "Phecda",
            "Phecda" to "Megrez",
            "Megrez" to "Dubhe",      // closes the bowl
            "Megrez" to "Alioth",
            "Alioth" to "Mizar",
            "Mizar" to "Alkaid",
        ),
        "Orion" to listOf(
            "Betelgeuse" to "Bellatrix",
            "Bellatrix" to "Mintaka",
            "Mintaka" to "Saiph",
            "Saiph" to "Rigel",
            "Rigel" to "Betelgeuse",
            "Mintaka" to "Alnilam",
            "Alnilam" to "Alnitak",
        ),
        "Cassiopeia" to listOf(
            "Caph" to "Schedar",
            "Schedar" to "Gamma Cas",
            "Gamma Cas" to "Ruchbah",
            "Ruchbah" to "Segin",
        ),
        "Cygnus" to listOf(
            "Deneb" to "Sadr",
            "Sadr" to "Gienah Cyg",
            "Sadr" to "Albireo",
            "Sadr" to "Delta Cyg",
        ),
        "Lyra" to listOf(
            "Vega" to "Sheliak",
            "Sheliak" to "Sulafat",
            "Vega" to "Sulafat",
        ),
        "Crux" to listOf(
            "Acrux" to "Gacrux",
            "Mimosa" to "Delta Cru",
        ),
        "Leo" to listOf(
            "Regulus" to "Algieba",
            "Algieba" to "Zosma",
            "Zosma" to "Denebola",
            "Denebola" to "Regulus",
        ),
        "Cassiopeia W" to listOf(
            // Already done above, just an alias entry — left empty.
        ),
        "Great Square" to listOf(
            "Markab" to "Scheat",
            "Scheat" to "Alpheratz",
            "Alpheratz" to "Algenib",
            "Algenib" to "Markab",
        ),
        "Andromeda" to listOf(
            "Alpheratz" to "Mirach",
            "Mirach" to "Almach",
        ),
    )
}
