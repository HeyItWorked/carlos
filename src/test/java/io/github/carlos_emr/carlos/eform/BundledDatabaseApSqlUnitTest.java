/**
 * Copyright (c) 2026 CARLOS Contributors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * This software is published under the GPL GNU General Public License.
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * CARLOS EMR Project
 * https://github.com/carlos-emr/carlos
 */

package io.github.carlos_emr.carlos.eform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.xml.bind.JAXBContext;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.carlos_emr.carlos.db.LegacyJdbcQuery;
import io.github.carlos_emr.carlos.eform.data.DatabaseAP;
import io.github.carlos_emr.carlos.eform.data.EForm;
import io.github.carlos_emr.carlos.eform.data.EFormApConfig;
import io.github.carlos_emr.carlos.managers.NioFileManager;
import io.github.carlos_emr.carlos.report.data.ParameterizedSql;
import io.github.carlos_emr.carlos.test.unit.CarlosUnitTestBase;
import io.github.carlos_emr.carlos.utility.XmlUtils;

/** Validates the shipped AP catalog at the same parameterization and SQL-safety boundary as rendering. */
@Tag("unit")
class BundledDatabaseApSqlUnitTest extends CarlosUnitTestBase {
    private static final Pattern SQL_LITERAL = Pattern.compile("'((?:[^']|'')*)'|\"((?:[^\"]|\"\")*)\"");

    @BeforeEach
    void registerDependencies() {
        registerMock(NioFileManager.class, mock(NioFileManager.class));
    }

    @Test
    void shouldAcceptEveryBundledReadQuery_afterRequestParameterization() throws Exception {
        List<String> failures = new ArrayList<>();
        int queries = 0;
        for (DatabaseAP ap : catalog()) {
            if (ap.getApSQL() == null || ap.getApSQL().isBlank()) continue;
            queries++;
            ParameterizedSql query = new EForm().parameterizeAllFields(ap.getApSQL());
            try {
                EFormSqlSafety.validateLegacySqlSafety(query.getSql());
            } catch (SecurityException error) {
                failures.add(ap.getApName() + ": " + error.getMessage());
            }
        }
        assertThat(queries).isGreaterThan(300);
        assertThat(failures).as("Every bundled AP must pass the unchanged render-time safety validator").isEmpty();
    }

    @ParameterizedTest(name = "{0} preserves its authored line separators")
    @MethodSource("newlineDefinitions")
    void shouldPreserveNewlineLiteralValues_withoutSqlModeDependentEscapes(String name, List<String> expected) throws Exception {
        DatabaseAP ap = definition(name);
        ParameterizedSql query = new EForm().parameterizeAllFields(ap.getApSQL());
        assertThatCode(() -> EFormSqlSafety.validateLegacySqlSafety(query.getSql())).doesNotThrowAnyException();
        var matcher = SQL_LITERAL.matcher(query.getSql());
        List<String> lineSeparators = new ArrayList<>();
        while (matcher.find()) {
            String literal = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if (literal.contains("\n")) lineSeparators.add(literal);
        }
        assertThat(lineSeparators).containsExactlyElementsOf(expected);
    }

    @Test
    void shouldKeepDecimalPointsInLatestLabValues_withoutAnAmbiguousSqlEscape() throws Exception {
        String sql = new EForm().parameterizeAllFields(definition("latest_lab_values").getApSQL()).getSql();
        assertThatCode(() -> EFormSqlSafety.validateLegacySqlSafety(sql)).doesNotThrowAnyException();
        var matcher = Pattern.compile("REGEXP_REPLACE\\(m\\.dataField, '([^']+)', ' '\\)").matcher(sql);
        assertThat(matcher.find()).isTrue();
        String regex = matcher.group(1);
        assertThat(regex).isEqualTo("[^[:alnum:]^.]+");
    }

    @Test
    void shouldRenderMedicalHistory_insteadOfSilentlyClearingTheClinicalField() throws Exception {
        DatabaseAP ap = definition("medical_history");
        String history = "Synthetic first history\n Synthetic second & follow-up\n";
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("medicalHistory")).thenReturn(history);
        List<ParameterizedSql> executed = new ArrayList<>();
        try (var loader = mockStatic(EFormLoader.class);
             var jdbc = mockStatic(LegacyJdbcQuery.class, CALLS_REAL_METHODS)) {
            loader.when(EFormLoader::getMarker).thenReturn("oscarDB");
            loader.when(() -> EFormLoader.getAP("medical_history")).thenReturn(ap);
            jdbc.when(() -> LegacyJdbcQuery.getPreparedResultSet(any(ParameterizedSql.class)))
                    .thenAnswer(invocation -> {
                        ParameterizedSql query = invocation.getArgument(0);
                        executed.add(query);
                        return rows;
                    });
            EForm form = new EForm();
            form.setDemographicNo("7");
            form.setFormHtml("<form><textarea name=\"MedicalHistory\" oscarDB=medical_history></textarea></form>");
            form.setDatabaseAPs();
            // Misc.getString deliberately trims the result's outer whitespace;
            // the clinical paragraphs and embedded newline must survive unchanged.
            assertThat(Jsoup.parse(form.getFormHtml()).selectFirst("textarea[name=MedicalHistory]").wholeText()).isEqualTo(history.strip());
            assertThat(executed).hasSize(1);
            assertThat(executed.getFirst().getParams()).containsExactly("7", "7");
        }
    }

    private static Stream<Arguments> newlineDefinitions() {
        Map<String, List<String>> definitions = new LinkedHashMap<>();
        definitions.put("bc_referral_address", List.of("\n", "\n"));
        definitions.put("bc_family_doctor_address", List.of("\n", "\n"));
        for (String name : List.of("social_family_history", "other_medications_history", "family_history",
                "medical_history", "ongoingconcerns", "past_ocular_history", "today_notes", "druglist")) {
            definitions.put(name, List.of("\n"));
        }
        definitions.put("allergies_des", List.of(" \n", ",\n\t\t\t"));
        definitions.put("recent_rx", List.of("\n\n", " \n"));
        definitions.put("today_rx", List.of("\n\n", " \n"));
        definitions.put("current_rx", List.of("\n", "\n"));
        definitions.put("current_rx_lt", List.of("\n", "\n"));
        definitions.put("druglist_generic", List.of(" \n"));
        return definitions.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static DatabaseAP definition(String name) throws Exception {
        return catalog().stream().filter(ap -> name.equals(ap.getApName())).findFirst().orElseThrow();
    }

    private static List<DatabaseAP> catalog() throws Exception {
        try (var stream = BundledDatabaseApSqlUnitTest.class.getResourceAsStream("/oscar/eform/apconfig.xml")) {
            assertThat(stream).isNotNull();
            var unmarshaller = JAXBContext.newInstance(EFormApConfig.class).createUnmarshaller();
            return ((EFormApConfig) unmarshaller.unmarshal(XmlUtils.createSecureJaxbSource(stream))).getDatabaseAPs();
        }
    }
}
