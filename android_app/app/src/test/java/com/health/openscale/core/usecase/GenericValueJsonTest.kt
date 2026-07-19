/*
 * openScale
 * Copyright (C) 2026 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.usecase

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip coverage for [GenericValueJson], the self-describing value payload shared by the
 * sync Intent and the ContentProvider. The provider's insert AND update paths rely on
 * [GenericValueJson.parse] returning every numeric type (incl. custom) in the user's unit, so a
 * build -> parse round-trip must preserve the values regardless of unit or predefined/custom type.
 *
 * Runs under Robolectric because [GenericValueJson] uses android's org.json implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenericValueJsonTest {

    private companion object {
        const val EPS = 1e-3f

        // Predefined types kept in their canonical unit (no conversion on the wire).
        val weightKg = MeasurementType(id = 1, key = MeasurementTypeKey.WEIGHT, unit = UnitType.KG)
        val fatPercent = MeasurementType(id = 2, key = MeasurementTypeKey.BODY_FAT, unit = UnitType.PERCENT)
        // Predefined type in a non-canonical unit -> exercises inch<->cm conversion on the wire.
        val waistInch = MeasurementType(id = 3, key = MeasurementTypeKey.WAIST, unit = UnitType.INCH)
        // Custom types are matched by typeId (key == "CUSTOM") on parse.
        val chestCustomCm = MeasurementType(id = 50, key = MeasurementTypeKey.CUSTOM, name = "Chest tape", unit = UnitType.CM)
        val noteCustomText = MeasurementType(
            id = 60, key = MeasurementTypeKey.CUSTOM, name = "Note", unit = UnitType.NONE, inputType = InputFieldType.TEXT
        )

        val allTypes = listOf(weightKg, fatPercent, waistInch, chestCustomCm, noteCustomText)
        val typesById = allTypes.associateBy { it.id }
        val typesByKey = allTypes.associateBy { it.key.name }
    }

    private fun value(typeId: Int, float: Float? = null, text: String? = null) =
        MeasurementValue(measurementId = 1, typeId = typeId, floatValue = float, textValue = text)

    private fun roundTrip(values: List<MeasurementValue>): Map<Int, Float> =
        GenericValueJson.parse(GenericValueJson.build(values, typesById), typesByKey, typesById)
            .toMap()

    @Test
    fun roundTrip_preservesNumericValuesAcrossPredefinedCustomAndUnits() {
        val parsed = roundTrip(
            listOf(
                value(weightKg.id, 80.5f),      // canonical unit, exact
                value(fatPercent.id, 18.4f),    // percent, no conversion
                value(waistInch.id, 36.0f),     // inch -> cm -> inch conversion
                value(chestCustomCm.id, 42.3f), // custom type matched by typeId
            )
        )

        assertThat(parsed[weightKg.id]).isWithin(EPS).of(80.5f)
        assertThat(parsed[fatPercent.id]).isWithin(EPS).of(18.4f)
        assertThat(parsed[waistInch.id]).isWithin(EPS).of(36.0f)
        assertThat(parsed[chestCustomCm.id]).isWithin(EPS).of(42.3f)
    }

    @Test
    fun roundTrip_skipsNonNumericValues() {
        // A TEXT value carries no "value" field, so it must not appear in the parsed numeric set.
        val parsed = roundTrip(
            listOf(
                value(weightKg.id, 80.5f),
                value(noteCustomText.id, text = "morning weigh-in"),
            )
        )

        assertThat(parsed).containsKey(weightKg.id)
        assertThat(parsed).doesNotContainKey(noteCustomText.id)
    }

    @Test
    fun parse_ignoresTypesUnknownToTheReceiver() {
        // Built with the full type set, but parsed by a receiver that doesn't know the waist type
        // (e.g. it was removed) -> that entry is dropped instead of failing the whole payload.
        val json = GenericValueJson.build(
            listOf(value(weightKg.id, 70f), value(waistInch.id, 34f)),
            typesById,
        )
        val reducedById = typesById.filterKeys { it != waistInch.id }
        val reducedByKey = typesByKey.filterKeys { it != waistInch.key.name }

        val parsed = GenericValueJson.parse(json, reducedByKey, reducedById).toMap()

        assertThat(parsed).containsKey(weightKg.id)
        assertThat(parsed).doesNotContainKey(waistInch.id)
    }
}
