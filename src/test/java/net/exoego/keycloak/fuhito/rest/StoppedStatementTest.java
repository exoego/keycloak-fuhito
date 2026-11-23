package net.exoego.keycloak.fuhito.rest;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoppedStatementTest {

    private static String message(Throwable error) {
        return StoppedStatement.of(error).orElseThrow().message();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "'ERROR: deadlock detected\n  Detail: Process 79 waits for ShareLock on transaction 812.' | deadlock detected",
        "ERROR: canceling statement due to user request | canceling statement due to user request",
        "canceling statement due to statement timeout | canceling statement due to statement timeout",
        "'' | ''",
    })
    void takesTheFirstLineOfTheMessageWithoutItsSeverity(String text, String message) {
        assertEquals(message, message(new SQLException(text, "57014")));
    }

    @Test
    void takesTheMessageOfABatchFromTheNextException() {
        var batch = new BatchUpdateException("Batch entry 0 update fuhito_org_unit was aborted: ERROR: deadlock "
                + "detected  Call getNextException to see other errors in the batch.", "40P01", new int[0]);
        batch.setNextException(new SQLException("ERROR: deadlock detected\n  Hint: See server log.", "40P01"));

        assertEquals("deadlock detected", message(new IllegalStateException(batch)));
    }

    @Test
    void findsNothingWithoutAnSqlState() {
        assertEquals(Optional.empty(), StoppedStatement.of(new IllegalStateException(new SQLException("no state"))));
        assertEquals(Optional.empty(), StoppedStatement.of(new IllegalStateException("no database")));
    }
}
