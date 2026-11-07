package com.gs5.kycaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CQRS at the database: schema {@code query} is written and read only by the projection package, and
 * the projection package never touches schema {@code command}. ArchUnit cannot see SQL text, so the
 * rule reads the sources; the guards make sure it found something to check.
 */
class RiskSchemaAccessRulesTest {

    private static final Path SOURCES = Path.of("src/main/java/com/gs5/kycaml");
    private static final Pattern QUERY_SQL = tablesOf("query", "compliance_risk_view|front_office_status");
    private static final Pattern COMMAND_SQL = tablesOf("command", "risk_profile_history|risk_profile|outbox");

    /** Any mention of a table of the schema, wherever it stands in the statement. */
    private static Pattern tablesOf(String schema, String tables) {
        return Pattern.compile("(?<![\\w.])" + schema + "\\.(" + tables + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    private static List<Path> sources() {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static boolean mentions(Path source, Pattern sql) {
        try {
            return sql.matcher(Files.readString(source)).find();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static boolean inProjection(Path source) {
        return source.toString().replace('\\', '/').contains("/projection/");
    }

    @Test
    @DisplayName("only the projection package reads or writes schema query")
    void shouldKeepTheQuerySchemaInTheProjectionPackage() {
        var touching = sources().stream().filter(source -> mentions(source, QUERY_SQL)).toList();

        assertThat(touching).isNotEmpty().allMatch(RiskSchemaAccessRulesTest::inProjection);
    }

    @Test
    @DisplayName("the projection package never touches schema command")
    void shouldKeepTheProjectionAwayFromTheCommandSchema() {
        var projection = sources().stream().filter(RiskSchemaAccessRulesTest::inProjection).toList();
        var touchingCommand = sources().stream().filter(source -> mentions(source, COMMAND_SQL)).toList();

        assertThat(projection).isNotEmpty().noneMatch(source -> mentions(source, COMMAND_SQL));
        assertThat(touchingCommand).isNotEmpty();
    }
}
